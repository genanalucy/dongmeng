BEGIN;

-- Existing codes remain globally single-use. Shared campaigns use the same
-- canonical hash and HTTP endpoint, but retain a durable claim per account.
ALTER TABLE redemption_codes ADD COLUMN shared boolean NOT NULL DEFAULT false;
ALTER TABLE redemption_codes ADD COLUMN claim_expires_at timestamptz;
ALTER TABLE redemption_codes ADD CONSTRAINT redemption_codes_shared_valid
    CHECK (NOT shared OR (claim_expires_at IS NOT NULL AND redeemed_by IS NULL AND redeemed_at IS NULL));

CREATE TABLE redemption_claims (
    code_id uuid NOT NULL REFERENCES redemption_codes(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    entitlement_id uuid NOT NULL UNIQUE,
    claimed_at timestamptz NOT NULL,
    PRIMARY KEY (code_id, user_id),
    CONSTRAINT redemption_claims_entitlement_owner_fk
        FOREIGN KEY (entitlement_id, user_id) REFERENCES entitlements(id, user_id) ON DELETE RESTRICT
);
CREATE INDEX redemption_claims_user_idx ON redemption_claims(user_id, claimed_at DESC);

-- Match the existing restricted production runtime role, when provisioned.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dngmeng_cloud_api') THEN
        GRANT SELECT, INSERT ON TABLE redemption_claims TO dngmeng_cloud_api;
    END IF;
END $$;

COMMIT;
