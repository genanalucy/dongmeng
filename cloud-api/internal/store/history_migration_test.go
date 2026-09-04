package store

import (
	"os"
	"strings"
	"testing"
)

// TestEncryptedHistoryMigrationStoresOnlyCiphertext pins the 000007 schema
// contract: tombstone-capable owner-scoped tables, composite foreign keys, and
// no plaintext columns.
func TestEncryptedHistoryMigrationStoresOnlyCiphertext(t *testing.T) {
	contents, err := os.ReadFile("../../../migrations/000007_encrypted_history.up.sql")
	if err != nil {
		t.Fatalf("read encrypted history migration: %v", err)
	}
	schema := string(contents)
	required := []string{
		"user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE",
		"CONSTRAINT history_sessions_id_user_unique UNIQUE (id, user_id)",
		"CONSTRAINT history_turns_session_owner_fk FOREIGN KEY (session_id, user_id) REFERENCES history_sessions(id, user_id) ON DELETE CASCADE",
		"CONSTRAINT history_turns_key_version_positive CHECK (key_version >= 1)",
		"octet_length(nonce) = 12",
		"octet_length(ciphertext) BETWEEN 16 AND 262144",
		"deleted_at timestamptz",
		"CREATE INDEX history_sessions_user_live_idx",
		"WHERE deleted_at IS NULL",
		"CREATE INDEX history_turns_session_created_idx",
		"CREATE INDEX history_turns_user_live_idx",
	}
	for _, fragment := range required {
		if !strings.Contains(schema, fragment) {
			t.Errorf("encrypted history migration missing %q", fragment)
		}
	}
	for _, forbidden := range []string{"title text", "content text", "body text", "summary text", "text NOT NULL"} {
		if strings.Contains(schema, forbidden) {
			t.Errorf("encrypted history migration must not define plaintext storage: %q", forbidden)
		}
	}
	if count := strings.Count(schema, " timestamp "); count != 0 {
		t.Errorf("encrypted history migration contains %d timezone-naive timestamp columns", count)
	}
}

// TestEncryptedHistoryDownMigrationDropsBothTablesInOrder ensures the paired
// down migration reverses the schema without touching unrelated tables.
func TestEncryptedHistoryDownMigrationDropsBothTablesInOrder(t *testing.T) {
	contents, err := os.ReadFile("../../../migrations/000007_encrypted_history.down.sql")
	if err != nil {
		t.Fatalf("read encrypted history down migration: %v", err)
	}
	schema := string(contents)
	turns := strings.Index(schema, "DROP TABLE IF EXISTS history_turns;")
	sessions := strings.Index(schema, "DROP TABLE IF EXISTS history_sessions;")
	if turns == -1 || sessions == -1 || turns > sessions {
		t.Fatalf("down migration must drop history_turns before history_sessions:\n%s", schema)
	}
	for _, forbidden := range []string{"DROP TABLE users", "DROP TABLE translation_sessions", "DELETE FROM", "UPDATE "} {
		if strings.Contains(schema, forbidden) {
			t.Errorf("down migration must not touch unrelated data: %q", forbidden)
		}
	}
}
