-- ============================================================
-- V59 - Notification delivery statistics
-- ------------------------------------------------------------
-- fact_notification_delivery_table
--   One row per notification message-service sent or tried to send (nudges, escalations, reports,
--   welcome/OTP/invite mails ...), from the NOTIFICATION_DELIVERY_UPDATED event on
--   message-service-topic. notification_uuid is message-service's ledger key; status_version
--   orders the updates of one notification, and only a higher version overwrites the row, so a
--   redelivered or out-of-order event changes nothing (see FactNotificationDeliveryRepository).
--
--   tenant_id has no foreign key: NULL means a platform-level notification with no tenant, as in
--   submission_attempt_table (V40). provider, message_type, user_type and the status columns are
--   free strings so a new provider or status never needs a migration. No PII is stored.
--
--   Timestamps: created_at_source, dispatched_at, delivered_at, read_at and settled_at hold the
--   event's UTC instants converted to the IST wall clock, like fact_meter_reading_table.reading_at,
--   so they sit on the same calendar day as dispatch_date. created_at/updated_at are audit columns.
--
--   dispatch_date is the IST calendar day of dispatched_at, else of created_at_source, worked out
--   at ingest so the daily rollups group on a plain column. time_to_deliver_s is
--   delivered_at - dispatched_at, else read_at - dispatched_at, else NULL.
--
-- agg_notification_delivery_daily_table / agg_notification_failure_daily_table
--   Daily rollups of the fact table, rebuilt for a lookback window by
--   NotificationDeliveryAggregationTask. Every row is overwritten from the facts, never
--   incremented, so a re-run is idempotent. tenant_id and user_type can be NULL, hence
--   UNIQUE NULLS NOT DISTINCT (as V24): otherwise every run would add another NULL-tenant row.
-- ============================================================

