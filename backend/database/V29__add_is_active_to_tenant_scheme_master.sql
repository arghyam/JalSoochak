-- Add is_active to scheme_master_table for all tenant schemas.
-- Safe to re-run because of IF NOT EXISTS.

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        EXECUTE format(
                'ALTER TABLE %I.scheme_master_table ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE',
                r.schema_name
                );
        RAISE NOTICE 'Updated table in schema: %', r.schema_name;
    END LOOP;
END $$;

