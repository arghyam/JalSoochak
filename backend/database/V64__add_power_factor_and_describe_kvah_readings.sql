-- ============================================================
-- Migration: V64 - power_factor on asset_pump_registry_table, and kVAh
--                  in the flow_reading_table column comments
-- ------------------------------------------------------------
--   asset_pump_registry_table
--     + power_factor  FLOAT  -- fraction in (0, 1]
--   flow_reading_table
--     column comments on extracted_reading, confirmed_reading and
--     submitted_unit now cover ELM readings in kV.A.h
--
-- An ELM meter may show apparent energy (kVAh) instead of active energy
-- (kWh). Such a reading is stored as read, in kV.A.h, and analytics turns
-- the day's kVAh increase into kWh by multiplying it by the power factor,
-- averaged over the scheme's active pumps, before the ELM formula runs.
-- Converting at capture would multiply the cumulative index, so a power
-- factor change would add energy that was never used. kWh readings never
-- use the power factor.
--
-- power_factor is nullable: no existing pump has one recorded. Pump rows
-- are entered by SQL like the other ratings, and a kVAh day on a scheme
-- with no power factor has no water quantity until one is entered and the
-- day recalculated.
--
-- ADD COLUMN without a default and COMMENT touch the catalogue only, so no
-- table is rewritten. On asset_pump_registry_table the ACCESS EXCLUSIVE lock
-- ADD COLUMN takes waits for telemetry-service's short reads and holds new
-- ones off until this commits. On flow_reading_table COMMENT takes only a
-- SHARE UPDATE EXCLUSIVE lock, which reads and writes do not wait for.
--
-- Runs in one transaction. As in V57, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unwrapped create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned without the column or comments unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Add the column and comments in existing tenant schemas ─────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard each table with to_regclass so a partially-provisioned schema
        -- missing it is skipped for that table only, never aborting the whole
        -- migration.
        IF to_regclass(format('%I.asset_pump_registry_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.asset_pump_registry_table
                     ADD COLUMN IF NOT EXISTS power_factor FLOAT',
                tenant_schema);
            EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.power_factor IS %L', tenant_schema,
                'Power factor as a fraction greater than 0 and at most 1 (0.9, not 90). '
                || 'ELM: kWh = the day''s kVAh increase x the average over the scheme''s active pumps. '
                || 'Needed only on schemes whose meter is read in kVAh.');
        END IF;

        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.extracted_reading IS %L', tenant_schema,
                'What OCR read off the meter photo, in the same unit as confirmed_reading. '
                || '0 when no photo was read.');
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.confirmed_reading IS %L', tenant_schema,
                'The reading in the channel''s standard unit, whatever unit it was sent in (see submitted_unit). '
                || 'BFM: m3, cumulative meter index. ELM: kW.h, cumulative meter index, or kV.A.h as read '
                || 'when submitted_unit is kV.A.h. PDU: min, pump run time of this submission.');
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.submitted_unit IS %L', tenant_schema,
                'UCUM code of the unit the reading was sent in: m3, kL or L (BFM), kW.h or kV.A.h (ELM), '
                || 'min or h (PDU). confirmed_reading holds the value converted to the channel''s standard unit, '
                || 'except kV.A.h, which is kept as read. NULL on rows from before V56, rows with no reading, '
                || 'and IOT and MAN rows; NULL means the channel''s standard unit.');
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas get the same column and comments ─────
-- Wrapper pattern (as used by V53/V56/V57): preserve the current
-- implementation once under a versioned name, then wrap it to add the
-- column and comments. The captured base therefore already includes the
-- V56 comments this replaces, and must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v64_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v64_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v64_base(schema_name);

    -- Power factor and kVAh-aware reading comments for new tenant schemas.
    -- Guard each table with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.asset_pump_registry_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.asset_pump_registry_table
                 ADD COLUMN IF NOT EXISTS power_factor FLOAT',
            schema_name);
        EXECUTE format('COMMENT ON COLUMN %I.asset_pump_registry_table.power_factor IS %L', schema_name,
            'Power factor as a fraction greater than 0 and at most 1 (0.9, not 90). '
            || 'ELM: kWh = the day''s kVAh increase x the average over the scheme''s active pumps. '
            || 'Needed only on schemes whose meter is read in kVAh.');
    END IF;

    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.extracted_reading IS %L', schema_name,
            'What OCR read off the meter photo, in the same unit as confirmed_reading. '
            || '0 when no photo was read.');
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.confirmed_reading IS %L', schema_name,
            'The reading in the channel''s standard unit, whatever unit it was sent in (see submitted_unit). '
            || 'BFM: m3, cumulative meter index. ELM: kW.h, cumulative meter index, or kV.A.h as read '
            || 'when submitted_unit is kV.A.h. PDU: min, pump run time of this submission.');
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.submitted_unit IS %L', schema_name,
            'UCUM code of the unit the reading was sent in: m3, kL or L (BFM), kW.h or kV.A.h (ELM), '
            || 'min or h (PDU). confirmed_reading holds the value converted to the channel''s standard unit, '
            || 'except kV.A.h, which is kept as read. NULL on rows from before V56, rows with no reading, '
            || 'and IOT and MAN rows; NULL means the channel''s standard unit.');
    END IF;
END;
$func$;
