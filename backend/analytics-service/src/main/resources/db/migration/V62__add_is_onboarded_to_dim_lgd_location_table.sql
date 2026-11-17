-- ============================================================
-- V62 - Phased rollout: the districts that are live on the dashboards
-- ------------------------------------------------------------
-- dim_lgd_location_table
--   + is_onboarded  BOOLEAN NOT NULL DEFAULT FALSE  -- the district is live on the dashboards
--
-- Read only when PHASED_ROLLOUT_ENABLED=true (RegionOnboardingRepository). A region below the state is
-- then reachable only if it has a dim_scheme_table row whose level_2_lgd_id district is onboarded.
-- With the switch off nothing reads the column, so every existing FALSE row is inert.
--
-- FALSE by default so that a district loaded or reloaded during a pilot stays hidden until ops onboard
-- it. Only district rows (lgd_level = 2) can be onboarded; the CHECK enforces that.
--
-- Set by ops with SQL (docs/phased-rollout.md), never by the service: DimLgdLocation deliberately does
-- not map it, so a Kafka upsert of the row leaves it alone.
--
-- A constant default, so adding the column does not rewrite the table. The CHECK is added NOT VALID, so
-- it holds the ACCESS EXCLUSIVE lock without scanning the table; it still applies to every write from
-- here on. V63 validates the existing rows in its own transaction, once this one has committed.
-- ============================================================

ALTER TABLE analytics_schema.dim_lgd_location_table
    ADD COLUMN IF NOT EXISTS is_onboarded BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE analytics_schema.dim_lgd_location_table
    ADD CONSTRAINT ck_dim_lgd_location_is_onboarded_district_only
        CHECK (NOT is_onboarded OR lgd_level = 2) NOT VALID;

COMMENT ON COLUMN analytics_schema.dim_lgd_location_table.is_onboarded IS
    'Phased rollout: district (lgd_level = 2) is live on the dashboards. Read only when PHASED_ROLLOUT_ENABLED=true.';
