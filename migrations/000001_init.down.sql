BEGIN;

DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS feedback_artifacts;
DROP TABLE IF EXISTS feedback_consents;
DROP TABLE IF EXISTS usage_records;
DROP TABLE IF EXISTS translation_sessions;
DROP TABLE IF EXISTS redemption_codes;
DROP TABLE IF EXISTS code_batches;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS entitlements;
DROP TABLE IF EXISTS users;
DROP FUNCTION IF EXISTS prevent_audit_log_mutation();

COMMIT;
