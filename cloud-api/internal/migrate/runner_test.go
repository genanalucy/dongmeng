package migrate

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func TestRepositoryMigrationsClassifyConcurrentIndexOutsideTransaction(t *testing.T) {
	migrations, err := discoverMigrations(filepath.Join("..", "..", "..", "migrations"))
	if err != nil {
		t.Fatalf("discover repository migrations: %v", err)
	}
	if len(migrations) != 3 {
		t.Fatalf("migration count = %d, want 3", len(migrations))
	}
	for index, version := range []string{"000001", "000002", "000003"} {
		if migrations[index].Version != version {
			t.Fatalf("migration[%d].Version = %q, want %q", index, migrations[index].Version, version)
		}
	}
	if migrations[0].OutsideTransaction || !migrations[1].OutsideTransaction || migrations[2].OutsideTransaction {
		t.Fatalf("transaction strategies = [%t %t %t], want [false true false]", migrations[0].OutsideTransaction, migrations[1].OutsideTransaction, migrations[2].OutsideTransaction)
	}
}

func TestRunRecordsChecksumsIsIdempotentAndFailsBeforeLaterMigration(t *testing.T) {
	dsn := isolatedTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal("connect isolated migration test database")
	}
	defer conn.Close(ctx)

	schema := "migration_test_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err := conn.Exec(ctx, "CREATE SCHEMA "+quoteIdentifier(schema)); err != nil {
		t.Fatal("create isolated migration test schema")
	}
	t.Cleanup(func() {
		_, _ = conn.Exec(context.Background(), "DROP SCHEMA "+quoteIdentifier(schema)+" CASCADE")
	})

	directory := testMigrationDirectory(t)
	config := Config{DatabaseURL: dsn, Directory: directory, Schema: schema}
	if err := Run(ctx, config); err != nil {
		t.Fatalf("first migration run: %v", err)
	}

	var version, checksum string
	var appliedAt time.Time
	if err := conn.QueryRow(ctx, "SELECT version, checksum, applied_at FROM "+quoteIdentifier(schema)+".schema_migrations ORDER BY version LIMIT 1").Scan(&version, &checksum, &appliedAt); err != nil {
		t.Fatal("read schema migration ledger")
	}
	if version != "000001" || len(checksum) != 64 || appliedAt.IsZero() {
		t.Fatalf("invalid first ledger entry: version=%q checksum length=%d applied=%t", version, len(checksum), !appliedAt.IsZero())
	}

	var eventCount int
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+quoteIdentifier(schema)+".migration_runner_events").Scan(&eventCount); err != nil {
		t.Fatal("count first-run events")
	}
	if eventCount != 1 {
		t.Fatalf("first run event count = %d, want 1", eventCount)
	}
	if err := Run(ctx, config); err != nil {
		t.Fatalf("repeat migration run: %v", err)
	}
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+quoteIdentifier(schema)+".migration_runner_events").Scan(&eventCount); err != nil {
		t.Fatal("count repeated-run events")
	}
	if eventCount != 1 {
		t.Fatalf("repeat run event count = %d, want 1", eventCount)
	}

	if err := os.WriteFile(filepath.Join(directory, "000004_later.up.sql"), []byte("INSERT INTO migration_runner_events(value) VALUES ('later');\n"), 0o600); err != nil {
		t.Fatal("write later migration")
	}
	if err := os.WriteFile(filepath.Join(directory, "000002_concurrent_index.up.sql"), []byte("CREATE INDEX CONCURRENTLY migration_runner_events_value_idx ON migration_runner_events(value);\n-- changed\n"), 0o600); err != nil {
		t.Fatal("change applied migration")
	}
	if err := Run(ctx, config); err == nil {
		t.Fatal("checksum mismatch was accepted")
	}
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+quoteIdentifier(schema)+".migration_runner_events").Scan(&eventCount); err != nil {
		t.Fatal("count checksum-failure events")
	}
	if eventCount != 1 {
		t.Fatalf("later migration ran after checksum mismatch: event count = %d", eventCount)
	}
}

func testMigrationDirectory(t *testing.T) string {
	t.Helper()
	directory := t.TempDir()
	files := map[string]string{
		"000001_create_events.up.sql":    "BEGIN;\nCREATE TABLE migration_runner_events (value text NOT NULL);\nCOMMIT;\n",
		"000002_concurrent_index.up.sql": "CREATE INDEX CONCURRENTLY migration_runner_events_value_idx ON migration_runner_events(value);\n",
		"000003_insert_event.up.sql":     "BEGIN;\nINSERT INTO migration_runner_events(value) VALUES ('first');\nCOMMIT;\n",
		"000003_insert_event.down.sql":   "DROP TABLE migration_runner_events;\n",
	}
	for name, contents := range files {
		if err := os.WriteFile(filepath.Join(directory, name), []byte(contents), 0o600); err != nil {
			t.Fatalf("write test migration %s: %v", name, err)
		}
	}
	return directory
}

func isolatedTestDSN(t *testing.T) string {
	t.Helper()
	dsn := os.Getenv("CLOUD_API_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not set; skipping isolated PostgreSQL migration test")
	}
	config, err := pgx.ParseConfig(dsn)
	if err != nil {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is invalid; skipping PostgreSQL migration test")
	}
	if config.Host != "127.0.0.1" || config.Port != 15432 {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not an isolated 127.0.0.1:15432 target; skipping PostgreSQL migration test")
	}
	if _, port, err := net.SplitHostPort(fmt.Sprintf("%s:%d", config.Host, config.Port)); err != nil || port != "15432" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL does not use port 15432; skipping PostgreSQL migration test")
	}
	if config.Database == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL has no database name; skipping PostgreSQL migration test")
	}
	return dsn
}

func TestRunRequiresAnExplicitDatabaseURL(t *testing.T) {
	if err := Run(context.Background(), Config{Directory: t.TempDir()}); err == nil {
		t.Fatal("migration run without explicit database URL was accepted")
	}
}

func TestCreatesIndexConcurrentlyIgnoresCommentsAndStringLiterals(t *testing.T) {
	if createsIndexConcurrently("-- CREATE INDEX CONCURRENTLY ignored\nSELECT 'CREATE INDEX CONCURRENTLY ignored';") {
		t.Fatal("comment or string literal was treated as a concurrent index")
	}
	if !createsIndexConcurrently("CREATE UNIQUE INDEX CONCURRENTLY actual_idx ON events(value);") {
		t.Fatal("concurrent unique index was not detected")
	}
}
