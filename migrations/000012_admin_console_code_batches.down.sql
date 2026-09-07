DROP INDEX IF EXISTS code_batches_active_created_idx;
ALTER TABLE code_batches DROP COLUMN IF EXISTS disabled_at;
