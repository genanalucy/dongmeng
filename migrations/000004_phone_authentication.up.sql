BEGIN;

ALTER TABLE users ADD COLUMN username text;
ALTER TABLE users ADD COLUMN phone text;

ALTER TABLE users ADD CONSTRAINT users_username_normalized
    CHECK (username IS NULL OR (username = lower(btrim(username)) AND length(username) BETWEEN 3 AND 32 AND username ~ '^[a-z0-9_]+$'));
ALTER TABLE users ADD CONSTRAINT users_phone_normalized
    CHECK (phone IS NULL OR phone ~ '^\+861[3-9][0-9]{9}$');

CREATE UNIQUE INDEX users_username_unique_idx ON users (username) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX users_phone_unique_idx ON users (phone) WHERE phone IS NOT NULL;

COMMIT;
