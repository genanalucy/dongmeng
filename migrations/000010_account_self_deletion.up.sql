BEGIN;

-- Self-service account deletion lifecycle. Deletion is a tombstone plus
-- anonymization, never a row removal: audit_logs (append-only, FK to
-- users(id, role)) and redeemed redemption_codes (redeemed_by ON DELETE
-- RESTRICT) must keep referencing the account, so the users row survives while
-- every login identity is replaced and the stored credential is invalidated.
-- The application-level deletion transaction additionally revokes all refresh
-- token families and entitlements, terminates active translation sessions with
-- the existing 'user_disabled' terminal reason, and clears/tombstones all
-- encrypted history bodies.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at timestamptz;

-- A deleted account is always disabled first (same transaction timestamp at
-- the latest), so every existing enabled-only lookup (login identities,
-- bearer principal check, session creation re-check) already excludes it.
ALTER TABLE users ADD CONSTRAINT users_deletion_valid
    CHECK (deleted_at IS NULL OR (deleted_at >= created_at AND disabled_at IS NOT NULL AND disabled_at <= deleted_at))
    NOT VALID;

-- The column is new, so every stored row satisfies the constraint already;
-- validating it now keeps the catalog fully trustworthy without blocking
-- concurrent reads or writes.
ALTER TABLE users VALIDATE CONSTRAINT users_deletion_valid;

COMMIT;
