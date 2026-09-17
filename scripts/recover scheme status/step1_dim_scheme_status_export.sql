-- =====================================================================================
-- STATUS RESTORE STEP 1 / 2  --  TENANT DB  (connection whose database holds tenant_<code>)
-- Pure SQL for DBeaver. This step is entirely READ-ONLY except for the one temp table
-- it builds for hand-off. Nothing in production changes here.
-- =====================================================================================
-- Why: analytics-service ran a nightly job (SchemeStatusSyncTask) that OVERWROTE
-- analytics_schema.dim_scheme_table.operating_status with a reporting-activity guess:
-- 1 if the scheme had a SUBMITTED fact_water_quantity row within the last 30 days, else 0.
-- It only ever wrote 1 or 0, so code 2 (Partially Operative) could not survive a night,
-- and whatever the state department actually recorded was replaced every midnight.
--
--   work_status       1 Ongoing, 2 Completed, 3 Not Started, 4 Handed Over
--   operating_status  0 Non-Operative, 1 Operative, 2 Partially Operative
--
-- tenant_<code>.scheme_master_table is authoritative for both — it is the column the CSV
-- upload and PATCH /scheme/schemes/{id}/status write, and the source dim_scheme_table is
-- fed from over Kafka. This step exports it; STEP 2 writes it back over the damage.
--
-- >>> ORDER MATTERS. Deploy the build that deletes SchemeStatusSyncTask BEFORE running
-- >>> STEP 2. Backfilling while that job is still scheduled means the next midnight
-- >>> undoes the entire repair.
--
-- Deliverable:  tenant_as.tmp_dim_scheme_status  -- hand-off table for STEP 2, one row per
--               scheme, each stamped with the analytics tenant it is for and the id of this
--               recovery run. STEP 2 refuses to write without both.
-- =====================================================================================

-- >>> EDIT ME (1 of 2): this script is written for ONE tenant at a time. Find-and-replace EVERY
-- >>> occurrence of tenant_as in this file with the schema you are repairing — miss one and
-- >>> the statement still runs, silently against the wrong schema. Run the pair once per tenant.

-- >>> EDIT ME (2 of 2): stamp the hand-off rows, in PART A below.
-- >>>   source_tenant_id  the ANALYTICS tenant_id (dim_scheme_table.tenant_id) that matches the
-- >>>                     schema above. This is the same value you give STEP 2 as :tenant, and
-- >>>                     STEP 2 cross-checks the two: a mismatch aborts instead of writing
-- >>>                     this tenant's statuses onto another tenant's dim rows. Replace -1.
-- >>>   recovery_run_id   a label unique to THIS run, e.g. 'as-20260907-1'. It is what tells a
-- >>>                     freshly imported staging table apart from one left behind by an
-- >>>                     earlier run or another tenant. Replace 'SET-ME'.
-- >>> Both are guarded below — leaving either at its placeholder fails STEP 1, not STEP 2.


-- ################################################################################
-- PART A  --  build the hand-off table from the authoritative tenant rows
-- ################################################################################
DROP TABLE IF EXISTS tenant_as.tmp_dim_scheme_status;

CREATE TABLE tenant_as.tmp_dim_scheme_status AS
SELECT
    sm.id                   AS scheme_id,
    sm.work_status          AS work_status,
    sm.operating_status     AS operating_status,
    (-1)::int               AS source_tenant_id,   -- >>> EDIT ME: analytics tenant_id
    'SET-ME'::text          AS recovery_run_id     -- >>> EDIT ME: unique id for this run
FROM tenant_as.scheme_master_table sm
WHERE sm.deleted_at IS NULL;

CREATE INDEX ON tenant_as.tmp_dim_scheme_status (scheme_id);

-- guard: refuse to hand over rows that are not stamped. STEP 2 keys its safety checks off
-- these two columns, so an unedited placeholder here is a silent hazard three steps later.
DO $$
DECLARE
    bad_tenant int;
    bad_run    int;
