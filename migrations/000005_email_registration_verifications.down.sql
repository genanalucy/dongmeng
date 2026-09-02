BEGIN;

DROP INDEX IF EXISTS email_verification_rate_limits_expiry_idx;
DROP TABLE IF EXISTS email_verification_rate_limits;
DROP INDEX IF EXISTS registration_verifications_expires_at_idx;
DROP TABLE IF EXISTS registration_verifications;

COMMIT;
