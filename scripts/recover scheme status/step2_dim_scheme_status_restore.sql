-- =====================================================================================
-- STATUS RESTORE STEP 2 / 2  --  ANALYTICS DB  (connection whose database holds analytics_schema)
-- Pure SQL for DBeaver. PART A + B are read-only. PART C writes to a production table.
-- SAFETY: run in DBeaver "Manual Commit" mode, review PART B, run PART D, then Commit / Rollback.
-- =====================================================================================
-- Input:  analytics_schema.tmp_stg_scheme_status  -- exported by step1_dim_scheme_status_export.sql
--         from tenant_<code>.scheme_master_table, which is authoritative for both columns.
--
-- What this repairs: dim_scheme_table.operating_status and .work_status, which
-- SchemeStatusSyncTask flattened to 1/0 nightly from reporting activity. See STEP 1's
-- header for the full account.
--
-- >>> PRECONDITION. The build that deletes SchemeStatusSyncTask must ALREADY BE DEPLOYED.
-- >>> If that job is still scheduled, the next midnight silently undoes everything below.
-- >>> Confirm with: no "Scheduler START 'scheme-status-sync'" line in analytics-service
-- >>> logs since the deploy.
--
-- What this does NOT do: it does not add or remove rows, and it does not touch any column
-- other than the two statuses (+ updated_at). Row-set shape is a separate concern; see
-- step2_dim_scheme_repair.sql for the attribute-drift repair.
--
-- A scheme holds one dim row per (village, sub-division). work_status and operating_status
-- are SCHEME-level attributes, so they are written to every one of the scheme's rows.
-- =====================================================================================

\if :{?tenant}
\else
  \set tenant 1
\endif


-- ################################################################################
-- PART A  (run ONCE)  --  create the hand-off staging table, then import into it
-- ################################################################################
DROP TABLE IF EXISTS analytics_schema.tmp_stg_scheme_status;
CREATE TABLE analytics_schema.tmp_stg_scheme_status (
    scheme_id        int PRIMARY KEY,
    work_status      int,
    operating_status int
);
-- >>> Now use DBeaver to import tenant_<code>.tmp_dim_scheme_status into this table
--     (right-click the tenant-side table -> Export Data -> Database -> this table),
--     then continue with PART B.

-- sanity check after import:
-- SELECT count(*) FROM analytics_schema.tmp_stg_scheme_status;


-- ################################################################################
-- PART B  (read-only preview)  --  what PART C would change. Nothing is written.
-- ################################################################################

-- guard: staging must be present and populated
DO $$
BEGIN
    IF to_regclass('analytics_schema.tmp_stg_scheme_status') IS NULL THEN
        RAISE EXCEPTION 'analytics_schema.tmp_stg_scheme_status not found - run PART A and import first.';
    END IF;
    IF (SELECT count(*) FROM analytics_schema.tmp_stg_scheme_status) = 0 THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status is empty - the STEP 1 export was not imported.';
    END IF;
END $$;

-- B1. the damage, stated plainly: what analytics holds now vs what the tenant DB says.
--     Expect operating_status to be almost entirely 1/0 with an empty or near-empty 2
--     bucket — that is the nightly flattening.
SELECT 'analytics (now)' AS source, operating_status, count(*) AS dim_rows,
       count(DISTINCT scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table
WHERE tenant_id = :tenant
GROUP BY operating_status
UNION ALL
SELECT 'tenant (authoritative)', operating_status, NULL, count(*)
FROM analytics_schema.tmp_stg_scheme_status
GROUP BY operating_status
ORDER BY source, operating_status;

-- B2. dim rows with no counterpart in the tenant DB. These are schemes the tenant no longer
--     has (or never had) — PART C leaves them untouched. Investigate a non-zero count.
SELECT count(*) AS dim_rows_without_a_tenant_scheme,
       count(DISTINCT d.scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table d
LEFT JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
  AND r.scheme_id IS NULL;

-- B3. how many rows each column would change
SELECT
    count(*) FILTER (WHERE d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status))           AS work_status_rows,
    count(*) FILTER (WHERE d.work_status IS NULL AND r.work_status IS NOT NULL)                                  AS work_status_null_restored,
    count(*) FILTER (WHERE d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status)) AS operating_status_rows,
    count(*) FILTER (WHERE d.operating_status <> 2 AND r.operating_status = 2)                                   AS partially_operative_restored
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant;

-- B4. sample of the rows that would change, old -> new (first 30)
SELECT d.scheme_id, d.id,
       d.work_status      AS work_now,  r.work_status      AS work_new,
       d.operating_status AS oper_now,  r.operating_status AS oper_new
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status))
ORDER BY d.scheme_id, d.id
LIMIT 30;


