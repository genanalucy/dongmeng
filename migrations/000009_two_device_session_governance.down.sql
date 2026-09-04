BEGIN;

DROP INDEX IF EXISTS translation_sessions_active_user_created_idx;
ALTER TABLE translation_sessions DROP CONSTRAINT IF EXISTS translation_sessions_termination_requires_terminal;
ALTER TABLE translation_sessions DROP CONSTRAINT IF EXISTS translation_sessions_termination_reason_valid;
ALTER TABLE translation_sessions DROP COLUMN IF EXISTS termination_reason;

COMMIT;
