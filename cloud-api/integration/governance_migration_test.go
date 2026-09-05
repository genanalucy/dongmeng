//go:build integration

package integration_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TestTwoDeviceGovernanceMigrationValidatesConstraints proves against real
// PostgreSQL that migration 000009 leaves its termination-reason CHECK
// constraints in a validated catalog state, that they actually enforce the
// reason vocabulary for new writes, and that up/down/up preserves session
// rows. The termination_reason column is new in the same migration, so every
// pre-existing row is NULL and validation must succeed without a backfill.
func TestTwoDeviceGovernanceMigrationValidatesConstraints(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	schema := "gov_migration_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: schema}); err != nil {
		t.Fatalf("apply migration history into isolated schema: %v", err)
	}
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated migration test database")
	}
	t.Cleanup(pool.Close)
	t.Cleanup(func() {
		dropContext, dropCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer dropCancel()
		_, _ = pool.Exec(dropContext, `DROP SCHEMA IF EXISTS `+schema+` CASCADE`)
	})
	conn, err := pool.Acquire(ctx)
	if err != nil {
		t.Fatal("acquire isolated migration connection")
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `SET search_path TO `+schema); err != nil {
		t.Fatalf("set search path: %v", err)
	}

	up, err := os.ReadFile(filepath.Join(repositoryMigrationDirectory(t), "000009_two_device_session_governance.up.sql"))
	if err != nil {
		t.Fatalf("read two-device governance up migration: %v", err)
	}
	down, err := os.ReadFile(filepath.Join(repositoryMigrationDirectory(t), "000009_two_device_session_governance.down.sql"))
	if err != nil {
		t.Fatalf("read two-device governance down migration: %v", err)
	}

	constraintState := func(name string) (present, validated bool) {
		t.Helper()
		if err := conn.QueryRow(ctx, `SELECT convalidated FROM pg_constraint WHERE conrelid='translation_sessions'::regclass AND conname=$1`, name).Scan(&validated); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				return false, false
			}
			t.Fatalf("inspect constraint %s: %v", name, err)
		}
		return true, validated
	}
	for _, name := range []string{"translation_sessions_termination_reason_valid", "translation_sessions_termination_requires_terminal"} {
		if present, validated := constraintState(name); !present || !validated {
			t.Fatalf("constraint %s present=%v validated=%v, want a fully validated constraint", name, present, validated)
		}
	}

	// Seed one fixture user with an active, an explained-terminated, and a
	// legacy pre-migration terminated session (terminal timestamp, NULL reason).
	fixtureUser, fixtureEntitlement := uuid.New(), uuid.New()
	seedSession := func(legacy bool) {
		t.Helper()
		id, jti := uuid.New(), uuid.New()
		var err error
		if legacy {
			_, err = conn.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at,ended_at) VALUES($1,$2,$3,$4,$5,now()+interval '5 minutes',now(),now())`, id, fixtureUser, fixtureEntitlement, "gov-migration-install-legacy", jti)
		} else {
			_, err = conn.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at,ended_at,termination_reason) VALUES($1,$2,$3,$4,$5,now()+interval '5 minutes',now(),now(),'ended')`, id, fixtureUser, fixtureEntitlement, "gov-migration-install-ended", jti)
		}
		if err != nil {
			t.Fatalf("seed session (legacy=%v): %v", legacy, err)
		}
	}
	if _, err := conn.Exec(ctx, `INSERT INTO users(id,email,password_hash) VALUES($1,$2,$3)`, fixtureUser, "gov-migration-"+fixtureUser.String()+"@example.test", strings.Repeat("x", 60)); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if _, err := conn.Exec(ctx, `INSERT INTO entitlements(id,user_id,kind,starts_at,expires_at) VALUES($1,$2,'trial',now(),now()+interval '3 days')`, fixtureEntitlement, fixtureUser); err != nil {
		t.Fatalf("seed entitlement: %v", err)
	}
	if _, err := conn.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at) VALUES($1,$2,$3,$4,$5,now()+interval '5 minutes',now())`, uuid.New(), fixtureUser, fixtureEntitlement, "gov-migration-install-active", uuid.New()); err != nil {
		t.Fatalf("seed active session: %v", err)
	}
	seedSession(false)
	seedSession(true)

	// The validated constraints must still enforce the vocabulary on writes.
	if _, err := conn.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at,ended_at,termination_reason) VALUES($1,$2,$3,$4,$5,now()+interval '5 minutes',now(),now(),'free_text_reason')`, uuid.New(), fixtureUser, fixtureEntitlement, "gov-migration-install-bad", uuid.New()); err == nil {
		t.Fatal("out-of-vocabulary termination reason was accepted")
	}
	if _, err := conn.Exec(ctx, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at,termination_reason) VALUES($1,$2,$3,$4,$5,now()+interval '5 minutes',now(),'ended')`, uuid.New(), fixtureUser, fixtureEntitlement, "gov-migration-install-bad", uuid.New()); err == nil {
		t.Fatal("termination reason without a terminal timestamp was accepted")
	}

	// Down must drop the governance artifacts while preserving session rows.
	if _, err := conn.Exec(ctx, string(down)); err != nil {
		t.Fatalf("apply down migration: %v", err)
	}
	var remaining, terminated int
	if err := conn.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE ended_at IS NOT NULL) FROM translation_sessions WHERE user_id=$1`, fixtureUser).Scan(&remaining, &terminated); err != nil || remaining != 3 || terminated != 2 {
		t.Fatalf("down migration disturbed session rows: remaining=%d terminated=%d err=%v", remaining, terminated, err)
	}
	var reasonColumn int
	if err := conn.QueryRow(ctx, `SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='translation_sessions' AND column_name='termination_reason'`).Scan(&reasonColumn); err != nil || reasonColumn != 0 {
		t.Fatalf("down migration left termination_reason behind: %d err=%v", reasonColumn, err)
	}
	for _, name := range []string{"translation_sessions_termination_reason_valid", "translation_sessions_termination_requires_terminal"} {
		if present, _ := constraintState(name); present {
			t.Fatalf("down migration left constraint %s behind", name)
		}
	}

	// Up after down must restore the governance artifacts and re-validate the
	// constraints even with pre-existing terminal rows (their reason is NULL).
	if _, err := conn.Exec(ctx, string(up)); err != nil {
		t.Fatalf("re-apply up migration: %v", err)
	}
	for _, name := range []string{"translation_sessions_termination_reason_valid", "translation_sessions_termination_requires_terminal"} {
		if present, validated := constraintState(name); !present || !validated {
			t.Fatalf("re-applied constraint %s present=%v validated=%v, want fully validated", name, present, validated)
		}
	}
	if err := conn.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1`, fixtureUser).Scan(&remaining); err != nil || remaining != 3 {
		t.Fatalf("re-applied migration disturbed session rows: remaining=%d err=%v", remaining, err)
	}
}
