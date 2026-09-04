-- Record the real status vocabulary on dim_scheme_table as column comments.
--
-- CORRECTION. The V39 header documents operating_status as:
--
--     0   => INACTIVE
--     > 0 => ACTIVE
--
-- That was never true of the source column. operating_status has three codes, not two, and
-- "active/inactive" was a derived binary layered on top of it: a nightly job rewrote every row to
-- 1 or 0 from reporting recency, and the read path collapsed whatever survived into
-- active/inactive. Both have been removed, so the column now means exactly what it stores.
--
-- V39 has already run everywhere, and editing a shipped migration would break its Flyway checksum,
-- so the correction lands here instead. Comments live in the catalog, which puts the vocabulary
-- where \d+ and any schema browser will show it.
--
-- Codes are the same in analytics_schema.dim_scheme_table and the per-tenant
-- scheme_master_table it is ingested from, and are mirrored in Java by SchemeWorkStatus and
-- SchemeOperatingStatus (copied verbatim into analytics-, scheme- and user-service, with
-- SchemeStatusVocabularyTest pinning each copy). Change the codes here, change them there.

COMMENT ON COLUMN analytics_schema.dim_scheme_table.work_status IS
    'Construction workflow state. 1 = Ongoing, 2 = Completed, 3 = Not Started, 4 = Handed Over. '
    'NULL means not recorded and is served as "Unknown". Dashboard aggregates additionally restrict '
    'to the effective included_work_statuses set (dim_tenant_table, tenant-0 default, then the '
    'analytics.dashboard.included-work-statuses env default).';

COMMENT ON COLUMN analytics_schema.dim_scheme_table.operating_status IS
    'Current operating state. 0 = Non-Operative, 1 = Operative, 2 = Partially Operative. '
    'NULL means not recorded and is served as "Unknown". This is NOT an active/inactive flag: '
    'code 2 exists precisely because the two-value reading was wrong, and nothing filters on it.';
