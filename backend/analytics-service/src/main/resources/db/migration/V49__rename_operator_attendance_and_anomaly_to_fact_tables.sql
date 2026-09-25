-- ============================================================
-- V49 - Name the operator attendance and anomaly tables as facts
-- ------------------------------------------------------------
-- Both tables record events measured against the dimensions rather than
-- describe an entity:
--
--   dim_operator_attendance_table  one row per operator, scheme and day,
--                                  keyed to dim_date, dim_user, dim_scheme
--                                  and dim_tenant
--   anomaly_table                  one row per anomaly, written beside
--                                  fact_escalation_table
--
-- so both take the fact_ prefix, with the objects named after them:
--
--   dim_operator_attendance_table        -> fact_operator_attendance_table
--     dim_operator_attendance_table_*    -> fact_operator_attendance_table_*
--                                           (id_seq, pkey, *_fkey)
--     idx_dim_op_attendance_*            -> idx_fact_op_attendance_*
--   anomaly_table                        -> fact_anomaly_table
--     anomaly_table_*                    -> fact_anomaly_table_*
--                                           (id_seq, pkey, uuid_key)
--     idx_anomaly_*                      -> idx_fact_anomaly_*
--
-- Only analytics_schema changes. tenant_<code>.anomaly_table is the
-- operational table telemetry-service and anomaly-service write, and
-- keeps its name.
--
-- RENAME touches the catalogue only, so no table is rewritten.
--
-- analytics-service runs this at startup while the pod it replaces is
-- still serving. A view under each old name keeps that pod's reads, and
-- its Kafka consumers' inserts and updates, working until it stops: each
-- is a plain SELECT * of one table, so PostgreSQL makes it updatable, and
-- the table's id default and unique keys apply through it. A later
-- release drops both views once no pod older than this migration runs.
-- ============================================================

-- ── Part A: Rename the tables ───────────────────────────────────────────────
ALTER TABLE analytics_schema.dim_operator_attendance_table RENAME TO fact_operator_attendance_table;
ALTER TABLE analytics_schema.anomaly_table RENAME TO fact_anomaly_table;

-- ── Part B: Rename the constraints PostgreSQL named after each table ────────
-- Found by prefix, so a key or foreign key an environment lacks is simply not
-- there to rename. Renaming a primary or unique key renames its index too.
DO $$
DECLARE
    con RECORD;
BEGIN
    FOR con IN
        SELECT conname, replace(conname, 'dim_operator_attendance_table_', 'fact_operator_attendance_table_') AS new_name
        FROM pg_constraint
        WHERE conrelid = 'analytics_schema.fact_operator_attendance_table'::regclass
          AND starts_with(conname, 'dim_operator_attendance_table_')
    LOOP
        EXECUTE format('ALTER TABLE analytics_schema.fact_operator_attendance_table RENAME CONSTRAINT %I TO %I',
                       con.conname, con.new_name);
    END LOOP;

    FOR con IN
        SELECT conname, 'fact_' || conname AS new_name
        FROM pg_constraint
        WHERE conrelid = 'analytics_schema.fact_anomaly_table'::regclass
          AND starts_with(conname, 'anomaly_table_')
    LOOP
        EXECUTE format('ALTER TABLE analytics_schema.fact_anomaly_table RENAME CONSTRAINT %I TO %I',
                       con.conname, con.new_name);
    END LOOP;
END $$;

-- ── Part C: Rename the id sequences and the hand-named indexes ──────────────
ALTER SEQUENCE IF EXISTS analytics_schema.dim_operator_attendance_table_id_seq
    RENAME TO fact_operator_attendance_table_id_seq;
ALTER INDEX IF EXISTS analytics_schema.idx_dim_op_attendance_tenant_id RENAME TO idx_fact_op_attendance_tenant_id;
ALTER INDEX IF EXISTS analytics_schema.idx_dim_op_attendance_date_key  RENAME TO idx_fact_op_attendance_date_key;
ALTER INDEX IF EXISTS analytics_schema.idx_dim_op_attendance_user_id   RENAME TO idx_fact_op_attendance_user_id;
ALTER INDEX IF EXISTS analytics_schema.idx_dim_op_attendance_scheme_id RENAME TO idx_fact_op_attendance_scheme_id;

ALTER SEQUENCE IF EXISTS analytics_schema.anomaly_table_id_seq RENAME TO fact_anomaly_table_id_seq;
ALTER INDEX IF EXISTS analytics_schema.idx_anomaly_tenant          RENAME TO idx_fact_anomaly_tenant;
ALTER INDEX IF EXISTS analytics_schema.idx_anomaly_scheme          RENAME TO idx_fact_anomaly_scheme;
ALTER INDEX IF EXISTS analytics_schema.idx_anomaly_type            RENAME TO idx_fact_anomaly_type;
ALTER INDEX IF EXISTS analytics_schema.idx_anomaly_corr            RENAME TO idx_fact_anomaly_corr;
ALTER INDEX IF EXISTS analytics_schema.idx_anomaly_submission_corr RENAME TO idx_fact_anomaly_submission_corr;

-- ── Part D: Keep the old names working for the pod being replaced ───────────
CREATE VIEW analytics_schema.dim_operator_attendance_table AS
    SELECT * FROM analytics_schema.fact_operator_attendance_table;
COMMENT ON VIEW analytics_schema.dim_operator_attendance_table IS
    'Deprecated rollout bridge from V49 for pods that predate it; use fact_operator_attendance_table. Dropped in a later release.';

CREATE VIEW analytics_schema.anomaly_table AS
    SELECT * FROM analytics_schema.fact_anomaly_table;
COMMENT ON VIEW analytics_schema.anomaly_table IS
    'Deprecated rollout bridge from V49 for pods that predate it; use fact_anomaly_table. Dropped in a later release.';
