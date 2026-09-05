BEGIN;

-- History titles are encrypted with the same per-user AEAD root key as turns.
-- No plaintext history content or titles are persisted.
ALTER TABLE history_sessions
    ADD COLUMN title_key_version integer,
    ADD COLUMN title_nonce bytea,
    ADD COLUMN title_ciphertext bytea,
    ADD COLUMN title_updated_at timestamptz,
    ADD CONSTRAINT history_sessions_title_shape CHECK (
        (title_key_version IS NULL AND title_nonce IS NULL AND title_ciphertext IS NULL AND title_updated_at IS NULL)
        OR (title_key_version >= 1 AND title_nonce IS NOT NULL AND title_ciphertext IS NOT NULL
            AND title_updated_at IS NOT NULL AND octet_length(title_nonce) = 12
            AND octet_length(title_ciphertext) BETWEEN 16 AND 262144)
    );

-- Durable owner-scoped operation IDs provide retry-safe sync. The opaque
-- change cursor is the generated sequence value, not client time.
CREATE TABLE history_changes (
    cursor bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id uuid NOT NULL,
    turn_id uuid,
    action text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT history_changes_action_valid CHECK (action IN ('turn.upsert', 'session.delete', 'title.patch'))
);
CREATE INDEX history_changes_user_cursor_idx ON history_changes (user_id, cursor);

CREATE TABLE history_operations (
    operation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cursor bigint NOT NULL REFERENCES history_changes(cursor) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT history_operations_id_user_unique UNIQUE (operation_id, user_id)
);

COMMIT;
