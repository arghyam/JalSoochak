-- ============================================================
-- Migration: V54 - k_factor on scheme_master_table
-- ------------------------------------------------------------
--   scheme_master_table
--     + k_factor  FLOAT  -- NULL when not recorded
--
-- Nullable: no existing scheme has a k-factor recorded.
--
-- Runs in one transaction. As in V51, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unwrapped create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned without the column unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing
        -- scheme_master_table is skipped for that table only, never aborting
        -- the whole migration.
        IF to_regclass(format('%I.scheme_master_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.scheme_master_table
                     ADD COLUMN IF NOT EXISTS k_factor FLOAT',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V42/V51/V53): preserve the current
-- implementation once under a versioned name, then wrap it to add the column.
-- The captured base therefore already includes the V53 provisioning, which
-- must run before this migration.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'create_tenant_schema'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'create_tenant_schema_v54_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v54_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v54_base(schema_name);

    -- Scheme k-factor for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.scheme_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.scheme_master_table
                 ADD COLUMN IF NOT EXISTS k_factor FLOAT',
            schema_name);
    END IF;
END;
$func$;