-- ################################################################################
-- PART C  (WRITES)  --  apply. Review PART B first; run under Manual Commit.
-- ################################################################################

-- C1. keep a before-image so the change is reversible without a full restore
DROP TABLE IF EXISTS analytics_schema.tmp_scheme_status_backup;
CREATE TABLE analytics_schema.tmp_scheme_status_backup AS
SELECT d.id, d.tenant_id, d.scheme_id, d.work_status, d.operating_status, d.updated_at
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status));

SELECT count(*) AS rows_backed_up FROM analytics_schema.tmp_scheme_status_backup;

-- C2. write the tenant's statuses onto every one of the scheme's dim rows.
--     COALESCE keeps whatever we already hold when the tenant value is unknown, so an
--     absent status never overwrites a good value with NULL.
UPDATE analytics_schema.dim_scheme_table d
SET work_status      = COALESCE(r.work_status,      d.work_status),
    operating_status = COALESCE(r.operating_status, d.operating_status),
    updated_at       = NOW()
FROM analytics_schema.tmp_stg_scheme_status r
WHERE d.tenant_id = :tenant
  AND d.scheme_id = r.scheme_id
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status));


-- ################################################################################
-- PART D  (read-only verification)  --  run BEFORE you commit.
-- ################################################################################

-- D1. THE GATE. A non-zero operating_status = 2 bucket is what proves the repair took:
--     that bucket cannot survive a night while SchemeStatusSyncTask is scheduled, so a
--     non-zero count here (and again tomorrow) proves nothing is re-flattening it.
--     Skip this check only if STEP 1's B1 showed the tenant DB itself has no code 2.
SELECT operating_status, count(*) AS dim_rows, count(DISTINCT scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table
WHERE tenant_id = :tenant
GROUP BY operating_status
ORDER BY operating_status;

-- D2. analytics must now agree with the tenant DB for every scheme it shares.
--     Both columns must return 0.
SELECT count(*) FILTER (WHERE d.work_status      IS DISTINCT FROM r.work_status)      AS work_status_still_differs,
       count(*) FILTER (WHERE d.operating_status IS DISTINCT FROM r.operating_status) AS operating_status_still_differs
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant;

-- D3. per-scheme drift: every row of a repaired scheme must carry the same status.
--     Scoped to schemes present in staging — a scheme the tenant DB no longer has was
--     deliberately left untouched by PART C, so its pre-existing drift is not this
--     script's to answer for. Must return 0 rows.
SELECT d.scheme_id,
       count(DISTINCT d.work_status)      AS distinct_work_status,
       count(DISTINCT d.operating_status) AS distinct_operating_status
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
GROUP BY d.scheme_id
HAVING count(DISTINCT d.work_status) > 1
    OR count(DISTINCT d.operating_status) > 1
LIMIT 50;

-- >>> Review D1-D3, then COMMIT. Re-run D1 the following day: if the code 2 bucket has
-- >>> collapsed to 0 overnight, a SchemeStatusSyncTask is still running somewhere.


-- ################################################################################
-- ROLLBACK (after commit)  --  restore the before-image captured in C1
-- ################################################################################
-- UPDATE analytics_schema.dim_scheme_table d
-- SET work_status      = b.work_status,
--     operating_status = b.operating_status,
--     updated_at       = b.updated_at
-- FROM analytics_schema.tmp_scheme_status_backup b
-- WHERE d.id = b.id;


-- ################################################################################
-- CLEANUP (optional, once the repair is confirmed stable)
-- ################################################################################
-- DROP TABLE IF EXISTS analytics_schema.tmp_stg_scheme_status;
-- DROP TABLE IF EXISTS analytics_schema.tmp_scheme_status_backup;

-- =====================================================================================
-- STATUS RESTORE STEP 2 / 2  --  ANALYTICS DB  (connection whose database holds analytics_schema)
-- Pure SQL for DBeaver. PART A + B are read-only. PART C writes to a production table.
-- SAFETY: run in DBeaver "Manual Commit" mode, review PART B, run PART D, then Commit / Rollback.
-- =====================================================================================
-- Input:  analytics_schema.tmp_stg_scheme_status  -- exported by step1_dim_scheme_status_export.sql
--         from tenant_<code>.scheme_master_table, which is authoritative for both columns.
--
-- What this repairs: dim_scheme_table.operating_status and .work_status, which
-- SchemeStatusSyncTask flattened to 1/0 nightly from reporting activity. See STEP 1's
-- header for the full account.
--
-- >>> PRECONDITION. The build that deletes SchemeStatusSyncTask must ALREADY BE DEPLOYED.
-- >>> If that job is still scheduled, the next midnight silently undoes everything below.
-- >>> Confirm with: no "Scheduler START 'scheme-status-sync'" line in analytics-service
-- >>> logs since the deploy.
--
-- What this does NOT do: it does not add or remove rows, and it does not touch any column
-- other than the two statuses (+ updated_at). Row-set shape is a separate concern; see
-- step2_dim_scheme_repair.sql for the attribute-drift repair.
--
-- A scheme holds one dim row per (village, sub-division). work_status and operating_status
-- are SCHEME-level attributes, so they are written to every one of the scheme's rows.
-- =====================================================================================

-- >>> EDIT ME: :tenant is the analytics tenant_id matching the tenant schema STEP 1 exported.
-- >>> There is no default — DBeaver prompts for it on the first statement that uses it
-- >>> (SQL Editor > Preferences > SQL Processing > Parameters > "Enable parameters in queries"
-- >>> must be on), or bind it under SQL Editor > Query Parameter Bindings. Confirm the value
-- >>> you enter is the tenant you mean: every PART B/C/D statement is scoped by it, and a
-- >>> wrong id silently writes another tenant's dim rows.


-- ################################################################################
-- PART A  (run ONCE)  --  create the hand-off staging table, then import into it
-- ################################################################################
DROP TABLE IF EXISTS analytics_schema.tmp_stg_scheme_status;
CREATE TABLE analytics_schema.tmp_stg_scheme_status (
    scheme_id        int PRIMARY KEY,
    work_status      int,
    operating_status int
);
-- >>> Now use DBeaver to import tenant_<code>.tmp_dim_scheme_status into this table
--     (right-click the tenant-side table -> Export Data -> Database -> this table),
--     then continue with PART B.

-- sanity check after import:
-- SELECT count(*) FROM analytics_schema.tmp_stg_scheme_status;


-- ################################################################################
-- PART B  (read-only preview)  --  what PART C would change. Nothing is written.
-- ################################################################################

-- guard: staging must be present and populated
DO $$
BEGIN
    IF to_regclass('analytics_schema.tmp_stg_scheme_status') IS NULL THEN
        RAISE EXCEPTION 'analytics_schema.tmp_stg_scheme_status not found - run PART A and import first.';
    END IF;
    IF (SELECT count(*) FROM analytics_schema.tmp_stg_scheme_status) = 0 THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status is empty - the STEP 1 export was not imported.';
    END IF;

    -- STEP 1's B2 reports out-of-range codes but cannot stop you importing them. PART C would
    -- write whatever is staged straight onto dim_scheme_table, so refuse here instead. NULL is
    -- legitimate and deliberately allowed: PART C COALESCEs it to the value already held.
    IF EXISTS (SELECT 1 FROM analytics_schema.tmp_stg_scheme_status
               WHERE work_status IS NOT NULL AND work_status NOT BETWEEN 1 AND 4) THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status holds work_status outside 1..4 - fix the export before running PART C. Offenders: %',
            (SELECT string_agg(format('scheme_id=%s work_status=%s', scheme_id, work_status), ', ')
             FROM (SELECT scheme_id, work_status FROM analytics_schema.tmp_stg_scheme_status
                   WHERE work_status IS NOT NULL AND work_status NOT BETWEEN 1 AND 4
                   ORDER BY scheme_id LIMIT 10) s);
    END IF;

    IF EXISTS (SELECT 1 FROM analytics_schema.tmp_stg_scheme_status
               WHERE operating_status IS NOT NULL AND operating_status NOT BETWEEN 0 AND 2) THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status holds operating_status outside 0..2 - fix the export before running PART C. Offenders: %',
            (SELECT string_agg(format('scheme_id=%s operating_status=%s', scheme_id, operating_status), ', ')
             FROM (SELECT scheme_id, operating_status FROM analytics_schema.tmp_stg_scheme_status
                   WHERE operating_status IS NOT NULL AND operating_status NOT BETWEEN 0 AND 2
                   ORDER BY scheme_id LIMIT 10) s);
    END IF;
END $$;

-- B1. the damage, stated plainly: what analytics holds now vs what the tenant DB says.
--     Expect operating_status to be almost entirely 1/0 with an empty or near-empty 2
--     bucket — that is the nightly flattening.
SELECT 'analytics (now)' AS source, operating_status, count(*) AS dim_rows,
       count(DISTINCT scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table
WHERE tenant_id = :tenant
GROUP BY operating_status
UNION ALL
SELECT 'tenant (authoritative)', operating_status, NULL, count(*)
FROM analytics_schema.tmp_stg_scheme_status
GROUP BY operating_status
ORDER BY source, operating_status;

-- B2. dim rows with no counterpart in the tenant DB. These are schemes the tenant no longer
--     has (or never had) — PART C leaves them untouched. Investigate a non-zero count.
SELECT count(*) AS dim_rows_without_a_tenant_scheme,
       count(DISTINCT d.scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table d
LEFT JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
  AND r.scheme_id IS NULL;

-- B3. how many rows each column would change
SELECT
    count(*) FILTER (WHERE d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status))           AS work_status_rows,
    count(*) FILTER (WHERE d.work_status IS NULL AND r.work_status IS NOT NULL)                                  AS work_status_null_restored,
    count(*) FILTER (WHERE d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status)) AS operating_status_rows,
    count(*) FILTER (WHERE d.operating_status <> 2 AND r.operating_status = 2)                                   AS partially_operative_restored
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant;

-- B4. sample of the rows that would change, old -> new (first 30)
SELECT d.scheme_id, d.id,
       d.work_status      AS work_now,  r.work_status      AS work_new,
       d.operating_status AS oper_now,  r.operating_status AS oper_new
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status))
ORDER BY d.scheme_id, d.id
LIMIT 30;


