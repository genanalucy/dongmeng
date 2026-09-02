BEGIN;

ALTER TABLE registration_verifications
    ADD COLUMN reservation_id uuid;

UPDATE registration_verifications
SET reservation_id = gen_random_uuid()
WHERE reservation_id IS NULL;

ALTER TABLE registration_verifications
    ALTER COLUMN reservation_id SET NOT NULL;

COMMIT;
