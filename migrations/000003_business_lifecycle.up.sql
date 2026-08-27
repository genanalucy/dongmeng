BEGIN;

ALTER TABLE users ADD COLUMN IF NOT EXISTS disabled_at timestamptz;
ALTER TABLE entitlements ADD COLUMN IF NOT EXISTS revoked_at timestamptz;
ALTER TABLE translation_sessions ADD COLUMN IF NOT EXISTS install_id text;
ALTER TABLE translation_sessions ADD COLUMN IF NOT EXISTS ended_at timestamptz;
ALTER TABLE translation_sessions ADD COLUMN IF NOT EXISTS revoked_at timestamptz;

UPDATE translation_sessions SET install_id = 'legacy' WHERE install_id IS NULL;
ALTER TABLE translation_sessions ALTER COLUMN install_id SET NOT NULL;
ALTER TABLE translation_sessions ADD CONSTRAINT translation_sessions_install_id_valid
    CHECK (length(btrim(install_id)) BETWEEN 1 AND 128);
ALTER TABLE entitlements ADD CONSTRAINT entitlements_revocation_valid
    CHECK (revoked_at IS NULL OR revoked_at >= created_at);
ALTER TABLE code_batches ADD CONSTRAINT code_batches_annual_duration
    CHECK (duration_days = 365) NOT VALID;
ALTER TABLE translation_sessions ADD CONSTRAINT translation_sessions_terminal_valid
    CHECK (ended_at IS NULL OR ended_at >= created_at) NOT VALID;
ALTER TABLE translation_sessions ADD CONSTRAINT translation_sessions_revocation_valid
    CHECK (revoked_at IS NULL OR revoked_at >= created_at) NOT VALID;

CREATE TABLE IF NOT EXISTS user_devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    install_id text NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_devices_install_id_valid CHECK (length(btrim(install_id)) BETWEEN 1 AND 128),
    CONSTRAINT user_devices_user_install_unique UNIQUE (user_id, install_id)
);
CREATE INDEX IF NOT EXISTS user_devices_user_seen_idx ON user_devices (user_id, last_seen_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS users_active_email_idx ON users (email) WHERE disabled_at IS NULL;
CREATE INDEX IF NOT EXISTS entitlements_active_unrevoked_idx ON entitlements (user_id, starts_at, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS translation_sessions_active_user_idx
    ON translation_sessions (user_id, expires_at) WHERE ended_at IS NULL AND revoked_at IS NULL;

COMMIT;