-- ################################################################################
-- PART C  (WRITES)  --  apply. Review PART B first; run under Manual Commit.
-- ################################################################################

-- C1. keep a before-image so the change is reversible without a full restore
DROP TABLE IF EXISTS analytics_schema.tmp_scheme_status_backup;
CREATE TABLE analytics_schema.tmp_scheme_status_backup AS
SELECT d.id, d.tenant_id, d.scheme_id, d.work_status, d.operating_status, d.updated_at
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status));

SELECT count(*) AS rows_backed_up FROM analytics_schema.tmp_scheme_status_backup;

-- C2. write the tenant's statuses onto every one of the scheme's dim rows.
--     COALESCE keeps whatever we already hold when the tenant value is unknown, so an
--     absent status never overwrites a good value with NULL.
UPDATE analytics_schema.dim_scheme_table d
SET work_status      = COALESCE(r.work_status,      d.work_status),
    operating_status = COALESCE(r.operating_status, d.operating_status),
    updated_at       = NOW()
FROM analytics_schema.tmp_stg_scheme_status r
WHERE d.tenant_id = :tenant
  AND d.scheme_id = r.scheme_id
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status));


-- ################################################################################
-- PART D  (read-only verification)  --  run BEFORE you commit.
-- ################################################################################

-- D1. THE GATE. A non-zero operating_status = 2 bucket is what proves the repair took:
--     that bucket cannot survive a night while SchemeStatusSyncTask is scheduled, so a
--     non-zero count here (and again tomorrow) proves nothing is re-flattening it.
--     Skip this check only if STEP 1's B1 showed the tenant DB itself has no code 2.
SELECT operating_status, count(*) AS dim_rows, count(DISTINCT scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table
WHERE tenant_id = :tenant
GROUP BY operating_status
ORDER BY operating_status;

-- D2. analytics must now agree with the tenant DB for every scheme it shares.
--     Both columns must return 0. COALESCE mirrors PART C: where the tenant value is NULL the
--     repair deliberately kept what analytics already held, so a bare IS DISTINCT FROM would
--     count those rows as failures and make this gate unpassable.
SELECT count(*) FILTER (WHERE d.work_status      IS DISTINCT FROM COALESCE(r.work_status,      d.work_status))      AS work_status_still_differs,
       count(*) FILTER (WHERE d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status)) AS operating_status_still_differs
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant;

