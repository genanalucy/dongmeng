BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    password_hash text NOT NULL,
    role text NOT NULL DEFAULT 'user',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_email_normalized CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    CONSTRAINT users_password_hash_nonempty CHECK (length(password_hash) BETWEEN 20 AND 1024),
    CONSTRAINT users_role_valid CHECK (role IN ('user', 'admin')),
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_id_role_unique UNIQUE (id, role)
);

CREATE TABLE entitlements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL,
    starts_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT entitlements_kind_valid CHECK (kind IN ('trial', 'package')),
    CONSTRAINT entitlements_period_valid CHECK (expires_at > starts_at),
    CONSTRAINT entitlements_duration_valid CHECK (
        (kind = 'trial' AND expires_at = starts_at + interval '3 days') OR
        (kind = 'package' AND expires_at = starts_at + interval '365 days')
    ),
    CONSTRAINT entitlements_id_user_unique UNIQUE (id, user_id)
);

CREATE INDEX entitlements_active_by_user_idx
    ON entitlements (user_id, starts_at, expires_at);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id uuid NOT NULL,
    token_hash bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT refresh_tokens_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT refresh_tokens_revocation_valid CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT refresh_tokens_replacement_valid CHECK (replaced_by_id IS NULL OR (revoked_at IS NOT NULL AND replaced_by_id <> id)),
    CONSTRAINT refresh_tokens_id_family_user_unique UNIQUE (id, family_id, user_id),
    CONSTRAINT refresh_tokens_replaced_by_family_user_fk FOREIGN KEY (replaced_by_id, family_id, user_id) REFERENCES refresh_tokens(id, family_id, user_id)
);

CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id, created_at);
CREATE INDEX refresh_tokens_active_user_idx
    ON refresh_tokens (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE code_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL,
    duration_days integer NOT NULL DEFAULT 365,
    created_by uuid NOT NULL,
    created_by_role text NOT NULL DEFAULT 'admin',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT code_batches_name_valid CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT code_batches_duration_valid CHECK (duration_days BETWEEN 1 AND 3650),
    CONSTRAINT code_batches_admin_role CHECK (created_by_role = 'admin'),
    CONSTRAINT code_batches_admin_fk FOREIGN KEY (created_by, created_by_role) REFERENCES users(id, role)
);

CREATE INDEX code_batches_created_idx ON code_batches (created_at DESC, id DESC);

CREATE TABLE redemption_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL REFERENCES code_batches(id) ON DELETE RESTRICT,
    code_hash bytea NOT NULL,
    redeemed_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    redeemed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT redemption_codes_hash_length CHECK (octet_length(code_hash) = 32),
    CONSTRAINT redemption_codes_hash_unique UNIQUE (code_hash),
    CONSTRAINT redemption_codes_redemption_pair CHECK ((redeemed_by IS NULL) = (redeemed_at IS NULL)),
    CONSTRAINT redemption_codes_redemption_time CHECK (redeemed_at IS NULL OR redeemed_at >= created_at)
);

CREATE INDEX redemption_codes_batch_idx ON redemption_codes (batch_id, created_at);
CREATE INDEX redemption_codes_unredeemed_idx ON redemption_codes (batch_id, id) WHERE redeemed_at IS NULL;
CREATE INDEX redemption_codes_redeemed_by_idx ON redemption_codes (redeemed_by, redeemed_at DESC) WHERE redeemed_by IS NOT NULL;

CREATE TABLE translation_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entitlement_id uuid NOT NULL,
    jti uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT translation_sessions_jti_unique UNIQUE (jti),
    CONSTRAINT translation_sessions_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT translation_sessions_entitlement_owner_fk FOREIGN KEY (entitlement_id, user_id) REFERENCES entitlements(id, user_id),
    CONSTRAINT translation_sessions_id_user_unique UNIQUE (id, user_id)
);

CREATE INDEX translation_sessions_user_expiry_idx ON translation_sessions (user_id, expires_at DESC);
CREATE INDEX translation_sessions_expiry_idx ON translation_sessions (expires_at, id);
CREATE INDEX translation_sessions_entitlement_idx ON translation_sessions (entitlement_id, created_at DESC);

CREATE TABLE usage_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id uuid NOT NULL,
    audio_seconds integer NOT NULL DEFAULT 0,
    characters integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT usage_records_nonnegative CHECK (audio_seconds >= 0 AND characters >= 0),
    CONSTRAINT usage_records_session_unique UNIQUE (session_id),
    CONSTRAINT usage_records_session_owner_fk FOREIGN KEY (session_id, user_id) REFERENCES translation_sessions(id, user_id)
);

CREATE INDEX usage_records_user_created_idx ON usage_records (user_id, created_at DESC, id DESC);

CREATE TABLE feedback_consents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    granted boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feedback_consents_id_user_granted_unique UNIQUE (id, user_id, granted)
);

CREATE INDEX feedback_consents_user_created_idx ON feedback_consents (user_id, created_at DESC, id DESC);

CREATE TABLE feedback_artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consent_id uuid NOT NULL,
    consent_granted boolean NOT NULL DEFAULT true,
    object_key text NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feedback_artifacts_object_key_valid CHECK (length(btrim(object_key)) BETWEEN 1 AND 1024),
    CONSTRAINT feedback_artifacts_object_key_unique UNIQUE (object_key),
    CONSTRAINT feedback_artifacts_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT feedback_artifacts_retention_max CHECK (expires_at <= created_at + interval '14 days'),
    CONSTRAINT feedback_artifacts_granted_consent CHECK (consent_granted),
    CONSTRAINT feedback_artifacts_consent_owner_fk FOREIGN KEY (consent_id, user_id, consent_granted) REFERENCES feedback_consents(id, user_id, granted)
);

CREATE INDEX feedback_artifacts_user_created_idx ON feedback_artifacts (user_id, created_at DESC, id DESC);
CREATE INDEX feedback_artifacts_expiry_idx ON feedback_artifacts (expires_at, id);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id uuid NOT NULL,
    admin_role text NOT NULL DEFAULT 'admin',
    action text NOT NULL,
    target_type text NOT NULL,
    target_id uuid,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT audit_logs_admin_role CHECK (admin_role = 'admin'),
    CONSTRAINT audit_logs_admin_fk FOREIGN KEY (admin_id, admin_role) REFERENCES users(id, role),
    CONSTRAINT audit_logs_action_valid CHECK (length(btrim(action)) BETWEEN 1 AND 100),
    CONSTRAINT audit_logs_target_type_valid CHECK (length(btrim(target_type)) BETWEEN 1 AND 100),
    CONSTRAINT audit_logs_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX audit_logs_created_idx ON audit_logs (created_at DESC, id DESC);
CREATE INDEX audit_logs_admin_created_idx ON audit_logs (admin_id, created_at DESC, id DESC);
CREATE INDEX audit_logs_target_created_idx ON audit_logs (target_type, target_id, created_at DESC, id DESC);
CREATE INDEX audit_logs_metadata_gin_idx ON audit_logs USING gin (metadata);

CREATE FUNCTION prevent_audit_log_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END;
$$;

CREATE TRIGGER audit_logs_prevent_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

CREATE TRIGGER audit_logs_prevent_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

COMMIT;
