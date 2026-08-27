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
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
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
	defer db.Close()
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated fixture pool")
	}
	defer raw.Close()

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
		t.Fatal("registration did not create exactly one fixed three-day trial")
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
	if _, err := db.CreateCodeBatch(ctx, domain.CreateBatchParams{AdminID: admin.ID, Name: "integration", DurationDays: 365, CodeHashes: [][]byte{codeHash}, Now: now}); err != nil {
		t.Fatal("create fixture code batch")
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

	usage := domain.CreateUsageParams{UserID: user.ID, SessionID: first.ID, AudioSeconds: 1, Characters: 1, Now: now}
	if err := db.CreateUsageRecord(ctx, usage); err != nil {
		t.Fatal("write first session usage")
	}
	if err := db.CreateUsageRecord(ctx, usage); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("second usage record for one session was not rejected")
	}
	other, _, err := db.Register(ctx, domain.RegisterParams{Email: integrationEmail(), PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register cross-user fixture")
	}
	if err := db.CreateUsageRecord(ctx, domain.CreateUsageParams{UserID: other.ID, SessionID: first.ID, AudioSeconds: 1, Characters: 1, Now: now}); err == nil {
		t.Fatal("cross-user usage/session association was accepted")
	}

	if err := db.EndTranslationSession(ctx, user.ID, first.ID, now.Add(time.Second)); err != nil {
		t.Fatal("end first session")
	}
	second := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateSession(ctx, second, now.Add(time.Second)); err != nil {
		t.Fatal("ended session did not release active-session slot")
	}
	if err := db.RevokeTranslationSession(ctx, user.ID, second.ID, now.Add(2*time.Second)); err != nil {
		t.Fatal("revoke second session")
	}
	if err := db.CreateSession(ctx, integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute)), now.Add(2*time.Second)); err != nil {
		t.Fatal("revoked session did not release active-session slot")
	}
}

func isolatedPostgresTestDSN(t *testing.T) string {
	t.Helper()
	url := os.Getenv("CLOUD_API_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not set; skipping isolated PostgreSQL business lifecycle test")
	}
	config, err := pgx.ParseConfig(url)
	if err != nil || config.Host != "127.0.0.1" || config.Port != 15432 || config.Database == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not an isolated 127.0.0.1:15432 database target; skipping PostgreSQL business lifecycle test")
	}
	return url
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
