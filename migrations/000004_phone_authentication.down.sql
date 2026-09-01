BEGIN;

DROP INDEX IF EXISTS users_phone_unique_idx;
DROP INDEX IF EXISTS users_username_unique_idx;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_phone_normalized;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_normalized;
ALTER TABLE users DROP COLUMN IF EXISTS phone;
ALTER TABLE users DROP COLUMN IF EXISTS username;

COMMIT;
