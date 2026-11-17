-- ============================================================
-- V63 - Validate V62's district-only CHECK on dim_lgd_location_table.is_onboarded
-- ------------------------------------------------------------
-- V62 adds ck_dim_lgd_location_is_onboarded_district_only NOT VALID. Validating it here scans the
-- existing rows under a SHARE UPDATE EXCLUSIVE lock, so the dashboards' reads and the Kafka upserts keep
-- running. Every existing row passes, since V62 gave each one is_onboarded = FALSE.
--
-- Keep this out of V62: Flyway runs each migration in its own transaction (spring.flyway.group is
-- off), which is what lets V62's ACCESS EXCLUSIVE lock be released before this scan.
-- ============================================================

ALTER TABLE analytics_schema.dim_lgd_location_table
    VALIDATE CONSTRAINT ck_dim_lgd_location_is_onboarded_district_only;
