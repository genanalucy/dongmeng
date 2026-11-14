BEGIN;

-- Refuse destructive rollback after any campaign has been provisioned/used.
LOCK TABLE redemption_codes, redemption_claims IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM redemption_codes WHERE shared)
       OR EXISTS (SELECT 1 FROM redemption_claims) THEN
        RAISE EXCEPTION 'shared redemption data requires an explicit recovery plan';
    END IF;
END $$;
DROP TABLE redemption_claims;
ALTER TABLE redemption_codes DROP CONSTRAINT redemption_codes_shared_valid;
ALTER TABLE redemption_codes DROP COLUMN claim_expires_at;
ALTER TABLE redemption_codes DROP COLUMN shared;

COMMIT;
