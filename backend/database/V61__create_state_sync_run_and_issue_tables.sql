-- ============================================================
-- Migration: V60 - State master-data sync bookkeeping
-- ------------------------------------------------------------
-- scheme-service pulls a state's master data (Assam: JJM Brain) on a schedule
-- and reconciles it into the tenant schema. Two tables back that job:
--
--   state_sync_run_table    one row per run. Doubles as the cross-pod lock:
--                           the partial UNIQUE index below admits a single
--                           RUNNING row per tenant, so when several replicas
--                           fire the same cron only the first INSERT wins and
--                           the rest skip. A crashed pod's claim is taken over
--                           once its heartbeat_at is older than the configured
--                           stale timeout (the claimer flips it to ABANDONED).
--                           source_watermark is the newest upstream
--                           updated_at an APPLY run saw; the next delta pull
--                           starts from it.
--
--   state_sync_issue_table  everything a run declined to write — conflicting
--                           ids, an unmatched village code, a spared archive —
--                           so a human can work the list. Never holds phone
--                           numbers or other PII in detail.
--
-- Both live in common_schema with a tenant_id column rather than in each
-- tenant schema, so create_tenant_schema() is deliberately not touched.
-- ============================================================

CREATE TABLE IF NOT EXISTS common_schema.state_sync_run_table (
    id                BIGSERIAL     PRIMARY KEY,
    tenant_id         INTEGER       NOT NULL REFERENCES common_schema.tenant_master_table(id),
    run_kind          VARCHAR(32)   NOT NULL,   -- FULL | DELTA | SCHEME_REFRESH
    mode              VARCHAR(16)   NOT NULL,   -- DRY_RUN | APPLY
    status            VARCHAR(16)   NOT NULL,   -- RUNNING | SUCCEEDED | FAILED | ABANDONED
    triggered_by      VARCHAR(64)   NOT NULL,   -- SCHEDULER | ADMIN:<user id>
    owner             VARCHAR(255)  NOT NULL,   -- host that holds the claim
    started_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    heartbeat_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    finished_at       TIMESTAMP,
    source_watermark  TIMESTAMP,
    counts            JSONB         NOT NULL DEFAULT '{}'::jsonb,
    error             TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_state_sync_run_one_running_per_tenant
    ON common_schema.state_sync_run_table (tenant_id)
    WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS idx_state_sync_run_tenant_started
    ON common_schema.state_sync_run_table (tenant_id, started_at DESC);

CREATE TABLE IF NOT EXISTS common_schema.state_sync_issue_table (
    id             BIGSERIAL     PRIMARY KEY,
    run_id         BIGINT        NOT NULL REFERENCES common_schema.state_sync_run_table(id),
    tenant_id      INTEGER       NOT NULL REFERENCES common_schema.tenant_master_table(id),
    entity         VARCHAR(32)   NOT NULL,   -- SCHEME | USER | LGD | DEPARTMENT | ...
    upstream_code  VARCHAR(255),
    category       VARCHAR(64)   NOT NULL,
    detail         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_state_sync_issue_run
    ON common_schema.state_sync_issue_table (run_id);

CREATE INDEX IF NOT EXISTS idx_state_sync_issue_tenant_category
    ON common_schema.state_sync_issue_table (tenant_id, category, created_at DESC);
