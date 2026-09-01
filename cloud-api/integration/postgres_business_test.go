//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/dngmeng/cloud-api/internal/domain"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
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
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply lifecycle test migrations")
	}
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
	var userID, adminID, otherID, legacyID, batchID uuid.UUID
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if batchID != uuid.Nil {
			if _, err := raw.Exec(cleanupContext, `DELETE FROM redemption_codes WHERE batch_id=$1`, batchID); err != nil {
				t.Error("cleanup fixture redemption codes")
			}
			if _, err := raw.Exec(cleanupContext, `DELETE FROM code_batches WHERE id=$1`, batchID); err != nil {
				t.Error("cleanup fixture code batch")
			}
		}
		userIDs := make([]uuid.UUID, 0, 3)
		for _, id := range []uuid.UUID{userID, adminID, otherID, legacyID} {
			if id != uuid.Nil {
				userIDs = append(userIDs, id)
			}
		}
		if len(userIDs) > 0 {
			if _, err := raw.Exec(cleanupContext, `DELETE FROM users WHERE id = ANY($1)`, userIDs); err != nil {
				t.Error("cleanup fixture users")
			}
		}
	})

	user, trial, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613800138000", PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register fixture user")
	}
	userID = user.ID
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
	var storedReservedEmail string
	if err := raw.QueryRow(ctx, `SELECT email FROM users WHERE id=$1`, user.ID).Scan(&storedReservedEmail); err != nil || !strings.HasPrefix(storedReservedEmail, "phone-") || !strings.HasSuffix(storedReservedEmail, "@reserved.invalid") {
		t.Fatal("phone registration did not retain reserved email")
	}
	issuer := auth.TokenIssuer{Issuer: "integration", Audience: "integration", AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: bytes.Repeat([]byte("s"), auth.MinimumSecretBytes)}
	router := httpapi.NewRouter(httpapi.RouterOptions{Config: config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000}, Store: db, Tokens: issuer, Now: func() time.Time { return now }})
	loginRequest := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"phone":"`+storedReservedEmail+`","password":"integration-password"}`))
	loginRequest.Header.Set("Content-Type", "application/json")
	loginResponse := httptest.NewRecorder()
	router.ServeHTTP(loginResponse, loginRequest)
	if loginResponse.Code != http.StatusUnauthorized || strings.Contains(loginResponse.Body.String(), "access_token") || strings.Contains(loginResponse.Body.String(), "refresh_token") {
		t.Fatal("reserved email login was not rejected safely")
	}
	var reservedRefreshCount int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM refresh_tokens WHERE user_id=$1`, user.ID).Scan(&reservedRefreshCount); err != nil || reservedRefreshCount != 0 {
		t.Fatal("reserved email login persisted a refresh token")
	}
	legacyEmail := integrationEmail()
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, legacyEmail, hash).Scan(&legacyID); err != nil {
		t.Fatal("insert legacy email fixture")
	}
	legacyByEmail, _, err := db.UserByEmail(ctx, legacyEmail)
	if err != nil || legacyByEmail.ID != legacyID {
		t.Fatal("legacy email user was not readable")
	}
	legacyByID, err := db.UserByID(ctx, legacyID)
	if err != nil || legacyByID.Email != legacyEmail {
		t.Fatal("legacy user id read was not compatible")
	}
	refresh := auth.RefreshManager{Store: db}
	issued, err := refresh.Issue(ctx, legacyID, time.Hour, now)
	if err != nil {
		t.Fatal("issue legacy refresh")
	}
	if _, err := refresh.Rotate(ctx, issued.Plaintext, time.Hour, now.Add(time.Minute)); err != nil {
		t.Fatal("rotate legacy refresh")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: user.Username, Phone: "+8613900138000", PasswordHash: hash, Now: now}); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("duplicate username was not rejected generically")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613800138000", PasswordHash: hash, Now: now}); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("duplicate phone was not rejected generically")
	}

	admin, _, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613700138000", PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register fixture admin")
	}
	adminID = admin.ID
	if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
		t.Fatal("make fixture admin")
	}
	code, codeHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal("generate fixture redemption code")
	}
	if err := raw.QueryRow(ctx, `INSERT INTO code_batches(name,duration_days,created_by,created_by_role,created_at) VALUES('integration',365,$1,'admin',$2) RETURNING id`, admin.ID, now).Scan(&batchID); err != nil {
		t.Fatal("create fixture code batch")
	}
	if _, err := raw.Exec(ctx, `INSERT INTO redemption_codes(batch_id,code_hash) VALUES($1,$2)`, batchID, codeHash); err != nil {
		t.Fatal("create fixture redemption code")
	}
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

	other, _, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613600138000", PasswordHash: hash, Now: now})
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

	// These constraints are intentionally added only after the lifecycle cases:
	// PostgreSQL checks them in the real Register transaction, so each failure
	// proves the production rollback boundary without a fake transaction.
	if _, err := raw.Exec(ctx, `ALTER TABLE users DROP CONSTRAINT IF EXISTS phone_auth_force_user_failure`); err != nil {
		t.Fatal("reset user rollback fixture")
	}
	if _, err := raw.Exec(ctx, `ALTER TABLE entitlements DROP CONSTRAINT IF EXISTS phone_auth_force_trial_failure`); err != nil {
		t.Fatal("reset trial rollback fixture")
	}
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanupContext, `ALTER TABLE users DROP CONSTRAINT IF EXISTS phone_auth_force_user_failure`)
		_, _ = raw.Exec(cleanupContext, `ALTER TABLE entitlements DROP CONSTRAINT IF EXISTS phone_auth_force_trial_failure`)
	})
	blockedUsername := "phoneuser_" + uuid.NewString()[:8]
	if _, err := raw.Exec(ctx, `ALTER TABLE users ADD CONSTRAINT phone_auth_force_user_failure CHECK (username <> '`+blockedUsername+`') NOT VALID`); err != nil {
		t.Fatal("add user rollback fixture")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: blockedUsername, Phone: "+8613500138000", PasswordHash: hash, Now: now}); err == nil {
		t.Fatal("forced user insert failure succeeded")
	}
	var failedUserCount, failedUserEntitlements int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM users WHERE username=$1`, blockedUsername).Scan(&failedUserCount); err != nil || failedUserCount != 0 {
		t.Fatal("user insert failure left a user")
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements e JOIN users u ON u.id=e.user_id WHERE u.username=$1`, blockedUsername).Scan(&failedUserEntitlements); err != nil || failedUserEntitlements != 0 {
		t.Fatal("user insert failure left an entitlement")
	}
	if _, err := raw.Exec(ctx, `ALTER TABLE users DROP CONSTRAINT phone_auth_force_user_failure`); err != nil {
		t.Fatal("remove user rollback fixture")
	}

	blockedTrialUsername := "phoneuser_" + uuid.NewString()[:8]
	if _, err := raw.Exec(ctx, `ALTER TABLE entitlements ADD CONSTRAINT phone_auth_force_trial_failure CHECK (kind <> 'trial') NOT VALID`); err != nil {
		t.Fatal("add trial rollback fixture")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: blockedTrialUsername, Phone: "+8613400138000", PasswordHash: hash, Now: now}); err == nil {
		t.Fatal("forced trial insert failure succeeded")
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM users WHERE username=$1`, blockedTrialUsername).Scan(&failedUserCount); err != nil || failedUserCount != 0 {
		t.Fatal("trial insert failure did not roll back user")
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements e JOIN users u ON u.id=e.user_id WHERE u.username=$1`, blockedTrialUsername).Scan(&failedUserEntitlements); err != nil || failedUserEntitlements != 0 {
		t.Fatal("trial insert failure left an entitlement")
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
		{name: "standard conforming strings override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable&standard_conforming_strings=off"},
		{name: "options override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable&options=-c%20standard_conforming_strings%3Doff"},
		{name: "unknown runtime parameter", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable&application_name=unsafe"},
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

func repositoryMigrationDirectory(t *testing.T) string {
	t.Helper()
	directory, err := filepath.Abs(filepath.Join("..", "..", "migrations"))
	if err != nil {
		t.Fatal("resolve lifecycle migration directory")
	}
	if _, err := os.Stat(directory); err != nil {
		t.Fatal("inspect lifecycle migration directory")
	}
	return directory
}

func integrationEmail() string {
	return "integration-" + strings.ReplaceAll(uuid.NewString(), "-", "") + "@example.test"
}
