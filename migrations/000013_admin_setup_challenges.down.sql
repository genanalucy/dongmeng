BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dngmeng_cloud_api')
       AND to_regclass('admin_setup_challenges') IS NOT NULL THEN
        REVOKE SELECT, UPDATE ON TABLE admin_setup_challenges FROM dngmeng_cloud_api;
    END IF;
END;
$$;

DROP TABLE IF EXISTS admin_setup_challenges;

COMMIT;
