-- ============================================================
-- Migration: V57 - asset_pump_registry_table.status defaults to active
-- ------------------------------------------------------------
--   asset_pump_registry_table
--     status  INTEGER NOT NULL  + DEFAULT 1  -- 1 = active, 0 = inactive
--
-- A pump's water quantity inputs are read only from active pumps
-- (status = 1 AND deleted_at IS NULL). Pump rows are entered by SQL, so a
-- row inserted without a status is active rather than rejected. Existing
-- rows keep the status they have.
--
-- SET DEFAULT touches the catalogue only, so no table is rewritten. The
-- ACCESS EXCLUSIVE lock it takes waits for telemetry-service's short reads
-- of this table and holds new ones off until this commits.
--
-- Runs in one transaction. As in V56, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unwrapped create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned without the default unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Set the default in existing tenant schemas ──────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing
        -- asset_pump_registry_table is skipped for that table only, never
        -- aborting the whole migration.
        IF to_regclass(format('%I.asset_pump_registry_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.asset_pump_registry_table
                     ALTER COLUMN status SET DEFAULT 1',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas get the same default ──────────────────
-- Wrapper pattern (as used by V42/V51/V53/V54/V56): preserve the current
-- implementation once under a versioned name, then wrap it to set the
-- default. The captured base therefore already includes the V56 wrapper,
-- which must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v57_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v57_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v57_base(schema_name);

    -- Active-by-default pump status for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.asset_pump_registry_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.asset_pump_registry_table
                 ALTER COLUMN status SET DEFAULT 1',
            schema_name);
    END IF;
END;
$func$;
