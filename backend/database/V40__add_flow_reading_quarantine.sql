-- ============================================================
-- Migration: V40 - Reading quarantine marker (SUPPLY-PLAUSIBILITY)
-- ------------------------------------------------------------
-- A submitted reading whose implied daily supply is not physically
-- plausible for the scheme's connected population is *stored* rather
-- than discarded, but marked so nothing downstream treats it as a
-- real reading:
--
--   flow_reading_table
--     + quarantine_reason  SMALLINT  NOT NULL DEFAULT 0
--          0 = NONE                       (an ordinary, accepted reading)
--          1 = IMPLAUSIBLE_WATER_SUPPLY   (implied litres/day exceed the
--                                          scheme's population ceiling)
--
-- A reason code rather than a boolean so a future quarantine cause reuses
-- the column instead of adding another one. DEFAULT 0 keeps every
-- pre-existing row and every other insert path (placeholder reuse, lenient
-- ingestion, meter-change, issue-report) correct with no code change.
--
-- Why the row is marked instead of simply not being published: telemetry's
-- own baseline comes from tenant_<x>.flow_reading_table while analytics'
-- comes from analytics_schema.fact_meter_reading_table. An unmarked,
-- unpublished row would make the two disagree — telemetry would see a small
-- delta the next day and pass the reading, while analytics would measure
-- against the last *published* reading and land the whole abnormal jump in
-- the warehouse a day late with no anomaly attached. Excluding marked rows
-- from telemetry's own baseline queries is what makes the suppression mean
-- anything.
--
-- No index: this is a filter on queries already anchored by scheme_id, not a
-- query dimension of its own. The V35 composite index
-- flow_reading_table(scheme_id, reading_date DESC) continues to serve the
-- baseline queries. Every related change is marked "SUPPLY-PLAUSIBILITY".
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
                     ADD COLUMN IF NOT EXISTS quarantine_reason SMALLINT NOT NULL DEFAULT 0',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34/V35/V36/V37/V39): preserve the current
-- implementation once under a versioned name, then wrap it to add the SUPPLY-PLAUSIBILITY column.
-- The captured base therefore already includes the V39 provisioning, which must run before this
-- migration.
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
        WHERE p.proname = 'create_tenant_schema_v40_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v40_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v40_base(schema_name);

    -- SUPPLY-PLAUSIBILITY: quarantine marker for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS quarantine_reason SMALLINT NOT NULL DEFAULT 0',
            schema_name);
    END IF;
END;
$func$;
