-- ============================================================
-- V40 - submission_attempt_table  (REPORTED-METRIC)
-- ------------------------------------------------------------
-- Captures meter-reading submissions that were REJECTED before any
-- reading or anomaly row was written (bean-validation, invalid API key,
-- and other pre-processing rejects in telemetry-service). These leave no
-- trace in fact_meter_reading or anomaly_table, so a scheme whose only
-- submission that day was such a reject is invisible to the dashboards.
--
-- This table lets the "reported" scheme counts include those attempts.
-- Populated by analytics-service from the SUBMISSION_REJECTED Kafka event.
-- Every part of this feature is tagged REPORTED-METRIC so it can be
-- located and reverted:  grep -rn "REPORTED-METRIC" backend/
-- ============================================================

CREATE TABLE IF NOT EXISTS analytics_schema.submission_attempt_table (
    id                          BIGSERIAL       PRIMARY KEY,
    tenant_id                   INTEGER,
    scheme_id                   INTEGER,            -- resolved to our scheme_id; NULL if the submitted id is unknown
    submitted_state_scheme_id   VARCHAR(255),       -- raw payload value (for reconciliation)
    submitted_centre_scheme_id  VARCHAR(255),       -- raw payload value (for reconciliation)
    phone_hash                  VARCHAR(64),        -- HMAC of the submitted phone (no raw PII)
    reason                      VARCHAR(255),       -- why it was rejected
    attempted_at                TIMESTAMP,          -- when the submission was attempted/rejected (from the event)
    created_at                  TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Filtered by (tenant, day) and joined by scheme_id in the reported-scheme KPIs.
CREATE INDEX IF NOT EXISTS idx_submission_attempt_tenant_attempted
    ON analytics_schema.submission_attempt_table (tenant_id, attempted_at);
CREATE INDEX IF NOT EXISTS idx_submission_attempt_scheme
    ON analytics_schema.submission_attempt_table (scheme_id)
    WHERE scheme_id IS NOT NULL;
