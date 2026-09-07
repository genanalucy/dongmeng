//go:build integration

package integration_test

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TestAdminSetupStoreIntegration uses a fresh schema in the same isolated
// PostgreSQL harness as the other store tests. It never runs unless
// CLOUD_API_TEST_DATABASE_URL passes the repository's loopback safety gate.
func TestAdminSetupStoreIntegration(t *testing.T) {
	t.Run("valid challenge replaces administrators once without retaining identities or secrets", func(t *testing.T) {
		db, raw, now := openAdminSetupTestStore(t)
		oldAdmin := insertSetupAdmin(t, raw, now, "old_admin")
		oldRefreshHash := auth.HashSecret("refresh-old-admin.example.test")
		if _, err := raw.Exec(context.Background(), `INSERT INTO refresh_tokens(user_id,family_id,token_hash,expires_at,created_at) VALUES($1,$2,$3,$4,$5)`, oldAdmin.id, uuid.New(), oldRefreshHash, now.Add(time.Hour), now); err != nil {
			t.Fatal("insert old administrator refresh token")
		}

		token := "setup-token-replacement.example.test"
		createSetupChallenge(t, db, token, now, now.Add(15*time.Minute))
		newUser, err := db.CompleteAdminSetup(context.Background(), setupParams(token, oldAdmin.username, oldAdmin.email, now))
		if err != nil {
			t.Fatalf("complete valid admin setup: %v", err)
		}
		if newUser.Username != oldAdmin.username || newUser.Email != oldAdmin.email || newUser.Role != "admin" {
			t.Fatalf("replacement administrator = %+v, want original reusable identity and admin role", newUser)
		}

		var disabled bool
		var tombstoneUsername, tombstoneEmail string
		if err := raw.QueryRow(context.Background(), `SELECT disabled_at IS NOT NULL, username, email FROM users WHERE id=$1`, oldAdmin.id).Scan(&disabled, &tombstoneUsername, &tombstoneEmail); err != nil {
			t.Fatal("read retired administrator")
		}
		if !disabled || tombstoneUsername == oldAdmin.username || tombstoneEmail == oldAdmin.email || !strings.HasSuffix(tombstoneEmail, "@tombstone.invalid") {
			t.Fatalf("retired admin disabled=%v username=%q email=%q, want disabled irreversible tombstone", disabled, tombstoneUsername, tombstoneEmail)
		}
		var refreshRevoked bool
		if err := raw.QueryRow(context.Background(), `SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE token_hash=$1`, oldRefreshHash).Scan(&refreshRevoked); err != nil {
			t.Fatal("read old administrator refresh token")
		}
		if !refreshRevoked {
			t.Fatal("old administrator refresh token was not revoked")
		}
		var used bool
		if err := raw.QueryRow(context.Background(), `SELECT used_at IS NOT NULL FROM admin_setup_challenges WHERE token_hash=$1`, auth.HashSecret(token)).Scan(&used); err != nil {
			t.Fatal("read setup challenge")
		}
		if !used {
			t.Fatal("successful setup challenge was not consumed")
		}
		var action, metadata string
		if err := raw.QueryRow(context.Background(), `SELECT action, metadata::text FROM audit_logs WHERE admin_id=$1 ORDER BY created_at DESC LIMIT 1`, newUser.ID).Scan(&action, &metadata); err != nil {
			t.Fatal("read setup audit record")
		}
		if action != "admin.setup.replace" || strings.Contains(metadata, oldAdmin.email) || strings.Contains(metadata, "fixture-password") || strings.Contains(metadata, token) {
			t.Fatalf("setup audit action=%q metadata=%q, want action without identity or secret material", action, metadata)
		}

		if _, err := db.CompleteAdminSetup(context.Background(), setupParams(token, "unused_admin", "unused@example.test", now.Add(time.Second))); !errors.Is(err, domain.ErrSetupUnavailable) {
			t.Fatalf("replay error = %v, want ErrSetupUnavailable", err)
		}
	})

	t.Run("expired challenge leaves existing administrator unchanged", func(t *testing.T) {
		db, raw, now := openAdminSetupTestStore(t)
		oldAdmin := insertSetupAdmin(t, raw, now, "expired_admin")
		token := "setup-token-expired.example.test"
		if _, err := raw.Exec(context.Background(), `INSERT INTO admin_setup_challenges(token_hash,expires_at,created_at) VALUES($1,$2,$3)`, auth.HashSecret(token), now.Add(-time.Second), now.Add(-time.Hour)); err != nil {
			t.Fatal("insert expired setup challenge")
		}
		if _, err := db.CompleteAdminSetup(context.Background(), setupParams(token, "replacement_admin", "replacement@example.test", now)); !errors.Is(err, domain.ErrSetupUnavailable) {
			t.Fatalf("expired setup error = %v, want ErrSetupUnavailable", err)
		}
		var disabled bool
		if err := raw.QueryRow(context.Background(), `SELECT disabled_at IS NOT NULL FROM users WHERE id=$1`, oldAdmin.id).Scan(&disabled); err != nil {
			t.Fatal("read existing administrator")
		}
		if disabled {
			t.Fatal("expired setup challenge changed existing administrator")
		}
	})

	t.Run("concurrent redemption permits exactly one replacement", func(t *testing.T) {
		db, raw, now := openAdminSetupTestStore(t)
		_ = insertSetupAdmin(t, raw, now, "concurrent_admin")
		token := "setup-token-concurrent.example.test"
		createSetupChallenge(t, db, token, now, now.Add(15*time.Minute))
		start := make(chan struct{})
		results := make(chan error, 2)
		var wait sync.WaitGroup
		for index := 0; index < 2; index++ {
			wait.Add(1)
			go func(index int) {
				defer wait.Done()
				<-start
				_, err := db.CompleteAdminSetup(context.Background(), setupParams(token, fmt.Sprintf("race_admin_%d", index), fmt.Sprintf("race-admin-%d@example.test", index), now))
				results <- err
			}(index)
		}
		close(start)
		wait.Wait()
		close(results)
		successes := 0
		for err := range results {
			if err == nil {
				successes++
				continue
			}
			if !errors.Is(err, domain.ErrSetupUnavailable) {
				t.Fatalf("concurrent completion error = %v, want only setup unavailable", err)
			}
		}
		if successes != 1 {
			t.Fatalf("concurrent setup successes = %d, want exactly 1", successes)
		}
	})
}