CREATE TABLE IF NOT EXISTS analytics_schema.fact_notification_delivery_table (
    id                    BIGSERIAL       PRIMARY KEY,
    notification_uuid     VARCHAR(36)     NOT NULL UNIQUE,
    status_version        INTEGER         NOT NULL,
    tenant_id             INTEGER,                        -- NULL = platform-level
    message_type          VARCHAR(40)     NOT NULL,
    channel               VARCHAR(20)     NOT NULL,
    provider              VARCHAR(40)     NOT NULL,
    user_id               BIGINT,
    user_type             VARCHAR(40),
    dispatch_status       VARCHAR(32)     NOT NULL,
    failure_stage         VARCHAR(32),
    delivery_status       VARCHAR(20)     NOT NULL,
    provider_error_code   VARCHAR(64),
    created_at_source     TIMESTAMP       NOT NULL,       -- event createdAt, IST wall clock
    dispatched_at         TIMESTAMP,                      -- IST wall clock
    delivered_at          TIMESTAMP,                      -- IST wall clock
    read_at               TIMESTAMP,                      -- IST wall clock
    settled_at            TIMESTAMP,                      -- IST wall clock
    dispatch_date         DATE            NOT NULL,       -- IST day of dispatched_at, else created_at_source
    subject_date          DATE,                           -- report date / week start / nudge date
    latency_ms            INTEGER,                        -- message-service's send call duration
    time_to_deliver_s     INTEGER,
    cost_amount           NUMERIC(10,4),
    cost_currency         VARCHAR(8),
    created_at            TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- The rollups and the tenant-scoped reads filter on (tenant, day) and group by type and channel.
CREATE INDEX IF NOT EXISTS idx_fact_notification_delivery_tenant_date
    ON analytics_schema.fact_notification_delivery_table (tenant_id, dispatch_date, message_type, channel);
CREATE INDEX IF NOT EXISTS idx_fact_notification_delivery_date
    ON analytics_schema.fact_notification_delivery_table (dispatch_date);
-- Finding the notifications that failed or never settled.
CREATE INDEX IF NOT EXISTS idx_fact_notification_delivery_open_or_failed
    ON analytics_schema.fact_notification_delivery_table (delivery_status, dispatch_date)
    WHERE delivery_status IN ('FAILED', 'PENDING', 'UNRESOLVED');

COMMENT ON TABLE analytics_schema.fact_notification_delivery_table IS
    'One row per notification (message-service ledger uuid), latest status_version wins. Timestamps are IST wall clock.';

CREATE TABLE IF NOT EXISTS analytics_schema.agg_notification_delivery_daily_table (
    id                       BIGSERIAL       PRIMARY KEY,
    tenant_id                INTEGER,                     -- NULL = platform-level
    stat_date                DATE            NOT NULL,    -- fact dispatch_date
    message_type             VARCHAR(40)     NOT NULL,
    channel                  VARCHAR(20)     NOT NULL,
    provider                 VARCHAR(40)     NOT NULL,
    user_type                VARCHAR(40),
    attempted                INTEGER         NOT NULL DEFAULT 0,  -- every notification
    accepted                 INTEGER         NOT NULL DEFAULT 0,
    suppressed               INTEGER         NOT NULL DEFAULT 0,
    skipped                  INTEGER         NOT NULL DEFAULT 0,  -- SKIPPED_*
    dispatch_failed          INTEGER         NOT NULL DEFAULT 0,  -- rejected / failed / unconfirmed send
    delivered                INTEGER         NOT NULL DEFAULT 0,  -- DELIVERED or READ
    read                     INTEGER         NOT NULL DEFAULT 0,
    delivery_failed          INTEGER         NOT NULL DEFAULT 0,
    pending                  INTEGER         NOT NULL DEFAULT 0,
    unresolved               INTEGER         NOT NULL DEFAULT 0,
    not_tracked              INTEGER         NOT NULL DEFAULT 0,
    account_level_failures   INTEGER         NOT NULL DEFAULT 0,  -- failures with a configured account-level code
    distinct_users           INTEGER         NOT NULL DEFAULT 0,
    avg_latency_ms           INTEGER,
    max_latency_ms           INTEGER,
    avg_time_to_deliver_s    INTEGER,
    total_cost               NUMERIC(12,4),
    cost_currency            VARCHAR(8),
    created_at               TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_agg_notification_delivery_daily
        UNIQUE NULLS NOT DISTINCT (tenant_id, stat_date, message_type, channel, provider, user_type)
);

CREATE INDEX IF NOT EXISTS idx_agg_notification_delivery_daily_date_tenant
    ON analytics_schema.agg_notification_delivery_daily_table (stat_date, tenant_id);

COMMENT ON TABLE analytics_schema.agg_notification_delivery_daily_table IS
    'Daily notification delivery counts per tenant, message type, channel, provider and user type; rebuilt from fact_notification_delivery_table.';

CREATE TABLE IF NOT EXISTS analytics_schema.agg_notification_failure_daily_table (
    id                    BIGSERIAL       PRIMARY KEY,
    tenant_id             INTEGER,                        -- NULL = platform-level
    stat_date             DATE            NOT NULL,       -- fact dispatch_date
    message_type          VARCHAR(40)     NOT NULL,
    channel               VARCHAR(20)     NOT NULL,
    provider              VARCHAR(40)     NOT NULL,
    failure_stage         VARCHAR(32),
    provider_error_code   VARCHAR(64),
    failures              INTEGER         NOT NULL DEFAULT 0,
    created_at            TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_agg_notification_failure_daily
        UNIQUE NULLS NOT DISTINCT (tenant_id, stat_date, message_type, channel, provider,
                                   failure_stage, provider_error_code)
);

COMMENT ON TABLE analytics_schema.agg_notification_failure_daily_table IS
    'Daily count of failed notifications (delivery FAILED or a failed dispatch) per stage and provider error code; rebuilt from fact_notification_delivery_table.';
