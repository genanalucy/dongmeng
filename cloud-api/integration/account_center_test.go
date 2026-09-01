//go:build integration

package integration_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// This test is intentionally environment-gated by isolatedPostgresTestDSN. It
// never connects unless CLOUD_API_TEST_DATABASE_URL passes the repository's
// 127.0.0.1:15432 safety validation.
func TestAccountCenterStoreOwnershipEntitlementsAndIdentityTransaction(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open store")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	hash, err := auth.HashPassword("weak")
	if err != nil {
		t.Fatal(err)
	}
	ownerEmail, otherEmail := integrationEmail(), integrationEmail()
	var owner, other, legacy uuid.UUID
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanup, `DELETE FROM users WHERE id=ANY($1)`, []uuid.UUID{owner, other, legacy})
	})
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, ownerEmail, hash).Scan(&owner); err != nil {
		t.Fatal("insert owner")
	}
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, otherEmail, hash).Scan(&other); err != nil {
		t.Fatal("insert other")
	}
	legacyEmail := integrationEmail()
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, legacyEmail, hash).Scan(&legacy); err != nil {
		t.Fatal("insert legacy")
	}

	// Mixed owner rows prove aggregate and summary SQL are subject-filtered.
	for index, fixture := range []struct {
		user    uuid.UUID
		seconds int
		at      time.Time
	}{{owner, 7, now.Add(-2 * time.Minute)}, {other, 99, now.Add(-time.Minute)}, {owner, 11, now}} {
		entitlement := uuid.New()
		if _, err := raw.Exec(ctx, `INSERT INTO entitlements(id,user_id,kind,starts_at,expires_at) VALUES($1,$2,'trial',$3,$4)`, entitlement, fixture.user, now.Add(-time.Hour), now.Add(24*time.Hour)); err != nil {
			t.Fatal("insert entitlement")
		}
		session := uuid.New()
		if _, err := raw.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at,ended_at) VALUES($1,$2,$3,$4,$5,$6,$7,$7)`, session, fixture.user, entitlement, "account-store-"+uuid.NewString(), uuid.New(), now.Add(time.Hour), fixture.at); err != nil {
			t.Fatal("insert session")
		}
		if _, err := raw.Exec(ctx, `INSERT INTO usage_records(user_id,session_id,audio_seconds,characters,created_at) VALUES($1,$2,$3,0,$4)`, fixture.user, session, fixture.seconds, fixture.at); err != nil {
			t.Fatal("insert usage")
		}
		_ = index
	}
	overview, err := db.AccountOverview(ctx, owner)
	if err != nil || overview.Usage.AudioSeconds != 18 || overview.Usage.SessionCount != 2 || overview.Usage.LastUsedAt == nil || !overview.Usage.LastUsedAt.Equal(now) {
		t.Fatalf("owner aggregate = %#v, %v", overview.Usage, err)
	}
	page, err := db.ListAccountUsage(ctx, owner, 1, 0)
	if err != nil || len(page) != 1 || page[0].DurationSeconds != 11 || !page[0].StartedAt.Equal(now) {
		t.Fatalf("owner first page = %#v, %v", page, err)
	}
	page, err = db.ListAccountUsage(ctx, owner, 1, 1)
	if err != nil || len(page) != 1 || page[0].DurationSeconds != 7 {
		t.Fatalf("owner second page = %#v, %v", page, err)
	}
	page, err = db.ListAccountUsage(ctx, owner, 50, 2)
	if err != nil || len(page) != 0 {
		t.Fatalf("owner empty page = %#v, %v", page, err)
	}

	// Weak legacy current credentials are verified by bcrypt and the old email
	// remains untouched while empty username/phone are completed.
	updated, err := db.UpdateIdentity(ctx, domain.UpdateIdentityParams{UserID: legacy, Username: "legacy_01", Email: legacyEmail, Phone: "+8613800138000", CurrentPassword: "weak"})
	if err != nil || updated.Username != "legacy_01" || updated.Email != legacyEmail || updated.Phone != "+8613800138000" {
		t.Fatalf("legacy completion = %#v, %v", updated, err)
	}
	if _, err := db.UpdateIdentity(ctx, domain.UpdateIdentityParams{UserID: legacy, Username: "legacy_02", Email: legacyEmail, Phone: "+8613900138000", CurrentPassword: "wrong"}); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("wrong password error = %v", err)
	}
	legacyRead, err := db.UserByID(ctx, legacy)
	if err != nil || legacyRead.Username != "legacy_01" || legacyRead.Phone != "+8613800138000" {
		t.Fatalf("wrong password mutated identity: %#v, %v", legacyRead, err)
	}

	// Canonical global username, email and phone conflicts all map to one error.
	for _, input := range []domain.UpdateIdentityParams{
		{UserID: owner, Username: "legacy_01", Email: ownerEmail, Phone: "+8613700138000", CurrentPassword: "weak"},
		{UserID: owner, Username: "owner_01", Email: legacyEmail, Phone: "+8613700138000", CurrentPassword: "weak"},
		{UserID: owner, Username: "owner_01", Email: ownerEmail, Phone: "+8613800138000", CurrentPassword: "weak"},
	} {
		if _, err := db.UpdateIdentity(ctx, input); !errors.Is(err, domain.ErrConflict) {
			t.Fatalf("identity conflict error = %v", err)
		}
	}
	// A database trigger that fails after the UPDATE statement demonstrates the
	// transaction leaves all mappings unchanged when its write phase fails.
	if _, err := raw.Exec(ctx, `CREATE OR REPLACE FUNCTION account_identity_force_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.username='rollback_01' THEN RAISE EXCEPTION 'forced account identity failure'; END IF; RETURN NEW; END; $$`); err != nil {
		t.Fatal("create rollback fixture")
	}
	if _, err := raw.Exec(ctx, `CREATE TRIGGER account_identity_force_failure_trigger BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION account_identity_force_failure()`); err != nil {
		t.Fatal("create rollback trigger")
	}
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanup, `DROP TRIGGER IF EXISTS account_identity_force_failure_trigger ON users`)
		_, _ = raw.Exec(cleanup, `DROP FUNCTION IF EXISTS account_identity_force_failure()`)
	})
	if _, err := db.UpdateIdentity(ctx, domain.UpdateIdentityParams{UserID: legacy, Username: "rollback_01", Email: "rollback@example.test", Phone: "+8613900138000", CurrentPassword: "weak"}); err == nil {
		t.Fatal("forced identity write failure succeeded")
	}
	legacyRead, err = db.UserByID(ctx, legacy)
	if err != nil || legacyRead.Username != "legacy_01" || legacyRead.Email != legacyEmail || legacyRead.Phone != "+8613800138000" {
		t.Fatalf("failed identity update was not atomic: %#v, %v", legacyRead, err)
	}
	if _, err := raw.Exec(ctx, `UPDATE users SET disabled_at=$2 WHERE id=$1`, owner, now); err != nil {
		t.Fatal("disable owner")
	}
	if _, err := db.UpdateIdentity(ctx, domain.UpdateIdentityParams{UserID: owner, Username: "owner_01", Email: ownerEmail, Phone: "+8613700138000", CurrentPassword: "weak"}); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("disabled identity error = %v", err)
	}
	if _, err := db.UpdateIdentity(ctx, domain.UpdateIdentityParams{UserID: uuid.New(), Username: "owner_01", Email: ownerEmail, Phone: "+8613700138000", CurrentPassword: "weak"}); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("missing identity error = %v", err)
	}
}
