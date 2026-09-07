BEGIN;

-- auth_version invalidates every previously issued access token the moment
-- an administrator password change commits. Access tokens carry the version
-- they were issued under; the authentication middleware rejects any token
-- whose version no longer matches the persisted row. Tokens issued before
-- this column existed carry no claim and therefore compare as version 0,
-- matching the default, so existing sessions keep working until the first
-- password change bumps the version.
ALTER TABLE users ADD COLUMN auth_version integer NOT NULL DEFAULT 0;

-- No privilege changes: the runtime role already holds table-level
-- privileges on users, and a new column inherits them automatically.

COMMIT;
