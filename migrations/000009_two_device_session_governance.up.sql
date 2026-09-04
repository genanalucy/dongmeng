BEGIN;

-- Two-device translation session governance. A session that stops being
-- usable before its natural expiry now records why, so Agent clients can
-- explain the replacement to the displaced device. Reasons are only written
-- together with a terminal timestamp; natural expiry stays temporal and is
-- resolved at read time.
ALTER TABLE translation_sessions ADD COLUMN IF NOT EXISTS termination_reason text;
ALTER TABLE translation_sessions ADD CONSTRAINT translation_sessions_termination_reason_valid
    CHECK (termination_reason IS NULL OR termination_reason IN
        ('ended', 'revoked', 'replaced_by_device', 'entitlement_revoked', 'user_disabled')) NOT VALID;
ALTER TABLE translation_sessions ADD CONSTRAINT translation_sessions_termination_requires_terminal
    CHECK (termination_reason IS NULL OR ended_at IS NOT NULL OR revoked_at IS NOT NULL) NOT VALID;

-- Replacement arbitration and the per-user active count both scan a user's
-- active sessions ordered by the stable (created_at, id) total order.
CREATE INDEX IF NOT EXISTS translation_sessions_active_user_created_idx
    ON translation_sessions (user_id, created_at, id)
    WHERE ended_at IS NULL AND revoked_at IS NULL;

COMMIT;
