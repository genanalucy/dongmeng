BEGIN;

-- A setup token is generated offline. Only its SHA-256 digest is persisted.
CREATE TABLE admin_setup_challenges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash bytea NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT admin_setup_challenges_token_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT admin_setup_challenges_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT admin_setup_challenges_used_valid CHECK (used_at IS NULL OR used_at >= created_at)
);

-- Public availability checks and one-shot redemption only inspect unconsumed rows.
CREATE INDEX admin_setup_challenges_available_expiry_idx
    ON admin_setup_challenges (expires_at, id)
    WHERE used_at IS NULL;

-- The API runtime role may only inspect and consume an already-created
-- challenge. Challenge creation requires the separate offline setup role.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dngmeng_cloud_api') THEN
        REVOKE ALL PRIVILEGES ON TABLE admin_setup_challenges FROM dngmeng_cloud_api;
        GRANT SELECT, UPDATE ON TABLE admin_setup_challenges TO dngmeng_cloud_api;
    END IF;
END;
$$;

COMMIT;
