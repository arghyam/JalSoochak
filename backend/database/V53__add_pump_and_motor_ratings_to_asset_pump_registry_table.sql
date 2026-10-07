-- ============================================================
-- Migration: V53 - Pump and motor ratings on
--                  asset_pump_registry_table
-- ------------------------------------------------------------
-- The table held the pump's own ratings under bare names. This prefixes
-- them with pump_, so they read apart from the motor's ratings added
-- beside them, in every tenant schema:
--
--   asset_pump_registry_table
--     efficiency      -> pump_efficiency
--     head            -> pump_head
--     discharge_rate  -> pump_discharge_capacity
--     + motor_power              FLOAT
--     + motor_power_unit         VARCHAR(20)  -- as recorded: kW, HP, bHP, ...
--     + motor_efficiency         FLOAT
--     + units_consumed_per_hour  FLOAT
--
-- The new columns are nullable: no existing pump has these ratings
-- recorded. motor_power_unit is free text rather than a CHECK list, as the
-- units on the motors in the field are not a closed set.
--
-- RENAME COLUMN, and ADD COLUMN without a default, touch the catalogue
-- only, so no table is rewritten. No service reads or writes this table, so
-- the ACCESS EXCLUSIVE lock each takes is granted at once, as in V52.
--
-- Runs in one transaction. As in V52, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unpatched create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned with the old shape unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Ensure new tenant schemas get the new names ─────────────────────
-- The table is created in one function body at the end of the create_tenant_schema() chain, which
-- V52 patched in place for the table rename. The chain is walked the same way, and that body's
-- three column definitions renamed in place from its full definition. A wrapper would leave every
-- new tenant creating the old columns only to rename them.
DO $$
DECLARE
    fn_name     TEXT := 'create_tenant_schema';
    fn_oid      OID;
    fn_src      TEXT;
    patched_def TEXT;
    patched     INT := 0;
BEGIN
    LOOP
        SELECT p.oid, p.prosrc
        INTO fn_oid, fn_src
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'common_schema'
          AND p.proname = fn_name
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text';

        IF NOT FOUND THEN
            RAISE EXCEPTION 'V53: common_schema.%(text) is called by the create_tenant_schema() chain but does not exist',
                fn_name;
        END IF;

        IF strpos(fn_src, 'asset_pump_registry_table (') > 0 THEN
            patched_def := regexp_replace(regexp_replace(regexp_replace(
                pg_get_functiondef(fn_oid),
                '\mefficiency(\s+)FLOAT,', 'pump_efficiency\1FLOAT,'),
                '\mhead(\s+)FLOAT,', 'pump_head\1FLOAT,'),
                '\mdischarge_rate(\s+)FLOAT,', 'pump_discharge_capacity\1FLOAT,');

            IF patched_def ~ '\m(efficiency|head|discharge_rate)\M'
               OR patched_def !~ '\mpump_efficiency\s+FLOAT,'
               OR patched_def !~ '\mpump_head\s+FLOAT,'
               OR patched_def !~ '\mpump_discharge_capacity\s+FLOAT,' THEN
                RAISE EXCEPTION 'V53 patch failed: common_schema.%() defines the pump ratings in a form this migration does not rewrite',
                    fn_name;
            END IF;

            EXECUTE patched_def;
            patched := patched + 1;
        END IF;

        -- Each wrapper runs the function it wraps first; the chain ends at a body with no such call.
        fn_name := substring(fn_src FROM 'PERFORM\s+common_schema\.(create_tenant_schema_[a-z0-9_]+)\s*\(');
        EXIT WHEN fn_name IS NULL;
    END LOOP;

    IF patched = 0 THEN
        RAISE EXCEPTION 'V53: no function in the create_tenant_schema() chain creates asset_pump_registry_table';
    END IF;

    RAISE NOTICE 'V53: renamed the pump ratings in % tenant provisioning function(s)', patched;
END $$;

-- ── Part B: Ensure new tenant schemas include the motor columns ─────────────
-- Wrapper pattern (as used by V42/V47/V51): preserve the current
-- implementation once under a versioned name, then wrap it to add the
-- columns. Adding them after the table is created, as Part C does, gives old
-- and new tenants the same column order. The captured base therefore
-- already includes the V51 provisioning, which must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v53_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v53_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v53_base(schema_name);

    -- Motor ratings for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.asset_pump_registry_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.asset_pump_registry_table
                 ADD COLUMN IF NOT EXISTS motor_power             FLOAT,
                 ADD COLUMN IF NOT EXISTS motor_power_unit        VARCHAR(20),
                 ADD COLUMN IF NOT EXISTS motor_efficiency        FLOAT,
                 ADD COLUMN IF NOT EXISTS units_consumed_per_hour FLOAT',
            schema_name);
    END IF;
END;
$func$;

-- ── Part C: Rename and add in existing tenant schemas ───────────────────────
-- Each step is guarded, so a partially-provisioned schema missing the table, or any column to
-- rename, is skipped for that object only.
DO $$
DECLARE
    tenant_schema TEXT;
    col           RECORD;
    altered       INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        IF to_regclass(format('%I.asset_pump_registry_table', tenant_schema)) IS NULL THEN
            CONTINUE;
        END IF;

        FOR col IN
            SELECT *
            FROM (VALUES
                ('efficiency',     'pump_efficiency'),
                ('head',           'pump_head'),
                ('discharge_rate', 'pump_discharge_capacity')
            ) AS r(old_name, new_name)
        LOOP
            IF EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = tenant_schema
                  AND table_name = 'asset_pump_registry_table'
                  AND column_name = col.old_name
            ) THEN
                EXECUTE format(
                    'ALTER TABLE %I.asset_pump_registry_table RENAME COLUMN %I TO %I',
                    tenant_schema, col.old_name, col.new_name);
            END IF;
        END LOOP;

        EXECUTE format(
            'ALTER TABLE %1$I.asset_pump_registry_table
                 ADD COLUMN IF NOT EXISTS motor_power             FLOAT,
                 ADD COLUMN IF NOT EXISTS motor_power_unit        VARCHAR(20),
                 ADD COLUMN IF NOT EXISTS motor_efficiency        FLOAT,
                 ADD COLUMN IF NOT EXISTS units_consumed_per_hour FLOAT',
            tenant_schema);

        altered := altered + 1;
    END LOOP;

    RAISE NOTICE 'V53: updated the pump ratings in % tenant schema(s)', altered;
END $$;