-- D3. per-scheme drift: every row of a repaired scheme must carry the same status.
--     Scoped to schemes present in staging — a scheme the tenant DB no longer has was
--     deliberately left untouched by PART C, so its pre-existing drift is not this
--     script's to answer for. Must return 0 rows.
SELECT d.scheme_id,
       count(DISTINCT d.work_status)      AS distinct_work_status,
       count(DISTINCT d.operating_status) AS distinct_operating_status
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status r ON r.scheme_id = d.scheme_id
WHERE d.tenant_id = :tenant
GROUP BY d.scheme_id
HAVING count(DISTINCT d.work_status) > 1
    OR count(DISTINCT d.operating_status) > 1
LIMIT 50;

-- >>> Review D1-D3, then COMMIT. Re-run D1 the following day: if the code 2 bucket has
-- >>> collapsed to 0 overnight, a SchemeStatusSyncTask is still running somewhere.


-- ################################################################################
-- ROLLBACK (after commit)  --  restore the before-image captured in C1
-- ################################################################################
-- UPDATE analytics_schema.dim_scheme_table d
-- SET work_status      = b.work_status,
--     operating_status = b.operating_status,
--     updated_at       = b.updated_at
-- FROM analytics_schema.tmp_scheme_status_backup b
-- WHERE d.id = b.id;


-- ################################################################################
-- CLEANUP (optional, once the repair is confirmed stable)
-- ################################################################################
-- DROP TABLE IF EXISTS analytics_schema.tmp_stg_scheme_status;
-- DROP TABLE IF EXISTS analytics_schema.tmp_scheme_status_backup;
