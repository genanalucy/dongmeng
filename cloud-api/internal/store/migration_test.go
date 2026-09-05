package store

import (
	"os"
	"strings"
	"testing"
)

func TestInitialMigrationContainsExpiryAndOwnershipGuards(t *testing.T) {
	contents, err := os.ReadFile("../../../migrations/000001_init.up.sql")
	if err != nil {
		t.Fatalf("read initial migration: %v", err)
	}
	schema := string(contents)
	required := []string{
		"code_hash bytea NOT NULL",
		"CHECK (octet_length(code_hash) = 32)",
		"CONSTRAINT redemption_codes_hash_unique UNIQUE (code_hash)",
		"FOREIGN KEY (replaced_by_id, family_id, user_id) REFERENCES refresh_tokens(id, family_id, user_id)",
		"FOREIGN KEY (entitlement_id, user_id) REFERENCES entitlements(id, user_id)",
		"FOREIGN KEY (session_id, user_id) REFERENCES translation_sessions(id, user_id)",
		"FOREIGN KEY (consent_id, user_id, consent_granted) REFERENCES feedback_consents(id, user_id, granted)",
		"CONSTRAINT feedback_artifacts_retention_max CHECK (expires_at <= created_at + interval '14 days')",
		"CREATE INDEX feedback_artifacts_expiry_idx ON feedback_artifacts (expires_at, id)",
		"CREATE INDEX translation_sessions_expiry_idx ON translation_sessions (expires_at, id)",
	}
	for _, fragment := range required {
		if !strings.Contains(schema, fragment) {
			t.Errorf("initial migration missing %q", fragment)
		}
	}
	if strings.Contains(schema, "code text") || strings.Contains(schema, "redemption_code text") {
		t.Error("initial migration must not store plaintext redemption codes")
	}
	if count := strings.Count(schema, " timestamp "); count != 0 {
		t.Errorf("initial migration contains %d timezone-naive timestamp columns", count)
	}
	if count := strings.Count(schema, "timestamptz"); count < 17 {
		t.Errorf("initial migration has only %d timestamptz columns", count)
	}
}

func TestRefreshExpiryIndexUsesIncrementalNonBlockingMigration(t *testing.T) {
	up, err := os.ReadFile("../../../migrations/000002_refresh_tokens_expiry_idx.up.sql")
	if err != nil {
		t.Fatalf("read refresh expiry migration: %v", err)
	}
	for _, fragment := range []string{
		"CREATE INDEX CONCURRENTLY IF NOT EXISTS refresh_tokens_expiry_idx",
		"ON refresh_tokens (expires_at, id)",
	} {
		if !strings.Contains(string(up), fragment) {
			t.Errorf("refresh expiry migration missing %q", fragment)
		}
	}
	if strings.Contains(strings.ToUpper(string(up)), "BEGIN") {
		t.Error("concurrent index migration must not run inside a transaction block")
	}

	down, err := os.ReadFile("../../../migrations/000002_refresh_tokens_expiry_idx.down.sql")
	if err != nil {
		t.Fatalf("read refresh expiry rollback: %v", err)
	}
	if !strings.Contains(string(down), "DROP INDEX CONCURRENTLY IF EXISTS refresh_tokens_expiry_idx") {
		t.Error("refresh expiry rollback must be concurrent and idempotent")
	}
}

func TestTwoDeviceGovernanceMigrationPersistsTerminationReasons(t *testing.T) {
	up, err := os.ReadFile("../../../migrations/000009_two_device_session_governance.up.sql")
	if err != nil {
		t.Fatalf("read two-device governance migration: %v", err)
	}
	schema := string(up)
	for _, fragment := range []string{
		"ALTER TABLE translation_sessions ADD COLUMN IF NOT EXISTS termination_reason text",
		"CHECK (termination_reason IS NULL OR termination_reason IN",
		"'replaced_by_device'",
		"CHECK (termination_reason IS NULL OR ended_at IS NOT NULL OR revoked_at IS NOT NULL)",
		"CREATE INDEX IF NOT EXISTS translation_sessions_active_user_created_idx",
		"ON translation_sessions (user_id, created_at, id)",
		"WHERE ended_at IS NULL AND revoked_at IS NULL",
		// The reason column is new, so existing rows are all NULL and both
		// constraints must reach a validated state in the same migration
		// instead of staying permanently NOT VALID.
		"VALIDATE CONSTRAINT translation_sessions_termination_reason_valid",
		"VALIDATE CONSTRAINT translation_sessions_termination_requires_terminal",
	} {
		if !strings.Contains(schema, fragment) {
			t.Errorf("two-device governance migration missing %q", fragment)
		}
	}

	down, err := os.ReadFile("../../../migrations/000009_two_device_session_governance.down.sql")
	if err != nil {
		t.Fatalf("read two-device governance rollback: %v", err)
	}
	for _, fragment := range []string{
		"DROP INDEX IF EXISTS translation_sessions_active_user_created_idx",
		"DROP COLUMN IF EXISTS termination_reason",
	} {
		if !strings.Contains(string(down), fragment) {
			t.Errorf("two-device governance rollback missing %q", fragment)
		}
	}
}
