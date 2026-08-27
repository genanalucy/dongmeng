//go:build integration

package integration_test

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Run with CLOUD_API_TEST_DATABASE_URL against an isolated database after
// migrations. The test never loads .env or prints the supplied URL.
func TestPostgresBusinessLifecycle(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open isolated test database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	hash, err := auth.HashPassword("integration-password")
	if err != nil {
		t.Fatal("hash integration fixture password")
	}
	user, trial, err := db.Register(ctx, domain.RegisterParams{Email: integrationEmail(), PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register fixture user")
	}
	if trial.UserID != user.ID || !trial.StartsAt.Equal(now) || !trial.ExpiresAt.Equal(now.Add(3*24*time.Hour)) {
		t.Fatal("registration did not create a fixed three-day trial")
	}
	var trialCount int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements WHERE user_id=$1 AND kind='trial'`, user.ID).Scan(&trialCount); err != nil {
		t.Fatal("count registration trials")
	}
	if trialCount != 1 {
		t.Fatalf("registration trial count = %d, want 1", trialCount)
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Email: user.Email, PasswordHash: hash, Now: now}); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("duplicate registration was not rejected")
	}

	admin, _, err := db.Register(ctx, domain.RegisterParams{Email: integrationEmail(), PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register fixture admin")
	}
	if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
		t.Fatal("make fixture admin")
	}
	code, codeHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal("generate fixture redemption code")
	}
	var batchID uuid.UUID
	if err := raw.QueryRow(ctx, `INSERT INTO code_batches(name,duration_days,created_by,created_by_role,created_at) VALUES('integration',365,$1,'admin',$2) RETURNING id`, admin.ID, now).Scan(&batchID); err != nil {
		t.Fatal("create fixture code batch")
	}
	if _, err := raw.Exec(ctx, `INSERT INTO redemption_codes(batch_id,code_hash) VALUES($1,$2)`, batchID, codeHash); err != nil {
		t.Fatal("create fixture redemption code")
	}
	var otherID uuid.UUID
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := raw.Exec(cleanupContext, `DELETE FROM redemption_codes WHERE batch_id=$1`, batchID); err != nil {
			t.Error("cleanup fixture redemption codes")
		}
		if _, err := raw.Exec(cleanupContext, `DELETE FROM code_batches WHERE id=$1`, batchID); err != nil {
			t.Error("cleanup fixture code batch")
		}
		if _, err := raw.Exec(cleanupContext, `DELETE FROM users WHERE id = ANY($1)`, []uuid.UUID{user.ID, admin.ID, otherID}); err != nil {
			t.Error("cleanup fixture users")
		}
	})
	canonicalHash, err := auth.HashRedemptionCode("  " + strings.ToLower(code) + "  ")
	if err != nil {
		t.Fatal("canonicalize fixture redemption code")
	}
	packageEntitlement, err := db.RedeemCode(ctx, user.ID, canonicalHash, now)
	if err != nil {
		t.Fatal("redeem canonical code")
	}
	if packageEntitlement.Kind != string(domain.EntitlementPackage) ||
		!packageEntitlement.StartsAt.Equal(trial.ExpiresAt) ||
		!packageEntitlement.ExpiresAt.Equal(trial.ExpiresAt.Add(365*24*time.Hour)) {
		t.Fatal("redemption did not create a stacked fixed 365-day entitlement")
	}
	if _, err := db.RedeemCode(ctx, user.ID, canonicalHash, now); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("second redemption was not rejected")
	}

	active, err := db.ActiveEntitlement(ctx, user.ID, now.Add(time.Second))
	if err != nil || active.ID != trial.ID {
		t.Fatal("trial was not the active entitlement before its stacked package")
	}
	first := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateSession(ctx, first, now); err != nil {
		t.Fatal("create first active session")
	}
	if err := db.CreateSession(ctx, integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute)), now); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("second active session was not rejected")
	}

	other, _, err := db.Register(ctx, domain.RegisterParams{Email: integrationEmail(), PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register cross-user fixture")
	}
	otherID = other.ID
	if err := db.EndTranslationSession(ctx, user.ID, first.ID, now.Add(time.Second)); err != nil {
		t.Fatal("end first session before cross-user fixture")
	}
	unused := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateSession(ctx, unused, now.Add(time.Second)); err != nil {
		t.Fatal("create unused owner session")
	}
	if err := db.CreateUsageRecord(ctx, domain.CreateUsageParams{UserID: other.ID, SessionID: unused.ID, AudioSeconds: 1, Characters: 1, Now: now}); err == nil {
		t.Fatal("cross-user usage/session association was accepted")
	}
	usage := domain.CreateUsageParams{UserID: user.ID, SessionID: unused.ID, AudioSeconds: 1, Characters: 1, Now: now}
	if err := db.CreateUsageRecord(ctx, usage); err != nil {
		t.Fatal("write first session usage")
	}
	if err := db.CreateUsageRecord(ctx, usage); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("second usage record for one session was not rejected")
	}

	if err := db.EndTranslationSession(ctx, user.ID, unused.ID, now.Add(2*time.Second)); err != nil {
		t.Fatal("end unused session")
	}
	second := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateSession(ctx, second, now.Add(2*time.Second)); err != nil {
		t.Fatal("ended session did not release active-session slot")
	}
	if err := db.RevokeTranslationSession(ctx, user.ID, second.ID, now.Add(3*time.Second)); err != nil {
		t.Fatal("revoke second session")
	}
	if err := db.CreateSession(ctx, integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute)), now.Add(3*time.Second)); err != nil {
		t.Fatal("revoked session did not release active-session slot")
	}
}

func TestIsolatedPostgresTestDSNRejectsUnsafeFallbackAndOverridesBeforeConnecting(t *testing.T) {
	for _, test := range []struct {
		name string
		dsn  string
	}{
		{name: "multi host fallback", dsn: "postgres://cloud:top-secret@127.0.0.1:15432,127.0.0.1:5432/cloud"},
		{name: "query host override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?host=127.0.0.1"},
		{name: "query service override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?service=unsafe"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := validateIsolatedPostgresTestDSN(test.dsn); !errors.Is(err, migrate.ErrUnsafeDatabaseTarget) {
				t.Fatalf("validateIsolatedPostgresTestDSN() error = %v, want unsafe target", err)
			}
		})
	}
}

func isolatedPostgresTestDSN(t *testing.T) string {
	t.Helper()
	dsn := os.Getenv("CLOUD_API_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not set; skipping isolated PostgreSQL business lifecycle test")
	}
	if _, err := validateIsolatedPostgresTestDSN(dsn); err != nil {
		t.Fatal("CLOUD_API_TEST_DATABASE_URL must target the isolated 127.0.0.1:15432 test database")
	}
	return dsn
}

func validateIsolatedPostgresTestDSN(dsn string) (string, error) {
	if err := migrate.ValidateDatabaseURL(dsn, false); err != nil {
		return "", err
	}
	return dsn, nil
}

func integrationSession(userID, entitlementID uuid.UUID, installID string, expiresAt time.Time) domain.TranslationSession {
	return domain.TranslationSession{
		ID:            uuid.New(),
		UserID:        userID,
		EntitlementID: entitlementID,
		InstallID:     installID,
		JTI:           uuid.New(),
		ExpiresAt:     expiresAt,
	}
}

func integrationEmail() string {
	return "integration-" + strings.ReplaceAll(uuid.NewString(), "-", "") + "@example.test"
}
