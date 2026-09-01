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
-- Deliverable:  tenant_as.tmp_dim_scheme_status  -- hand-off table for STEP 2
-- =====================================================================================

-- >>> EDIT ME: this script is written for ONE tenant at a time. Find-and-replace EVERY
-- >>> occurrence of tenant_as in this file with the schema you are repairing — miss one and
-- >>> the statement still runs, silently against the wrong schema. Use the matching analytics
-- >>> tenant_id as :tenant in STEP 2. Run the pair once per tenant.


-- ################################################################################
-- PART A  --  build the hand-off table from the authoritative tenant rows
-- ################################################################################
DROP TABLE IF EXISTS tenant_as.tmp_dim_scheme_status;

CREATE TABLE tenant_as.tmp_dim_scheme_status AS
SELECT
    sm.id               AS scheme_id,
    sm.work_status      AS work_status,
    sm.operating_status AS operating_status
FROM tenant_as.scheme_master_table sm
WHERE sm.deleted_at IS NULL;

CREATE INDEX ON tenant_as.tmp_dim_scheme_status (scheme_id);


-- ################################################################################
-- PART B  (read-only)  --  sanity reports before you hand this over
-- ################################################################################

-- B1. size, and the distribution STEP 2 is going to restore. The operating_status = 2
--     bucket is the whole point: if it is 0 here, the tenant DB itself holds no
--     Partially Operative scheme and STEP 2's verification gate cannot pass.
SELECT count(*)                                            AS schemes_exported,
       count(*) FILTER (WHERE operating_status = 0)         AS non_operative,
       count(*) FILTER (WHERE operating_status = 1)         AS operative,
       count(*) FILTER (WHERE operating_status = 2)         AS partially_operative,
       count(*) FILTER (WHERE operating_status IS NULL)     AS operating_status_null,
       count(*) FILTER (WHERE work_status IS NULL)          AS work_status_null
FROM tenant_as.tmp_dim_scheme_status;

-- B2. anything outside the documented code ranges is a data problem in the tenant DB,
--     not something STEP 2 should propagate. Expect 0 rows.
SELECT scheme_id, work_status, operating_status
FROM tenant_as.tmp_dim_scheme_status
WHERE work_status      NOT BETWEEN 1 AND 4
   OR operating_status NOT BETWEEN 0 AND 2
ORDER BY scheme_id
LIMIT 50;

-- B3. duplicate scheme_ids would make STEP 2's UPDATE ... FROM non-deterministic.
--     scheme_id is the PK of scheme_master_table, so this must return 0 rows.
SELECT scheme_id, count(*)
FROM tenant_as.tmp_dim_scheme_status
GROUP BY scheme_id
HAVING count(*) > 1;


-- >>> Next: transfer tenant_as.tmp_dim_scheme_status to the ANALYTICS db, then run
--     step2_dim_scheme_status_restore.sql.
-- >>> In DBeaver: right-click tmp_dim_scheme_status -> Export Data -> Database ->
--     pick the analytics connection, target table analytics_schema.tmp_stg_scheme_status.


-- ################################################################################
-- CLEANUP (optional, after STEP 2 has completed successfully)
-- ################################################################################
-- DROP TABLE IF EXISTS tenant_as.tmp_dim_scheme_status;
