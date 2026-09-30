-- ============================================================
-- Migration: V57 - asset_pump_registry_table.status defaults to active
-- ------------------------------------------------------------
--   asset_pump_registry_table
--     status  INTEGER NOT NULL  + DEFAULT 1  -- 1 = active, 0 = inactive
--     + column comments on the ratings the ELM and PDU water quantities
--       are calculated from, with the unit each must be entered in
--
-- A pump's water quantity inputs are read only from active pumps
-- (status = 1 AND deleted_at IS NULL). Pump rows are entered by SQL, so a
-- row inserted without a status is active rather than rejected. Existing
-- rows keep the status they have.
--
-- The ratings are plain FLOATs, so nothing stops a value in the wrong unit:
-- a discharge capacity in m3/h instead of L/min makes every PDU total
-- about 16.7 times too high. The comments say what analytics assumes.
--
-- SET DEFAULT and COMMENT touch the catalogue only, so no table is
-- rewritten. The ACCESS EXCLUSIVE lock they take waits for
-- telemetry-service's short reads of this table and holds new ones off
-- until this commits.
--
-- Runs in one transaction. As in V56, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unwrapped create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned without the default or comments unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Set the default and comments in existing tenant schemas ────────
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
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.pump_discharge_capacity IS %L', tenant_schema,
                'Litres per minute (L/min) the pump delivers. PDU: litres = run minutes x the sum over the '
                || 'scheme''s active pumps. Also used by ELM F1 and F2.');
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.pump_efficiency IS %L', tenant_schema,
                'Pump efficiency as a fraction from 0 to 1 (0.7, not 70). Used by ELM F3.');
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.pump_head IS %L', tenant_schema,
                'Pump head in metres. Used by ELM F3.');
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.motor_power IS %L', tenant_schema,
                'Motor power rating, in motor_power_unit (kW, HP or bHP). Used by ELM F2.');
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.motor_efficiency IS %L', tenant_schema,
                'Motor efficiency as a fraction from 0 to 1 (0.85, not 85). Used by ELM F3.');
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.units_consumed_per_hour IS %L', tenant_schema,
                'Electricity the pump uses per hour of running, in kWh per hour. Used by ELM F1.');
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas get the same default and comments ────
-- Wrapper pattern (as used by V42/V51/V53/V54/V56): preserve the current
-- implementation once under a versioned name, then wrap it to set the
-- default and comments. The captured base therefore already includes the
-- V56 wrapper, which must run before this migration.
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

    -- Active-by-default pump status and rating comments for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.asset_pump_registry_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.asset_pump_registry_table
                 ALTER COLUMN status SET DEFAULT 1',
            schema_name);
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.pump_discharge_capacity IS %L', schema_name,
            'Litres per minute (L/min) the pump delivers. PDU: litres = run minutes x the sum over the '
            || 'scheme''s active pumps. Also used by ELM F1 and F2.');
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.pump_efficiency IS %L', schema_name,
            'Pump efficiency as a fraction from 0 to 1 (0.7, not 70). Used by ELM F3.');
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.pump_head IS %L', schema_name,
            'Pump head in metres. Used by ELM F3.');
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.motor_power IS %L', schema_name,
            'Motor power rating, in motor_power_unit (kW, HP or bHP). Used by ELM F2.');
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.motor_efficiency IS %L', schema_name,
            'Motor efficiency as a fraction from 0 to 1 (0.85, not 85). Used by ELM F3.');
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.units_consumed_per_hour IS %L', schema_name,
            'Electricity the pump uses per hour of running, in kWh per hour. Used by ELM F1.');
    END IF;
END;
$func$;
