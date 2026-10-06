-- ============================================================
-- Migration: V62 - scheduled_job_run_table
-- ------------------------------------------------------------
--   common_schema.scheduled_job_run_table
--     one row per notification job run: (job_type, tenant_id, period_key)
--
-- Every tenant-service pod checks once a minute which tenant's nudge,
-- escalation, daily report and weekly report are due. The pod that first
-- inserts a run's row runs that job; the unique key turns every other
-- pod's insert into a no-op, so each job runs once per tenant per period
-- however many pods are up.
--
-- period_key is the IST day the run is for. For WEEKLY_REPORT it is the
-- first day of the reported week, so moving the firing day within a week
-- cannot send that week's report twice.
--
-- A row is never retried: a FAILED run, or a RUNNING row left by a pod
-- that died mid-run, keeps its period. Re-sending to officers already
-- notified is worse than missing one run.
--
-- At most 4 rows per tenant per day, so the table needs no cleanup.
-- ============================================================

CREATE TABLE common_schema.scheduled_job_run_table (
    id            BIGSERIAL    PRIMARY KEY,
    job_type      VARCHAR(32)  NOT NULL,                    -- NUDGE | ESCALATION | DAILY_REPORT | WEEKLY_REPORT
    tenant_id     INTEGER      NOT NULL,
    period_key    DATE         NOT NULL,                    -- IST day; WEEKLY_REPORT: first day of reported week
    due_at_ist    TIMESTAMP    NOT NULL,                    -- the IST slot the run was due at
    status        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',  -- RUNNING | SUCCEEDED | FAILED
    claimed_by    VARCHAR(255) NOT NULL,                    -- pod host name
    claimed_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMP,
    error_message TEXT,
    CONSTRAINT uq_scheduled_job_run UNIQUE (job_type, tenant_id, period_key),
    CONSTRAINT fk_scheduled_job_run_tenant
        FOREIGN KEY (tenant_id) REFERENCES common_schema.tenant_master_table(id)
);
