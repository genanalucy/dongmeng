BEGIN;

CREATE TABLE registration_verifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username text NOT NULL,
    email text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    code_hash bytea NOT NULL,
    code_salt bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    sent_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX registration_verifications_expires_at_idx ON registration_verifications (expires_at);

CREATE TABLE email_verification_rate_limits (
    key_type text NOT NULL CHECK (key_type IN ('email', 'ip')),
    key_hash bytea NOT NULL,
    window_started_at timestamptz NOT NULL,
    request_count integer NOT NULL CHECK (request_count >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (key_type, key_hash)
);

COMMIT;