func TestAdminSetupMigrationGrantsRuntimeOnlyChallengeConsumption(t *testing.T) {
	baseDSN := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, baseDSN)
	if err != nil {
		t.Fatal("open permission test pool")
	}
	defer pool.Close()
	if _, err := pool.Exec(ctx, `DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='dngmeng_cloud_api') THEN CREATE ROLE dngmeng_cloud_api NOLOGIN; END IF; END $$`); err != nil {
		t.Skipf("create isolated runtime role: %v", err)
	}
	schema := "admin_setup_permissions_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err := pool.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		t.Fatal("create permission test schema")
	}
	defer func() { _, _ = pool.Exec(context.Background(), `DROP SCHEMA IF EXISTS `+schema+` CASCADE`) }()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: baseDSN, Directory: repositoryMigrationDirectory(t), Schema: schema}); err != nil {
		t.Fatal("apply permission test migrations")
	}
	for privilege, expected := range map[string]bool{"SELECT": true, "UPDATE": true, "INSERT": false, "DELETE": false} {
		var granted bool
		if err := pool.QueryRow(ctx, `SELECT has_table_privilege('dngmeng_cloud_api',$1,$2)`, schema+".admin_setup_challenges", privilege).Scan(&granted); err != nil {
			t.Fatalf("inspect %s privilege: %v", privilege, err)
		}
		if granted != expected {
			t.Fatalf("runtime %s privilege = %v, want %v", privilege, granted, expected)
		}
	}
}

type setupAdminFixture struct {
	id              uuid.UUID
	username, email string
}

func openAdminSetupTestStore(t *testing.T) (*store.Postgres, *pgxpool.Pool, time.Time) {
	t.Helper()
	baseDSN := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	basePool, err := pgxpool.New(ctx, baseDSN)
	if err != nil {
		t.Fatal("open isolated schema fixture pool")
	}
	t.Cleanup(basePool.Close)
	schema := "admin_setup_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err := basePool.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		t.Fatal("create admin setup schema")
	}
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = basePool.Exec(cleanup, `DROP SCHEMA IF EXISTS `+schema+` CASCADE`)
	})
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: baseDSN, Directory: repositoryMigrationDirectory(t), Schema: schema}); err != nil {
		t.Fatal("apply admin setup migrations")
	}
	schemaDSN := postgresSchemaDSN(t, baseDSN, schema)
	db, err := store.Open(ctx, schemaDSN)
	if err != nil {
		t.Fatal("open admin setup store")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, schemaDSN)
	if err != nil {
		t.Fatal("open admin setup raw pool")
	}
	t.Cleanup(raw.Close)
	var currentSchema string
	if err := raw.QueryRow(ctx, `SELECT current_schema()`).Scan(&currentSchema); err != nil || currentSchema != schema {
		t.Fatalf("admin setup schema=%q err=%v, want %q", currentSchema, err, schema)
	}
	return db, raw, time.Now().UTC().Truncate(time.Microsecond)
}

func postgresSchemaDSN(t *testing.T, baseDSN, schema string) string {
	t.Helper()
	parsed, err := url.Parse(baseDSN)
	if err != nil {
		t.Fatal("parse isolated PostgreSQL URL")
	}
	query := parsed.Query()
	query.Set("options", "-c search_path="+schema)
	parsed.RawQuery = query.Encode()
	return parsed.String()
}

func insertSetupAdmin(t *testing.T, raw *pgxpool.Pool, now time.Time, prefix string) setupAdminFixture {
	t.Helper()
	value := strings.ReplaceAll(uuid.NewString(), "-", "")
	fixture := setupAdminFixture{username: prefix + "_" + value[:8], email: prefix + "-" + value + "@example.test"}
	if err := raw.QueryRow(context.Background(), `INSERT INTO users(email,username,password_hash,role,created_at) VALUES($1,$2,$3,'admin',$4) RETURNING id`, fixture.email, fixture.username, strings.Repeat("x", 60), now).Scan(&fixture.id); err != nil {
		t.Fatalf("insert administrator fixture: %v", err)
	}
	return fixture
}

func createSetupChallenge(t *testing.T, db *store.Postgres, token string, now, expiresAt time.Time) {
	t.Helper()
	if err := db.CreateAdminSetupChallenge(context.Background(), domain.CreateAdminSetupChallengeParams{TokenHash: auth.HashSecret(token), Now: now, ExpiresAt: expiresAt}); err != nil {
		t.Fatal("create setup challenge")
	}
}

func setupParams(token, username, email string, now time.Time) domain.AdminSetupParams {
	return domain.AdminSetupParams{TokenHash: auth.HashSecret(token), Username: username, Email: email, PasswordHash: strings.Repeat("x", 60), Now: now}
}
