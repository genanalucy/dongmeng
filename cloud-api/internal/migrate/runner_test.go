package migrate

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func TestValidateDatabaseURLFailsClosed(t *testing.T) {
	const secret = "top-secret"
	tests := []struct {
		name         string
		dsn          string
		allowCompose bool
		wantErr      bool
	}{
		{name: "allows exact host target", dsn: "postgres://cloud:" + secret + "@127.0.0.1:15432/cloud?sslmode=disable"},
		{name: "allows exact compose service target only when opted in", dsn: "postgres://cloud:" + secret + "@postgres:5432/cloud?sslmode=disable", allowCompose: true},
		{name: "rejects compose service target without opt in", dsn: "postgres://cloud:" + secret + "@postgres:5432/cloud", wantErr: true},
		{name: "rejects host postgres port", dsn: "postgres://cloud:" + secret + "@127.0.0.1:5432/cloud", wantErr: true},
		{name: "rejects localhost alias", dsn: "postgres://cloud:" + secret + "@localhost:15432/cloud", wantErr: true},
		{name: "rejects IPv6 loopback", dsn: "postgres://cloud:" + secret + "@[::1]:15432/cloud", wantErr: true},
		{name: "rejects missing port", dsn: "postgres://cloud:" + secret + "@127.0.0.1/cloud", wantErr: true},
		{name: "rejects socket style keyword connection string", dsn: "host=/var/run/postgresql dbname=cloud password=" + secret, wantErr: true},
		{name: "rejects multi host", dsn: "postgres://cloud:" + secret + "@127.0.0.1:15432,postgres:5432/cloud", wantErr: true},
		{name: "rejects query host override", dsn: "postgres://cloud:" + secret + "@127.0.0.1:15432/cloud?host=postgres", wantErr: true},
		{name: "rejects empty database", dsn: "postgres://cloud:" + secret + "@127.0.0.1:15432/", wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := ValidateDatabaseURL(test.dsn, test.allowCompose)
			if (err != nil) != test.wantErr {
				t.Fatalf("ValidateDatabaseURL() error = %v, wantErr %t", err, test.wantErr)
			}
			if err != nil && strings.Contains(err.Error(), secret) {
				t.Fatal("database target validation error exposed the DSN secret")
			}
		})
	}
}

func TestRunRejectsUnsafeTargetBeforeReadingMigrationsOrConnecting(t *testing.T) {
	for _, databaseURL := range []string{
		"postgres://cloud:top-secret@127.0.0.1:5432/cloud",
		"postgres://cloud:top-secret@postgres:5432/cloud",
	} {
		t.Run(databaseURL, func(t *testing.T) {
			t.Setenv("CLOUD_API_MIGRATE_COMPOSE_SERVICE", "")
			err := Run(context.Background(), Config{
				DatabaseURL: databaseURL,
				Directory:   filepath.Join(t.TempDir(), "does-not-exist"),
			})
			if !errors.Is(err, ErrUnsafeDatabaseTarget) {
				t.Fatalf("Run() error = %v, want unsafe target error", err)
			}
			if strings.Contains(err.Error(), "top-secret") {
				t.Fatal("unsafe target error exposed the DSN secret")
			}
		})
	}
}

func TestRepositoryMigrationsClassifyConcurrentIndexOutsideTransaction(t *testing.T) {
	migrations, err := discoverMigrations(repositoryMigrationDirectory(t))
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
	if migrations[1].ConcurrentIndex != "refresh_tokens_expiry_idx" {
		t.Fatalf("concurrent index = %q, want refresh_tokens_expiry_idx", migrations[1].ConcurrentIndex)
	}
}

