-- ============================================================
-- Migration: V43 - Link an anomaly to the submission that caused it
-- ------------------------------------------------------------
-- ANOMALY-SUBMISSION-LINK
--
-- Until now an anomaly was associated with an operator, a scheme and a
-- timestamp, but never with the flow_reading_table row it was raised over.
-- The two were matched on (user_id, scheme_id, type, created_at) — a
-- heuristic, not a key — so no dashboard could open the photo or the value
-- behind an anomaly, and nothing could tell two submissions on the same day
-- apart.
--
--   anomaly_table
--     + flow_reading_id  INTEGER  NULL  REFERENCES flow_reading_table(id)
--
-- Why the surrogate id and not correlation_id, which flow_reading_table
-- already carries:
--
--   1. correlation_id is MUTABLE. createIssueReportRecord() reuses an
--      existing same-day row and overwrites its correlation_id, which would
--      orphan any anomaly already pointing at the old value.
--   2. It is NOT UNIQUE and not ours. The value is FlowVision's requestId
--      or a client-supplied request.correlationId, under a plain index with
--      no unique constraint, and the Glific flows deliberately COPY one
--      row's correlation_id onto later events so a WhatsApp conversation
--      keeps a single thread id. Many rows, one value, by design.
--   3. It is OVERLOADED as a state marker — 'scheme-selection-',
--      'issue-report-', 'manual-', 'location-', 'previous-day-' prefixes are
--      matched with LIKE by findLatestPendingSchemeSelectionForDate().
--
--   id is immutable, unique and FK-enforceable, and createFlowReading()
--   already returns it at every anomaly call site.
--
-- NULLable, and deliberately so: five of the ten anomaly types have no
-- submission behind them at all. Types 1 (UNREADABLE_IMAGE), 4
-- (DUPLICATE_IMAGE_SUBMISSION) and 5 (READING_LESS_THAN_PREVIOUS) are raised
-- when a submission arrived but was rejected before any row was inserted;
-- types 6 (NO_WATER_SUPPLY) and 9 (NO_SUBMISSION) come from the issue-report
-- menu, where nothing was submitted; type 3 (CONSECUTIVE_OVERRIDE_5_DAYS) is
-- an aggregate over days with no single row to point at. A NOT NULL column
-- would make those anomalies unwritable.
--
-- ON DELETE SET NULL rather than the default RESTRICT: readings are
-- soft-deleted (deleted_at) so this should never fire, but if a row is ever
-- hard-deleted the anomaly must survive without its pointer. Losing the link
-- beats losing the anomaly — the same rule the insert path already follows
-- when it skips columns a schema does not have.
--
-- Every related change is marked "ANOMALY-SUBMISSION-LINK".
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing either table is skipped
        -- for that table only, never aborting the whole migration.
        IF to_regclass(format('%I.anomaly_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.anomaly_table
                     ADD COLUMN IF NOT EXISTS flow_reading_id INTEGER',
                tenant_schema);

            -- The FK needs flow_reading_table to exist; the column itself does not, so it is added
            -- either way and only the constraint is conditional.
            IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL
               AND NOT EXISTS (
                   SELECT 1
                   FROM pg_constraint c
                   JOIN pg_class t     ON t.oid = c.conrelid
                   JOIN pg_namespace n ON n.oid = t.relnamespace
                   WHERE n.nspname = tenant_schema
                     AND t.relname = 'anomaly_table'
                     AND c.conname = 'fk_anomaly_flow_reading'
               ) THEN
                EXECUTE format(
                    'ALTER TABLE %1$I.anomaly_table
                         ADD CONSTRAINT fk_anomaly_flow_reading
                         FOREIGN KEY (flow_reading_id)
                         REFERENCES %1$I.flow_reading_table(id)
                         ON DELETE SET NULL',
                    tenant_schema);
            END IF;

            -- Partial index: the reverse lookup ("which anomalies were raised over this
            -- submission?") only ever asks about linked rows, and the majority of rows are the
            -- no-submission types that keep NULL here.
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_anom_flow_reading
                     ON %1$I.anomaly_table(flow_reading_id)
                     WHERE flow_reading_id IS NOT NULL',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34/V35/V36/V37/V39/V40/V41/V42): preserve the current
-- implementation once under a versioned name, then wrap it to add the ANOMALY-SUBMISSION-LINK
-- column. The captured base therefore already includes the V42 provisioning, which must run first.
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
        WHERE p.proname = 'create_tenant_schema_v43_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v43_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v43_base(schema_name);

    -- ANOMALY-SUBMISSION-LINK: submission pointer for new tenant schemas.
    IF to_regclass(format('%I.anomaly_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.anomaly_table
                 ADD COLUMN IF NOT EXISTS flow_reading_id INTEGER',
            schema_name);

        IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
               FROM pg_constraint c
               JOIN pg_class t     ON t.oid = c.conrelid
               JOIN pg_namespace n ON n.oid = t.relnamespace
               WHERE n.nspname = schema_name
                 AND t.relname = 'anomaly_table'
                 AND c.conname = 'fk_anomaly_flow_reading'
           ) THEN
            EXECUTE format(
                'ALTER TABLE %1$I.anomaly_table
                     ADD CONSTRAINT fk_anomaly_flow_reading
                     FOREIGN KEY (flow_reading_id)
                     REFERENCES %1$I.flow_reading_table(id)
                     ON DELETE SET NULL',
                schema_name);
        END IF;

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_anom_flow_reading
                 ON %1$I.anomaly_table(flow_reading_id)
                 WHERE flow_reading_id IS NOT NULL',
            schema_name);
    END IF;
END;
$func$;
