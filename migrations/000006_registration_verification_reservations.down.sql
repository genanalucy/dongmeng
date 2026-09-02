BEGIN;

ALTER TABLE registration_verifications
    DROP COLUMN reservation_id;

COMMIT;
