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
		{name: "rejects query port override", dsn: "postgres://cloud:" + secret + "@127.0.0.1:15432/cloud?port=5432", wantErr: true},
		{name: "rejects query fallback host override", dsn: "postgres://cloud:" + secret + "@127.0.0.1:15432/cloud?hostaddr=127.0.0.1", wantErr: true},
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
	tests := []struct {
		name        string
		databaseURL string
	}{
		{name: "host port", databaseURL: "postgres://cloud:top-secret@127.0.0.1:5432/cloud"},
		{name: "compose target without container opt in", databaseURL: "postgres://cloud:top-secret@postgres:5432/cloud"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Setenv("CLOUD_API_MIGRATE_COMPOSE_SERVICE", "")
			err := Run(context.Background(), Config{
				DatabaseURL: test.databaseURL,
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
	index := migrations[1].ConcurrentIndex
	if index.Name != "refresh_tokens_expiry_idx" || index.Table != "refresh_tokens" || index.Method != "btree" || index.Unique || strings.Join(index.Columns, ",") != "expires_at,id" {
		t.Fatalf("unexpected repository concurrent index definition: %#v", index)
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

func TestTransactionalSQLAcceptsOnlyOneOuterEnvelope(t *testing.T) {
	body, err := transactionalSQL("-- leading comment\nBEGIN TRANSACTION;\nCREATE TABLE events(id integer);\nCOMMIT;\n-- trailing comment\n")
	if err != nil {
		t.Fatalf("known transaction envelope was rejected: %v", err)
	}
	if strings.TrimSpace(body) != "CREATE TABLE events(id integer);" {
		t.Fatalf("transactionalSQL() body = %q, want only the enclosed DDL", body)
	}
	for _, sql := range []string{
		"CREATE TABLE events(id integer); COMMIT;",
		"CREATE TABLE events(id integer); END;",
		"CREATE TABLE events(id integer); ABORT;",
		"CREATE TABLE events(id integer); SAVEPOINT nested;",
		"CREATE TABLE events(id integer); RELEASE SAVEPOINT nested;",
		"CREATE TABLE events(id integer); PREPARE TRANSACTION 'prepared';",
		"CREATE TABLE events(id integer); SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;",
		"BEGIN; CREATE TABLE first_table(id integer); COMMIT; CREATE TABLE second_table(id integer); COMMIT;",
		"BEGIN; BEGIN; CREATE TABLE events(id integer); COMMIT; COMMIT;",
		"BEGIN; CREATE TABLE events(id integer); END; COMMIT;",
		"BEGIN; CREATE TABLE events(id integer); ABORT; COMMIT;",
	} {
		if _, err := transactionalSQL(sql); err == nil {
			t.Fatalf("transaction control was accepted: %q", sql)
		}
	}
	if _, err := transactionalSQL("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN RAISE NOTICE 'ok'; END; $$;"); err != nil {
		t.Fatalf("transaction words in a dollar-quoted function body were rejected: %v", err)
	}
}

func TestConcurrentIndexTargetParsesOnlyVerifiableDefinitions(t *testing.T) {
	definition, found, err := concurrentIndexTarget("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS events_active_idx ON app.events USING btree (user_id, created_at);")
	if err != nil || !found {
		t.Fatalf("concurrent index definition was not parsed: found=%t error=%v", found, err)
	}
	if definition.Name != "events_active_idx" || definition.Schema != "app" || definition.Table != "events" || definition.Method != "btree" || !definition.Unique || strings.Join(definition.Columns, ",") != "user_id,created_at" {
		t.Fatalf("unexpected concurrent index definition: %#v", definition)
	}
	for _, sql := range []string{
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events ((lower(email)));",
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events (email) WHERE deleted_at = now();",
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events (email) INCLUDE (id);",
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events USING hash (email);",
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events (email DESC);",
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events (email NULLS FIRST);",
		"CREATE INDEX CONCURRENTLY unsupported_idx ON events (email COLLATE \"C\");",
	} {
		if _, _, err := concurrentIndexTarget(sql); err == nil {
			t.Fatalf("unsupported concurrent definition was accepted: %q", sql)
		}
	}
}

func TestSameConcurrentIndexDefinitionFailsClosedOnUnexpectedCatalogState(t *testing.T) {
	expected := concurrentIndexDefinition{
		Schema: "app", Name: "events_email_id_idx", Table: "events", Method: "btree", Columns: []string{"email", "id"},
	}
	actual := expected
	actual.KeyCount = 1
	actual.IncludeCount = 1
	actual.DefaultBtreeOpclasses = true
	actual.DefaultCollations = true
	actual.DefaultSortOrder = true
	actual.NoPredicate = true
	actual.Valid = true
	if sameConcurrentIndexDefinition(expected, actual) {
		t.Fatal("sameConcurrentIndexDefinition() accepted an INCLUDE index with the same aggregate attributes")
	}

	actual = expected
	actual.KeyCount = 2
	actual.DefaultBtreeOpclasses = true
	actual.DefaultCollations = true
	actual.DefaultSortOrder = true
	actual.NoPredicate = true
	actual.Valid = true
	if !sameConcurrentIndexDefinition(expected, actual) {
		t.Fatal("sameConcurrentIndexDefinition() rejected the exact restricted btree definition")
	}
	for _, mutate := range []func(*concurrentIndexDefinition){
		func(definition *concurrentIndexDefinition) { definition.Valid = false },
		func(definition *concurrentIndexDefinition) { definition.DefaultBtreeOpclasses = false },
		func(definition *concurrentIndexDefinition) { definition.DefaultCollations = false },
		func(definition *concurrentIndexDefinition) { definition.DefaultSortOrder = false },
		func(definition *concurrentIndexDefinition) { definition.NoPredicate = false },
		func(definition *concurrentIndexDefinition) { definition.Columns[1] = "other_id" },
	} {
		mismatched := actual
		mismatched.Columns = append([]string(nil), actual.Columns...)
		mutate(&mismatched)
		if sameConcurrentIndexDefinition(expected, mismatched) {
			t.Fatal("sameConcurrentIndexDefinition() accepted an unknown or mismatched catalog state")
		}
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
	path := filepath.Join(directory, "000001_init.up.sql")
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("read repository concurrent migration copy")
	}
	if err := os.WriteFile(path, append(contents, []byte("-- checksum changed\n")...), 0o600); err != nil {
		t.Fatal("change applied migration copy")
	}
	err = Run(ctx, Config{DatabaseURL: dsn, Directory: directory, Schema: schema})
	if err == nil || !strings.Contains(err.Error(), "migration checksum mismatch for version 000001") {
		t.Fatal("repository checksum mismatch did not fail through ledger validation")
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

func TestRunSerializesAndRecoversDedicatedPostgresMigrations(t *testing.T) {
	dsn := isolatedTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, schema := openMigrationTestSchema(t, ctx, dsn)
	directory := writeMigrations(t, map[string]string{
		"000001_lock.up.sql": "CREATE TABLE events(id integer);\n",
	})
	config := Config{DatabaseURL: dsn, Directory: directory, Schema: schema}

	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", migrationAdvisoryLock); err != nil {
		t.Fatal("hold migration advisory lock")
	}
	done := make(chan error, 1)
	go func() { done <- Run(ctx, config) }()
	waitForMigrationLockWaiter(t, ctx, conn)
	if _, err := conn.Exec(ctx, "SELECT pg_advisory_unlock($1)", migrationAdvisoryLock); err != nil {
		t.Fatal("release migration advisory lock")
	}
	if err := <-done; err != nil {
		t.Fatalf("runner failed after advisory lock release: %v", err)
	}
	if err := Run(ctx, config); err != nil {
		t.Fatalf("second runner was not a no-op after lock contention: %v", err)
	}
}

func waitForMigrationLockWaiter(t *testing.T, ctx context.Context, conn *pgx.Conn) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiters int
		err := conn.QueryRow(ctx, `
SELECT count(*)
FROM pg_catalog.pg_locks
WHERE locktype = 'advisory'
  AND classid::bigint = $1 / 4294967296
  AND objid::bigint = $1 % 4294967296
  AND granted = false`, migrationAdvisoryLock).Scan(&waiters)
		if err != nil {
			t.Fatal("observe migration advisory lock waiter")
		}
		if waiters > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("runner never reached migration advisory lock wait")
}

func TestRunRejectsExistingSchemaWithEmptyLedger(t *testing.T) {
	dsn := isolatedTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, schema := openMigrationTestSchema(t, ctx, dsn)
	if _, err := conn.Exec(ctx, "CREATE TABLE "+quoteIdentifier(schema)+".users(id integer)"); err != nil {
		t.Fatal("create legacy Cloud relation")
	}
	directory := writeMigrations(t, map[string]string{
		"000001_should_not_run.up.sql": "CREATE TABLE must_not_exist(id integer);\n",
	})
	err := Run(ctx, Config{DatabaseURL: dsn, Directory: directory, Schema: schema})
	if err == nil || !strings.Contains(err.Error(), "empty migration ledger") {
		t.Fatal("existing schema with empty ledger was accepted")
	}
	var exists bool
	if err := conn.QueryRow(ctx, "SELECT to_regclass($1) IS NOT NULL", schema+".must_not_exist").Scan(&exists); err != nil {
		t.Fatal("check replay was blocked")
	}
	if exists {
		t.Fatal("runner replayed migration with existing schema and empty ledger")
	}
}

func TestRunRejectsMismatchedOrInvalidConcurrentIndexBeforeLedgerAndRecovers(t *testing.T) {
	dsn := isolatedTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, schema := openMigrationTestSchema(t, ctx, dsn)
	if _, err := conn.Exec(ctx, "CREATE TABLE "+quoteIdentifier(schema)+".events(id integer PRIMARY KEY, email text)"); err != nil {
		t.Fatal("create concurrent index fixture table")
	}
	directory := writeMigrations(t, map[string]string{
		"000001_concurrent.up.sql": "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS events_email_id_idx ON events (email, id);\n",
	})
	config := Config{DatabaseURL: dsn, Directory: directory, Schema: schema}

	if _, err := conn.Exec(ctx, "CREATE UNIQUE INDEX "+quoteIdentifier(schema)+".events_email_id_idx ON "+quoteIdentifier(schema)+".events(email) INCLUDE (id)"); err != nil {
		t.Fatal("create same-attribute INCLUDE boundary mismatch index")
	}
	assertConcurrentMigrationNotLedgered(t, ctx, conn, config, schema)
	if _, err := conn.Exec(ctx, "DROP INDEX "+quoteIdentifier(schema)+".events_email_id_idx"); err != nil {
		t.Fatal("drop INCLUDE boundary mismatch index")
	}
	if _, err := conn.Exec(ctx, "INSERT INTO "+quoteIdentifier(schema)+".events(id,email) VALUES(1,'duplicate'),(2,'duplicate')"); err != nil {
		t.Fatal("seed duplicate values")
	}
	if _, err := conn.Exec(ctx, "CREATE UNIQUE INDEX CONCURRENTLY "+quoteIdentifier(schema)+".events_email_id_idx ON "+quoteIdentifier(schema)+".events(email)"); err == nil {
		t.Fatal("failed concurrent unique index fixture unexpectedly succeeded")
	}
	assertConcurrentMigrationNotLedgered(t, ctx, conn, config, schema)
	if _, err := conn.Exec(ctx, "DROP INDEX "+quoteIdentifier(schema)+".events_email_id_idx"); err != nil {
		t.Fatal("drop invalid same-name index")
	}
	if _, err := conn.Exec(ctx, "DELETE FROM "+quoteIdentifier(schema)+".events WHERE id=1"); err != nil {
		t.Fatal("remove duplicate fixture row")
	}
	if err := Run(ctx, config); err != nil {
		t.Fatalf("runner did not recover after concurrent index repair: %v", err)
	}
	var count int
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+quoteIdentifier(schema)+".schema_migrations").Scan(&count); err != nil || count != 1 {
		t.Fatal("recovered concurrent migration was not recorded exactly once")
	}
}

func assertConcurrentMigrationNotLedgered(t *testing.T, ctx context.Context, conn *pgx.Conn, config Config, schema string) {
	t.Helper()
	if err := Run(ctx, config); err == nil {
		t.Fatal("mismatched or invalid concurrent index was accepted")
	}
	var ledgerExists bool
	if err := conn.QueryRow(ctx, "SELECT to_regclass($1) IS NOT NULL", schema+".schema_migrations").Scan(&ledgerExists); err != nil {
		t.Fatal("check concurrent migration ledger")
	}
	if !ledgerExists {
		return
	}
	var count int
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+quoteIdentifier(schema)+".schema_migrations").Scan(&count); err != nil {
		t.Fatal("count concurrent migration ledger")
	}
	if count != 0 {
		t.Fatal("mismatched or invalid concurrent index was recorded in ledger")
	}
}

func openMigrationTestSchema(t *testing.T, ctx context.Context, dsn string) (*pgx.Conn, string) {
	t.Helper()
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
	return conn, schema
}

func writeMigrations(t *testing.T, files map[string]string) string {
	t.Helper()
	directory := t.TempDir()
	for name, contents := range files {
		if err := os.WriteFile(filepath.Join(directory, name), []byte(contents), 0o600); err != nil {
			t.Fatal("write isolated migration")
		}
	}
	return directory
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
