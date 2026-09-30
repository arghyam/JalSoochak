-- ============================================================
-- Migration: V56 - submitted_unit on flow_reading_table
-- ------------------------------------------------------------
--   flow_reading_table
--     + submitted_unit  VARCHAR(16)  -- NULL on rows written before this
--     + column comments on extracted_reading, confirmed_reading and
--       submitted_unit, since the table holds readings in several units
--
-- confirmed_reading is always stored in the channel's standard unit (m3
-- for BFM, kW.h for ELM, min for PDU). submitted_unit records the UCUM
-- code the value was sent in, which the stored value has already been
-- converted from. Nullable: existing rows have no recorded unit.
--
-- Runs in one transaction. As in V54, the SHARE lock on tenant_master_table,
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
        -- flow_reading_table is skipped for that table only, never aborting
        -- the whole migration.
        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.flow_reading_table
                     ADD COLUMN IF NOT EXISTS submitted_unit VARCHAR(16)',
                tenant_schema);
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.extracted_reading IS %L', tenant_schema,
                'What OCR read off the meter photo, in the channel''s standard unit. 0 when no photo was read.');
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.confirmed_reading IS %L', tenant_schema,
                'The reading in the channel''s standard unit, whatever unit it was sent in (see submitted_unit). '
                || 'BFM: m3, cumulative meter index. ELM: kW.h, cumulative meter index. '
                || 'PDU: min, pump run time of this submission.');
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.submitted_unit IS %L', tenant_schema,
                'UCUM code of the unit the reading was sent in: m3, kL or L (BFM), kW.h (ELM), min or h (PDU). '
                || 'confirmed_reading holds the value converted to the channel''s standard unit. '
                || 'NULL on rows from before V56, rows with no reading, and IOT and MAN rows.');
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column and comments ──
-- Wrapper pattern (as used by V42/V51/V53/V54): preserve the current
-- implementation once under a versioned name, then wrap it to add the column.
-- The captured base therefore already includes the V54 wrapper and V55's
-- in-place patch, which must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v56_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v56_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v56_base(schema_name);

    -- Submitted unit and column comments for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS submitted_unit VARCHAR(16)',
            schema_name);
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.extracted_reading IS %L', schema_name,
            'What OCR read off the meter photo, in the channel''s standard unit. 0 when no photo was read.');
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.confirmed_reading IS %L', schema_name,
            'The reading in the channel''s standard unit, whatever unit it was sent in (see submitted_unit). '
            || 'BFM: m3, cumulative meter index. ELM: kW.h, cumulative meter index. '
            || 'PDU: min, pump run time of this submission.');
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.submitted_unit IS %L', schema_name,
            'UCUM code of the unit the reading was sent in: m3, kL or L (BFM), kW.h (ELM), min or h (PDU). '
            || 'confirmed_reading holds the value converted to the channel''s standard unit. '
            || 'NULL on rows from before V56, rows with no reading, and IOT and MAN rows.');
    END IF;
END;
$func$;
