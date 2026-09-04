BEGIN;

-- Registration captchas persist only a salted HMAC of the answer. The
-- challenge text and its SVG rendering never touch the database.
CREATE TABLE registration_captchas (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    answer_hash bytea NOT NULL CHECK (octet_length(answer_hash) = 32),
    answer_salt bytea NOT NULL CHECK (octet_length(answer_salt) BETWEEN 16 AND 64),
    expires_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX registration_captchas_expires_at_idx ON registration_captchas (expires_at, id);

-- Fixed one-hour windows keyed by HMAC(per-IP) buckets, mirroring the
-- fixed-window shape used by the legacy verification flow while staying fully
-- isolated from the tables that flow retains for rollback.
CREATE TABLE captcha_rate_limits (
    key_type text NOT NULL CHECK (key_type IN ('issue', 'register')),
    key_hash bytea NOT NULL,
    window_started_at timestamptz NOT NULL,
    request_count integer NOT NULL CHECK (request_count >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (key_type, key_hash)
);

-- Both buckets expire one hour after their fixed window starts; this index
-- supports bounded cleanup without retaining long-lived key hashes.
CREATE INDEX captcha_rate_limits_expiry_idx
    ON captcha_rate_limits (window_started_at);

COMMIT;
