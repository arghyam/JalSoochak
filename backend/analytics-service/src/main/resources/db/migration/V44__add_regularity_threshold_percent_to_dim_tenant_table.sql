-- Per-tenant scheme-regularity threshold: the percentage of days in a window on which a scheme must
-- supply water to be classified "regular" in dashboards.
--
-- Deliberately no DEFAULT: NULL means "not configured" and is what makes the three-tier fallback in
-- RegularityThresholdFilter work (own tenant -> tenant-0 national default -> analytics env default).
-- A DEFAULT here would make every existing tenant look explicitly configured and permanently shadow
-- the national default.
ALTER TABLE analytics_schema.dim_tenant_table
    ADD COLUMN IF NOT EXISTS regularity_threshold_percent NUMERIC(5,2);
