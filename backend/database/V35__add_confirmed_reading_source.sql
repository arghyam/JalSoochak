-- ============================================================
-- Migration: V35 - Confirmed-reading provenance (ROLLOVER-RESOLVE)
-- ------------------------------------------------------------
-- The telemetry-service can now resolve FlowVision rollover-digit
-- ambiguity before a reading is confirmed: it may replace the
-- model's raw pick (still stored verbatim in extracted_reading)
-- with a sibling candidate that better fits the scheme's recent
-- consumption pattern, and writes the result into confirmed_reading.
--
-- This column records which rows that logic modified so the change
-- is fully auditable and revertible:
--
--   flow_reading_table
--     + confirmed_reading_source  SMALLINT  NOT NULL DEFAULT 0
--          0 = AS_EXTRACTED        (confirmed_reading == the model's pick)
--          1 = ROLLOVER_RESOLVED   (confirmed_reading resolved to a sibling digit)
--
-- The recording behaviour lives in telemetry-service and is gated
-- by the `flowvision.rollover.resolution.enabled` flag. DEFAULT 0
-- means every pre-existing row, every other insert path (placeholder
-- reuse, lenient ingestion, meter-change, reprocessing) and every
-- pre-migration tenant stay correct with zero code changes. No index
-- is added — the column is not a query dimension. Every related
-- change is marked "ROLLOVER-RESOLVE".
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing flow_reading_table is
        -- skipped for that table only, never aborting the whole migration.
        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.flow_reading_table
                     ADD COLUMN IF NOT EXISTS confirmed_reading_source SMALLINT NOT NULL DEFAULT 0',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34): preserve the current implementation once under a
-- versioned name, then wrap it to add the ROLLOVER-RESOLVE column. The captured base therefore already
-- includes the V34 provisioning, which must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v35_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v35_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v35_base(schema_name);

    -- ROLLOVER-RESOLVE: provenance column for new tenant schemas. Guard with to_regclass so a
    -- partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS confirmed_reading_source SMALLINT NOT NULL DEFAULT 0',
            schema_name);
    END IF;
END;
$func$;