func TestDiscoverMigrationsRejectsUnsafeConcurrentMigrationShapes(t *testing.T) {
	tests := []string{
		"CREATE INDEX CONCURRENTLY idx ON events(value); INSERT INTO events VALUES ('unsafe');",
		"CREATE INDEX CONCURRENTLY IF NOT EXISTS idx ON events(value)",
		"CREATE INDEX CONCURRENTLY \"quoted_idx\" ON events(value);",
	}
	for index, sql := range tests {
		t.Run(string(rune('a'+index)), func(t *testing.T) {
			directory := t.TempDir()
			if err := os.WriteFile(filepath.Join(directory, "000001_invalid.up.sql"), []byte(sql), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := discoverMigrations(directory); err == nil {
				t.Fatal("unsafe concurrent migration structure was accepted")
			}
		})
	}
}

func TestTransactionalSQLRejectsUnknownTransactionControl(t *testing.T) {
	if _, err := transactionalSQL("-- leading comment\nBEGIN TRANSACTION;\nCREATE TABLE events(id integer);\nCOMMIT;\n-- trailing comment\n"); err != nil {
		t.Fatalf("known transaction envelope was rejected: %v", err)
	}
	if _, err := transactionalSQL("CREATE TABLE events(id integer); COMMIT;"); err == nil {
		t.Fatal("unwrapped transaction control was accepted")
	}
	if _, err := transactionalSQL("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN RAISE NOTICE 'ok'; END; $$;"); err != nil {
		t.Fatalf("transaction words in a dollar-quoted function body were rejected: %v", err)
	}
}

func TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration(t *testing.T) {
	dsn := isolatedTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal("connect isolated migration test database")
	}
	t.Cleanup(func() { _ = conn.Close(context.Background()) })

	schema := "migration_test_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err := conn.Exec(ctx, "CREATE SCHEMA "+quoteIdentifier(schema)); err != nil {
		t.Fatal("create isolated migration test schema")
	}
	t.Cleanup(func() {
		if _, err := conn.Exec(context.Background(), "DROP SCHEMA "+quoteIdentifier(schema)+" CASCADE"); err != nil {
			t.Error("drop isolated migration test schema")
		}
	})

	config := Config{DatabaseURL: dsn, Directory: repositoryMigrationDirectory(t), Schema: schema}
	if err := Run(ctx, config); err != nil {
		t.Fatalf("first repository migration run: %v", err)
	}

	ledger := quoteIdentifier(schema) + ".schema_migrations"
	var count int
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+ledger).Scan(&count); err != nil {
		t.Fatal("count repository migration ledger")
	}
	if count != 3 {
		t.Fatalf("ledger count = %d, want 3", count)
	}
	var valid bool
	if err := conn.QueryRow(ctx, `SELECT i.indisvalid FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=$1 AND c.relname='refresh_tokens_expiry_idx'`, schema).Scan(&valid); err != nil {
		t.Fatal("read concurrent index validity")
	}
	if !valid {
		t.Fatal("repository concurrent index is invalid")
	}
	var before time.Time
	if err := conn.QueryRow(ctx, "SELECT max(applied_at) FROM "+ledger).Scan(&before); err != nil {
		t.Fatal("read first migration timestamp")
	}
	if err := Run(ctx, config); err != nil {
		t.Fatalf("repeat repository migration run: %v", err)
	}
	var after time.Time
	if err := conn.QueryRow(ctx, "SELECT max(applied_at) FROM "+ledger).Scan(&after); err != nil {
		t.Fatal("read repeated migration timestamp")
	}
	if !after.Equal(before) {
		t.Fatal("repeat repository migration run rewrote the ledger")
	}

	directory := copyRepositoryMigrations(t)
	if err := os.WriteFile(filepath.Join(directory, "000004_later.up.sql"), []byte("CREATE TABLE must_not_exist(id integer);\n"), 0o600); err != nil {
		t.Fatal("write later migration")
	}
	path := filepath.Join(directory, "000002_refresh_tokens_expiry_idx.up.sql")
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("read repository concurrent migration copy")
	}
	if err := os.WriteFile(path, append(contents, []byte("-- checksum changed\n")...), 0o600); err != nil {
		t.Fatal("change applied migration copy")
	}
	if err := Run(ctx, Config{DatabaseURL: dsn, Directory: directory, Schema: schema}); err == nil {
		t.Fatal("repository checksum mismatch was accepted")
	}
	var laterExists bool
	if err := conn.QueryRow(ctx, "SELECT to_regclass($1) IS NOT NULL", schema+".must_not_exist").Scan(&laterExists); err != nil {
		t.Fatal("check later migration table")
	}
	if laterExists {
		t.Fatal("later migration ran after checksum mismatch")
	}
}

func repositoryMigrationDirectory(t *testing.T) string {
	t.Helper()
	return filepath.Join("..", "..", "..", "migrations")
}

func copyRepositoryMigrations(t *testing.T) string {
	t.Helper()
	directory := t.TempDir()
	for _, name := range []string{
		"000001_init.up.sql",
		"000002_refresh_tokens_expiry_idx.up.sql",
		"000003_business_lifecycle.up.sql",
	} {
		contents, err := os.ReadFile(filepath.Join(repositoryMigrationDirectory(t), name))
		if err != nil {
			t.Fatalf("read repository migration %s: %v", name, err)
		}
		if err := os.WriteFile(filepath.Join(directory, name), contents, 0o600); err != nil {
			t.Fatalf("copy repository migration %s: %v", name, err)
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
	if err := ValidateDatabaseURL(dsn, false); err != nil {
		t.Fatal("CLOUD_API_TEST_DATABASE_URL must target the isolated 127.0.0.1:15432 test database")
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
