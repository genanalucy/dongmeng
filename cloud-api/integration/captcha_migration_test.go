//go:build integration

package integration_test

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TestCaptchaMigrationDownIsIdempotentIntegration exercises the 000008
// rollback against real PostgreSQL in three states the repository cares
// about: an empty schema (partial rollback), a fully applied up migration,
// and a repeated down after rollback. The down migration must stay a no-op
// success instead of failing the whole rollback batch.
func TestCaptchaMigrationDownIsIdempotentIntegration(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated test database")
	}
	t.Cleanup(pool.Close)

	// An isolated schema keeps this test independent of the shared public
	// schema used by the lifecycle test; every statement below runs on one
	// acquired connection with that schema first on the search path.
	schema := "captcha_down_test_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err := pool.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		t.Fatalf("create isolated schema: %v", err)
	}
	t.Cleanup(func() {
		dropContext, dropCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer dropCancel()
		_, _ = pool.Exec(dropContext, `DROP SCHEMA IF EXISTS `+schema+` CASCADE`)
	})
	conn, err := pool.Acquire(ctx)
	if err != nil {
		t.Fatal("acquire isolated connection")
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `SET search_path TO `+schema); err != nil {
		t.Fatalf("set search path: %v", err)
	}

	up, err := os.ReadFile(filepath.Join(repositoryMigrationDirectory(t), "000008_registration_captchas.up.sql"))
	if err != nil {
		t.Fatalf("read captcha up migration: %v", err)
	}
	down, err := os.ReadFile(filepath.Join(repositoryMigrationDirectory(t), "000008_registration_captchas.down.sql"))
	if err != nil {
		t.Fatalf("read captcha down migration: %v", err)
	}

	apply := func(name, sql string) {
		t.Helper()
		if _, err := conn.Exec(ctx, sql); err != nil {
			t.Fatalf("apply %s: %v", name, err)
		}
	}
	tablePresent := func(table string) bool {
		t.Helper()
		var present bool
		if err := conn.QueryRow(ctx, `SELECT to_regclass($1) IS NOT NULL`, table).Scan(&present); err != nil {
			t.Fatalf("inspect %s: %v", table, err)
		}
		return present
	}

	// Partial state: down on an empty schema must succeed.
	apply("down on empty schema", string(down))
	if tablePresent("captcha_rate_limits") || tablePresent("registration_captchas") {
		t.Fatal("empty schema unexpectedly contains captcha tables")
	}

	apply("up", string(up))
	if !tablePresent("captcha_rate_limits") || !tablePresent("registration_captchas") {
		t.Fatal("applied migration did not create the captcha tables")
	}

	apply("down after up", string(down))
	if tablePresent("captcha_rate_limits") || tablePresent("registration_captchas") {
		t.Fatal("rollback left captcha tables behind")
	}

	// Idempotent repeat of the same rollback.
	apply("down repeated", string(down))
	if tablePresent("captcha_rate_limits") || tablePresent("registration_captchas") {
		t.Fatal("repeated rollback left captcha tables behind")
	}
}
