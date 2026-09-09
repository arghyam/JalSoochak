-- ============================================================
-- Migration: V41 - Per-operator latest-reading index (OPERATOR-LATEST)
-- ------------------------------------------------------------
-- Several queries resolve "the latest reading submitted by this operator"
-- with a correlated LATERAL lookup keyed on created_by alone:
--
--   SELECT ... FROM <tenant>.flow_reading_table fr
--   WHERE fr.deleted_at IS NULL AND fr.created_by = u.id
--   ORDER BY fr.<observation_time|reading_at> DESC, fr.id DESC
--   LIMIT 1
--
-- No existing index serves it. The closest, idx_%1$s_flow_scheme_creator_date
-- (scheme_id, created_by, reading_date DESC) from V21/V22, leads on scheme_id,
-- which these queries never constrain — so Postgres degrades to a full index
-- scan plus a top-N sort *per operator*.
--
-- The cost is invisible on the first page (the planner walks user_table_pkey
-- backwards and stops at LIMIT) and quadratic-looking on deep pages, where
-- OFFSET forces the lateral to run for every operator in the tenant. Measured
-- on 5,000 operators / 1,000,000 readings, all pages warm:
--
--   listReadingCompliance page 0     155 ms  ->    3.9 ms
--   listReadingCompliance page 999  4375 ms  ->  228   ms  (7.1M buffer hits -> ~5k)
--
-- Nine call sites benefit, in user-service (PublicPumpOperatorRepository) and
-- tenant-service (NudgeRepository, whose 8 AM nudge and 9 AM escalation crons
-- run this lookup once per operator every day).
--
-- Partial on deleted_at IS NULL because every one of those queries filters on
-- it, which keeps the index to the live rows only.
--
-- The timestamp column name differs by tenant vintage — V4 renamed reading_at
-- to observation_time, and schemas provisioned before it still carry the old
-- name — so the column is resolved per schema, exactly as the repositories'
-- resolveFlowReadingTimeColumn() does. The index name does not vary with it.
--
-- NOTE (ops): plain CREATE INDEX, matching V21/V35, takes a SHARE lock that
-- blocks writes to flow_reading_table while it builds. On a large production
-- tenant, build it out-of-band with CREATE INDEX CONCURRENTLY first; the
-- IF NOT EXISTS guard here then makes this migration a no-op for that schema.
-- Every related change is marked "OPERATOR-LATEST".
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
    time_column   TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing flow_reading_table is
        -- skipped for that table only, never aborting the whole migration.
        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL THEN
            SELECT CASE WHEN EXISTS (
                       SELECT 1 FROM information_schema.columns
                       WHERE table_schema = tenant_schema
                         AND table_name = 'flow_reading_table'
                         AND column_name = 'observation_time')
                   THEN 'observation_time' ELSE 'reading_at' END
              INTO time_column;

            -- OPERATOR-LATEST: backs the per-operator latest-reading LATERAL lookup.
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_flow_creator_time
                     ON %1$I.flow_reading_table(created_by, %2$I DESC, id DESC)
                     WHERE deleted_at IS NULL',
                tenant_schema, time_column);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas get the same index ────────────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34/V35/V36/V37/V39/V40): preserve the current
-- implementation once under a versioned name, then wrap it to add the OPERATOR-LATEST index.
-- The captured base therefore already includes the V40 provisioning, which must run before this
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
        WHERE p.proname = 'create_tenant_schema_v41_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v41_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
DECLARE
    time_column TEXT;
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v41_base(schema_name);

    -- OPERATOR-LATEST: per-operator latest-reading index for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        SELECT CASE WHEN EXISTS (
                   SELECT 1 FROM information_schema.columns
                   WHERE table_schema = schema_name
                     AND table_name = 'flow_reading_table'
                     AND column_name = 'observation_time')
               THEN 'observation_time' ELSE 'reading_at' END
          INTO time_column;

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_flow_creator_time
                 ON %1$I.flow_reading_table(created_by, %2$I DESC, id DESC)
                 WHERE deleted_at IS NULL',
            schema_name, time_column);
    END IF;
END;
$func$;
