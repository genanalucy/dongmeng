BEGIN;
DROP TABLE IF EXISTS history_operations;
DROP TABLE IF EXISTS history_changes;
ALTER TABLE history_sessions
    DROP CONSTRAINT IF EXISTS history_sessions_title_shape,
    DROP COLUMN IF EXISTS title_updated_at,
    DROP COLUMN IF EXISTS title_ciphertext,
    DROP COLUMN IF EXISTS title_nonce,
    DROP COLUMN IF EXISTS title_key_version;
COMMIT;
