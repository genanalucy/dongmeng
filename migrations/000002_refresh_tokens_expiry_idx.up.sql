CREATE INDEX CONCURRENTLY IF NOT EXISTS refresh_tokens_expiry_idx
    ON refresh_tokens (expires_at, id);
