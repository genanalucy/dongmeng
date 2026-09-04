BEGIN;

-- Server-side encrypted translation history. Only completed text turns are
-- persisted, and only as AEAD ciphertext: nonce, ciphertext, and the data-key
-- version that selects the per-user HKDF derivation. No plaintext columns.
CREATE TABLE history_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT history_sessions_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT history_sessions_tombstone_valid CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);

-- Owner-scoped listing and the live-session cap (domain.HistoryMaxLiveSessions)
-- both read the partial index over non-tombstoned sessions.
CREATE INDEX history_sessions_user_live_idx
    ON history_sessions (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE history_turns (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id uuid NOT NULL,
    key_version integer NOT NULL,
    nonce bytea,
    ciphertext bytea,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT history_turns_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT history_turns_session_owner_fk FOREIGN KEY (session_id, user_id) REFERENCES history_sessions(id, user_id) ON DELETE CASCADE,
    CONSTRAINT history_turns_key_version_positive CHECK (key_version >= 1),
    -- A live turn always carries its AEAD pair; a tombstoned turn keeps only
    -- the identifiers and timestamps, never ciphertext.
    CONSTRAINT history_turns_ciphertext_shape CHECK (
        (nonce IS NULL AND ciphertext IS NULL AND deleted_at IS NOT NULL)
        OR (nonce IS NOT NULL AND ciphertext IS NOT NULL AND octet_length(nonce) = 12 AND octet_length(ciphertext) BETWEEN 16 AND 262144)
    ),
    CONSTRAINT history_turns_tombstone_valid CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);

-- Turns are listed inside one owner-scoped session, newest first.
CREATE INDEX history_turns_session_created_idx
    ON history_turns (session_id, created_at DESC, id DESC);

-- The live-turn cap (domain.HistoryMaxLiveTurns) counts non-tombstoned turns
-- per user through this partial covering index.
CREATE INDEX history_turns_user_live_idx
    ON history_turns (user_id)
    WHERE deleted_at IS NULL;

COMMIT;
