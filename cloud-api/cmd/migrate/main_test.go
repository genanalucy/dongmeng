package main

import (
	"os"
	"testing"
)

func TestMigrationConfigRequiresExplicitUpAndDSN(t *testing.T) {
	t.Setenv("CLOUD_API_MIGRATE_DATABASE_URL", "")
	if _, err := migrationConfig([]string{"up"}); err == nil {
		t.Fatal("missing explicit migration DSN was accepted")
	}
	if _, err := migrationConfig([]string{"down", "-database-url", "postgres://example"}); err == nil {
		t.Fatal("down migration command was accepted")
	}
	if _, err := migrationConfig([]string{"-database-url", "postgres://example"}); err == nil {
		t.Fatal("implicit migration command was accepted")
	}
}

func TestMigrationConfigAcceptsExactHostEnvironmentDSNWithoutExposingValue(t *testing.T) {
	const dsn = "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable"
	if err := os.Setenv("CLOUD_API_MIGRATE_DATABASE_URL", dsn); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Unsetenv("CLOUD_API_MIGRATE_DATABASE_URL") })
	config, err := migrationConfig([]string{"up"})
	if err != nil {
		t.Fatalf("environment DSN rejected: %v", err)
	}
	if config.DatabaseURL != dsn {
		t.Fatal("environment DSN was not passed to runner configuration")
	}
}

func TestMigrationConfigPreservesComposeDSNForRunnerPolicy(t *testing.T) {
	const dsn = "postgres://cloud:top-secret@postgres:5432/cloud?sslmode=disable"
	config, err := migrationConfig([]string{"up", "--database-url", dsn})
	if err != nil {
		t.Fatalf("Compose DSN parsing was rejected before runner policy: %v", err)
	}
	if config.DatabaseURL != dsn {
		t.Fatal("Compose DSN was not passed to runner policy")
	}
}
