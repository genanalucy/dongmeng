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
	"github.com/jackc/pgx/v5/pgxpool"
)

// Run with CLOUD_API_TEST_DATABASE_URL against an isolated disposable database
// after applying migrations. It never loads .env or prints the URL.
func TestPostgresBusinessTransactions(t *testing.T) {
	url := os.Getenv("CLOUD_API_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not set")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open isolated test database")
	}
	defer db.Close()
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open fixture pool")
	}
	defer raw.Close()
	now := time.Now().UTC().Truncate(time.Microsecond)
	password := "integration-password"
	hash, err := auth.HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	user, trial, err := db.Register(ctx, domain.RegisterParams{Email: integrationEmail(), PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register transaction", err)
	}
	if trial.UserID != user.ID || !trial.ExpiresAt.Equal(now.Add(3*24*time.Hour)) {
		t.Fatalf("trial=%+v", trial)
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Email: user.Email, PasswordHash: hash, Now: now}); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("duplicate registration=%v", err)
	}
	admin, _, err := db.Register(ctx, domain.RegisterParams{Email: integrationEmail(), PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
		t.Fatal("make fixture admin", err)
	}
	code, codeHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.CreateCodeBatch(ctx, domain.CreateBatchParams{AdminID: admin.ID, Name: "integration", DurationDays: 365, CodeHashes: [][]byte{codeHash}, Now: now}); err != nil {
		t.Fatal("create batch", err)
	}
	inputHash, err := auth.HashRedemptionCode(strings.ToLower(code))
	if err != nil {
		t.Fatal(err)
	}
	packageEntitlement, err := db.RedeemCode(ctx, user.ID, inputHash, now)
	if err != nil {
		t.Fatal("redeem", err)
	}
	if packageEntitlement.Kind != string(domain.EntitlementPackage) || packageEntitlement.ExpiresAt.Sub(packageEntitlement.StartsAt) != 365*24*time.Hour {
		t.Fatalf("package=%+v", packageEntitlement)
	}
	if _, err := db.RedeemCode(ctx, user.ID, inputHash, now); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("second redemption=%v", err)
	}
	refresh := auth.RefreshManager{Store: db}
	initial, err := refresh.Issue(ctx, user.ID, time.Hour, now)
	if err != nil {
		t.Fatal("issue refresh", err)
	}
	rotated, err := refresh.Rotate(ctx, initial.Plaintext, time.Hour, now.Add(time.Second))
	if err != nil {
		t.Fatal("rotate refresh", err)
	}
	if _, err := refresh.Rotate(ctx, initial.Plaintext, time.Hour, now.Add(2*time.Second)); err == nil {
		t.Fatal("refresh replay accepted")
	}
	if _, err := refresh.Rotate(ctx, rotated.Plaintext, time.Hour, now.Add(3*time.Second)); err == nil {
		t.Fatal("family remained usable after replay")
	}
	sessionNow := now.Add(time.Second)
	active, err := db.ActiveEntitlement(ctx, user.ID, sessionNow)
	if err != nil {
		t.Fatalf("active entitlement before session: %v package=%+v at=%s", err, packageEntitlement, sessionNow)
	}
	if active.Kind != string(domain.EntitlementTrial) {
		t.Fatalf("expected existing trial to remain active while package is stacked: %+v", active)
	}
	session := domain.TranslationSession{ID: uuid.New(), UserID: user.ID, EntitlementID: active.ID, InstallID: "integration-install", JTI: uuid.New(), ExpiresAt: sessionNow.Add(time.Minute)}
	if err := db.CreateSession(ctx, session, sessionNow); err != nil {
		t.Fatal("create session", err)
	}
	second := session
	second.ID = uuid.New()
	second.JTI = uuid.New()
	if err := db.CreateSession(ctx, second, sessionNow); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("second active session=%v", err)
	}
}
func integrationEmail() string {
	return "integration-" + strings.ReplaceAll(uuid.NewString(), "-", "") + "@example.test"
}
