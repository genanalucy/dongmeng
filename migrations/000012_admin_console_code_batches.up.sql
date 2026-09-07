ALTER TABLE code_batches ADD COLUMN disabled_at timestamptz;

CREATE INDEX code_batches_active_created_idx
    ON code_batches (created_at DESC, id DESC)
    WHERE disabled_at IS NULL;