BEGIN
    SELECT count(*) INTO bad_tenant FROM tenant_as.tmp_dim_scheme_status
     WHERE source_tenant_id IS NULL OR source_tenant_id <= 0;
    IF bad_tenant > 0 THEN
        RAISE EXCEPTION 'source_tenant_id is still the -1 placeholder - set it to the analytics tenant_id (EDIT ME 2 of 2) and re-run PART A.';
    END IF;

    SELECT count(*) INTO bad_run FROM tenant_as.tmp_dim_scheme_status
     WHERE recovery_run_id IS NULL OR btrim(recovery_run_id) = '' OR recovery_run_id = 'SET-ME';
    IF bad_run > 0 THEN
        RAISE EXCEPTION 'recovery_run_id is still the SET-ME placeholder - give this run a unique label (EDIT ME 2 of 2) and re-run PART A.';
    END IF;
END $$;


-- ################################################################################
-- PART B  (read-only)  --  sanity reports before you hand this over
-- ################################################################################

-- B1. size, the identifiers stamped on every row, and the distribution STEP 2 is going to
--     restore. Read the two identifiers back before exporting: source_tenant_id must be the
--     analytics tenant_id you will give STEP 2 as :tenant. The operating_status = 2 bucket is
--     the whole point: if it is 0 here, the tenant DB itself holds no Partially Operative
--     scheme and STEP 2's verification gate cannot pass.
SELECT source_tenant_id,
       recovery_run_id,
       count(*)                                            AS schemes_exported,
       count(*) FILTER (WHERE operating_status = 0)         AS non_operative,
       count(*) FILTER (WHERE operating_status = 1)         AS operative,
       count(*) FILTER (WHERE operating_status = 2)         AS partially_operative,
       count(*) FILTER (WHERE operating_status IS NULL)     AS operating_status_null,
       count(*) FILTER (WHERE work_status IS NULL)          AS work_status_null
FROM tenant_as.tmp_dim_scheme_status
GROUP BY source_tenant_id, recovery_run_id;

-- B2. anything outside the documented code ranges is a data problem in the tenant DB,
--     not something STEP 2 should propagate. Expect 0 rows.
--     work_status = 0 is deliberately NOT flagged. It is the reserved sentinel lenient ingest
--     stores on the placeholder schemes it auto-provisions for a scheme id it cannot resolve;
--     this export carries those rows through like any other, and STEP 2's guard admits 0 for
--     the same reason. Use B2a below to see how many of them you are handing over.
SELECT scheme_id, work_status, operating_status
FROM tenant_as.tmp_dim_scheme_status
WHERE work_status      NOT BETWEEN 0 AND 4
   OR operating_status NOT BETWEEN 0 AND 2
ORDER BY scheme_id
LIMIT 50;

-- B2a. how much of the export is lenient-ingest placeholders rather than real schemes.
--      A high count is worth a look on its own — every one of these is a submission whose
--      scheme id could not be resolved — but it does not block the restore: placeholders are
--      created by telemetry-service with a direct insert and are never published to analytics,
--      so PART C finds no dim row to join them to and passes over them.
SELECT count(*) FILTER (WHERE work_status = 0) AS placeholder_rows,
       count(*) FILTER (WHERE work_status <> 0) AS real_scheme_rows
FROM tenant_as.tmp_dim_scheme_status;

-- B3. duplicate scheme_ids would make STEP 2's UPDATE ... FROM non-deterministic.
--     scheme_id is the PK of scheme_master_table, so this must return 0 rows.
SELECT scheme_id, count(*)
FROM tenant_as.tmp_dim_scheme_status
GROUP BY scheme_id
HAVING count(*) > 1;


-- >>> Next: transfer tenant_as.tmp_dim_scheme_status to the ANALYTICS db, then run
--     step2_dim_scheme_status_restore.sql.
-- >>> In DBeaver: right-click tmp_dim_scheme_status -> Export Data -> Database -> pick the
--     analytics connection. The target table is named per tenant and run, because
--     analytics_schema is shared by every tenant: STEP 2 creates
--     analytics_schema.tmp_stg_scheme_status__as_run1 after you have replaced its __as_run1
--     suffix. Create it there FIRST (STEP 2 PART A), then import into it.


-- ################################################################################
-- CLEANUP (optional, after STEP 2 has completed successfully)
-- ################################################################################
-- DROP TABLE IF EXISTS tenant_as.tmp_dim_scheme_status;
