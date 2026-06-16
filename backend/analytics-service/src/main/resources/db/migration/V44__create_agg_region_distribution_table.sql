-- ============================================================
-- AGG REGION DISTRIBUTION (long-format distribution KPIs)
-- ============================================================
-- Serves the Map<String,Integer> dashboard cards (outage reasons, non-submission
-- reasons, submission-status pie, scheme-status counts) without schema churn when
-- the set of reason/status codes changes. One row per
-- (period bucket, region node, dist_type, dist_key).
-- ============================================================

CREATE TABLE analytics_schema.agg_region_distribution (
    id              BIGSERIAL   PRIMARY KEY,
    period_scale    VARCHAR(8)  NOT NULL,   -- DAY | WEEK | MONTH
    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,
    tenant_id       INT         NOT NULL,
    hierarchy       VARCHAR(8)  NOT NULL,   -- LGD | DEPT
    region_level    SMALLINT    NOT NULL,   -- 1..6
    region_id       INT         NOT NULL,
    dist_type       VARCHAR(32) NOT NULL,   -- OUTAGE_REASON | NON_SUBMISSION_REASON | SUBMISSION_STATUS | SCHEME_STATUS
    dist_key        VARCHAR(64) NOT NULL,
    scheme_count    INT         NOT NULL DEFAULT 0,
    computed_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_final        BOOLEAN     NOT NULL DEFAULT FALSE,

    -- region_id is only unique within a tenant, so tenant_id is part of the key.
    CONSTRAINT uq_agg_region_distribution
        UNIQUE (period_scale, period_start, tenant_id, hierarchy, region_level, region_id, dist_type, dist_key)
);

CREATE INDEX IF NOT EXISTS idx_agg_region_distribution_lookup
    ON analytics_schema.agg_region_distribution(period_scale, tenant_id, hierarchy, region_level, region_id, period_start, dist_type);
