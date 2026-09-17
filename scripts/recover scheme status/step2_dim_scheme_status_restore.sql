-- =====================================================================================
-- STATUS RESTORE STEP 2 / 2  --  ANALYTICS DB  (connection whose database holds analytics_schema)
-- Pure SQL for DBeaver. PART A + B are read-only. PART C writes to a production table.
-- SAFETY: run in DBeaver "Manual Commit" mode, review PART B, run PART D, then Commit / Rollback.
-- =====================================================================================
-- Input:  analytics_schema.tmp_stg_scheme_status__as_run1  -- exported by
--         step1_dim_scheme_status_export.sql from tenant_<code>.scheme_master_table, which is
--         authoritative for both columns. Every row carries the analytics tenant it was
--         exported for (source_tenant_id) and the id of that export run (recovery_run_id).
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

-- >>> EDIT ME (1 of 2): :tenant is the analytics tenant_id matching the tenant schema STEP 1
-- >>> exported — the same number you stamped into source_tenant_id there. There is no default:
-- >>> DBeaver prompts for it on the first statement that uses it (SQL Editor > Preferences >
-- >>> SQL Processing > Parameters > "Enable parameters in queries" must be on), or bind it
-- >>> under SQL Editor > Query Parameter Bindings. Every PART B/C/D statement is scoped by it.
-- >>> You are no longer relying on getting it right by hand: PART B's gate refuses to continue
-- >>> unless it equals the source_tenant_id the staging rows carry, and every join below is
-- >>> additionally scoped by that column, so a wrong id cannot write another tenant's dim rows.

-- >>> EDIT ME (2 of 2): find-and-replace EVERY occurrence of the __as_run1 suffix in this file
-- >>> with <tenant code>_<your recovery_run_id>. Unlike the tenant-side export, these two
-- >>> tables live in analytics_schema, which every tenant shares — fixed names mean a second
-- >>> tenant's run silently drops the first one's staging rows and, worse, its C1 before-image
-- >>> while that repair is still being verified. One suffix per tenant per run keeps them apart:
-- >>>     analytics_schema.tmp_stg_scheme_status__as_run1     (imported from STEP 1)
-- >>>     analytics_schema.tmp_scheme_status_backup__as_run1  (C1 before-image, for ROLLBACK)


-- ################################################################################
-- PART A  (run ONCE)  --  create the hand-off staging table, then import into it
-- ################################################################################
DROP TABLE IF EXISTS analytics_schema.tmp_stg_scheme_status__as_run1;
CREATE TABLE analytics_schema.tmp_stg_scheme_status__as_run1 (
    scheme_id        int PRIMARY KEY,
    work_status      int,
    operating_status int,
    source_tenant_id int  NOT NULL,
    recovery_run_id  text NOT NULL
);
-- >>> Now use DBeaver to import tenant_<code>.tmp_dim_scheme_status into this table
--     (right-click the tenant-side table -> Export Data -> Database -> this table),
--     then continue with PART B. Map all five columns — source_tenant_id and
--     recovery_run_id are NOT NULL here on purpose: an import that drops them fails
--     loudly now rather than defeating PART B's gate later.

-- sanity check after import:
-- SELECT count(*) FROM analytics_schema.tmp_stg_scheme_status__as_run1;


-- ################################################################################
-- PART B  (read-only preview)  --  what PART C would change. Nothing is written.
-- ################################################################################

-- guard: staging must be present, populated, internally consistent, and in range.
-- (:tenant is deliberately absent from this block — DBeaver does not bind named parameters
-- inside a dollar-quoted body. The cross-check against :tenant is the next statement, B0.)
DO $$
BEGIN
    IF to_regclass('analytics_schema.tmp_stg_scheme_status__as_run1') IS NULL THEN
        RAISE EXCEPTION 'analytics_schema.tmp_stg_scheme_status__as_run1 not found - run PART A and import first.';
    END IF;
    IF (SELECT count(*) FROM analytics_schema.tmp_stg_scheme_status__as_run1) = 0 THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status__as_run1 is empty - the STEP 1 export was not imported.';
    END IF;

    -- One export, one tenant, one run. More than one distinct value means two exports were
    -- imported into the same table and PART C would write a blend of them.
    IF (SELECT count(DISTINCT source_tenant_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1) <> 1 THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status__as_run1 holds % distinct source_tenant_id values - it must hold exactly one export. Values: %',
            (SELECT count(DISTINCT source_tenant_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1),
            (SELECT string_agg(DISTINCT source_tenant_id::text, ', ') FROM analytics_schema.tmp_stg_scheme_status__as_run1);
    END IF;

    IF (SELECT count(DISTINCT recovery_run_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1) <> 1 THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status__as_run1 holds % distinct recovery_run_id values - it must hold exactly one export. Values: %',
            (SELECT count(DISTINCT recovery_run_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1),
            (SELECT string_agg(DISTINCT recovery_run_id, ', ') FROM analytics_schema.tmp_stg_scheme_status__as_run1);
    END IF;

    -- An unedited STEP 1 placeholder that reached this far identifies nothing.
    IF EXISTS (SELECT 1 FROM analytics_schema.tmp_stg_scheme_status__as_run1
               WHERE source_tenant_id <= 0 OR btrim(recovery_run_id) = '' OR recovery_run_id = 'SET-ME') THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status__as_run1 still carries STEP 1 placeholder identifiers - re-run STEP 1 PART A with source_tenant_id and recovery_run_id filled in, then re-import.';
    END IF;

    -- STEP 1's B2 reports out-of-range codes but cannot stop you importing them. PART C would
    -- write whatever is staged straight onto dim_scheme_table, so refuse here instead. NULL is
    -- legitimate and deliberately allowed: PART C COALESCEs it to the value already held.
    -- 0 is admitted alongside the 1..4 table: STEP 1 exports every non-deleted scheme, and the
    -- placeholder schemes lenient ingest auto-provisions carry work_status = 0, a reserved
    -- sentinel that reads as "Unknown". Those rows reach PART C but match no dim row, so they
    -- change nothing; rejecting the 0 here would abort the restore over rows it never writes.
    IF EXISTS (SELECT 1 FROM analytics_schema.tmp_stg_scheme_status__as_run1
               WHERE work_status IS NOT NULL AND work_status NOT BETWEEN 0 AND 4) THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status__as_run1 holds work_status outside 0..4 - fix the export before running PART C. Offenders: %',
            (SELECT string_agg(format('scheme_id=%s work_status=%s', scheme_id, work_status), ', ')
             FROM (SELECT scheme_id, work_status FROM analytics_schema.tmp_stg_scheme_status__as_run1
                   WHERE work_status IS NOT NULL AND work_status NOT BETWEEN 0 AND 4
                   ORDER BY scheme_id LIMIT 10) s);
    END IF;

    IF EXISTS (SELECT 1 FROM analytics_schema.tmp_stg_scheme_status__as_run1
               WHERE operating_status IS NOT NULL AND operating_status NOT BETWEEN 0 AND 2) THEN
        RAISE EXCEPTION 'tmp_stg_scheme_status__as_run1 holds operating_status outside 0..2 - fix the export before running PART C. Offenders: %',
            (SELECT string_agg(format('scheme_id=%s operating_status=%s', scheme_id, operating_status), ', ')
             FROM (SELECT scheme_id, operating_status FROM analytics_schema.tmp_stg_scheme_status__as_run1
                   WHERE operating_status IS NOT NULL AND operating_status NOT BETWEEN 0 AND 2
                   ORDER BY scheme_id LIMIT 10) s);
    END IF;
END $$;

-- B0. THE IDENTITY GATE. Everything below is scoped by :tenant, so the staging rows must have
--     been exported for that same tenant. On a match this returns one 'OK ...' row naming the
--     tenant, the run and the row count — read it, confirm the run is the one you just
--     exported, and continue. On a mismatch the ELSE branch casts its message to int, which
--     raises and stops the script: that is the intent, because a mismatch here means PART C
--     would otherwise write one tenant's statuses onto another tenant's dim rows. (CASE is
--     evaluated lazily, so the cast never runs on the OK path. It is shaped this way because
--     DBeaver cannot bind :tenant inside a DO block, where a plain RAISE would live.)
SELECT CASE
    WHEN (SELECT min(source_tenant_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1) = :tenant
    THEN format('OK - staging holds analytics tenant %s, recovery run %s, %s schemes',
                (SELECT min(source_tenant_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1),
                (SELECT min(recovery_run_id)  FROM analytics_schema.tmp_stg_scheme_status__as_run1),
                (SELECT count(*)              FROM analytics_schema.tmp_stg_scheme_status__as_run1))
    ELSE CAST(format('ABORT - staging was exported for analytics tenant %s but :tenant is %s. Do NOT run PART C.',
                (SELECT min(source_tenant_id) FROM analytics_schema.tmp_stg_scheme_status__as_run1),
                :tenant) AS int)::text
END AS staging_identity_gate;

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
FROM analytics_schema.tmp_stg_scheme_status__as_run1
GROUP BY operating_status
ORDER BY source, operating_status;

-- B2. dim rows with no counterpart in the tenant DB. These are schemes the tenant no longer
--     has (or never had) — PART C leaves them untouched. Investigate a non-zero count.
SELECT count(*) AS dim_rows_without_a_tenant_scheme,
       count(DISTINCT d.scheme_id) AS schemes
FROM analytics_schema.dim_scheme_table d
LEFT JOIN analytics_schema.tmp_stg_scheme_status__as_run1 r
       ON r.scheme_id = d.scheme_id AND r.source_tenant_id = d.tenant_id
WHERE d.tenant_id = :tenant
  AND r.scheme_id IS NULL;

-- B3. how many rows each column would change
SELECT
    count(*) FILTER (WHERE d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status))           AS work_status_rows,
    count(*) FILTER (WHERE d.work_status IS NULL AND r.work_status IS NOT NULL)                                  AS work_status_null_restored,
    count(*) FILTER (WHERE d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status)) AS operating_status_rows,
    count(*) FILTER (WHERE d.operating_status <> 2 AND r.operating_status = 2)                                   AS partially_operative_restored
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status__as_run1 r
  ON r.scheme_id = d.scheme_id AND r.source_tenant_id = d.tenant_id
WHERE d.tenant_id = :tenant;

-- B4. sample of the rows that would change, old -> new (first 30)
SELECT d.scheme_id, d.id,
       d.work_status      AS work_now,  r.work_status      AS work_new,
       d.operating_status AS oper_now,  r.operating_status AS oper_new
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status__as_run1 r
  ON r.scheme_id = d.scheme_id AND r.source_tenant_id = d.tenant_id
WHERE d.tenant_id = :tenant
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status))
ORDER BY d.scheme_id, d.id
LIMIT 30;


-- ################################################################################
-- PART C  (WRITES)  --  apply. Review PART B first; run under Manual Commit.
-- ################################################################################

-- C1. keep a before-image so the change is reversible without a full restore.
--     Named per tenant and run: this table is the only way back after a commit, so it must
--     not be dropped by the next tenant's repair while this one is still being verified.
DROP TABLE IF EXISTS analytics_schema.tmp_scheme_status_backup__as_run1;
CREATE TABLE analytics_schema.tmp_scheme_status_backup__as_run1 AS
SELECT d.id, d.tenant_id, d.scheme_id, d.work_status, d.operating_status, d.updated_at
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status__as_run1 r
  ON r.scheme_id = d.scheme_id AND r.source_tenant_id = d.tenant_id
WHERE d.tenant_id = :tenant
  AND (d.work_status      IS DISTINCT FROM COALESCE(r.work_status, d.work_status)
    OR d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status));

SELECT count(*) AS rows_backed_up FROM analytics_schema.tmp_scheme_status_backup__as_run1;

-- C2. write the tenant's statuses onto every one of the scheme's dim rows.
--     COALESCE keeps whatever we already hold when the tenant value is unknown, so an
--     absent status never overwrites a good value with NULL.
UPDATE analytics_schema.dim_scheme_table d
SET work_status      = COALESCE(r.work_status,      d.work_status),
    operating_status = COALESCE(r.operating_status, d.operating_status),
    updated_at       = NOW()
FROM analytics_schema.tmp_stg_scheme_status__as_run1 r
WHERE d.tenant_id = :tenant
  AND d.scheme_id = r.scheme_id
  AND r.source_tenant_id = d.tenant_id
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
--     count those rows as failures and make this gate unpassable. Read this together with
--     B3's row counts: B0 has already proved the join is not empty, so 0/0 here means the
--     rows B3 listed were actually written, not that nothing matched.
SELECT count(*) FILTER (WHERE d.work_status      IS DISTINCT FROM COALESCE(r.work_status,      d.work_status))      AS work_status_still_differs,
       count(*) FILTER (WHERE d.operating_status IS DISTINCT FROM COALESCE(r.operating_status, d.operating_status)) AS operating_status_still_differs
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status__as_run1 r
  ON r.scheme_id = d.scheme_id AND r.source_tenant_id = d.tenant_id
WHERE d.tenant_id = :tenant;

-- D3. per-scheme drift: every row of a repaired scheme must carry the same status.
--     Scoped to schemes present in staging — a scheme the tenant DB no longer has was
--     deliberately left untouched by PART C, so its pre-existing drift is not this
--     script's to answer for. Must return 0 rows.
SELECT d.scheme_id,
       count(DISTINCT d.work_status)      AS distinct_work_status,
       count(DISTINCT d.operating_status) AS distinct_operating_status
FROM analytics_schema.dim_scheme_table d
JOIN analytics_schema.tmp_stg_scheme_status__as_run1 r
  ON r.scheme_id = d.scheme_id AND r.source_tenant_id = d.tenant_id
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
-- FROM analytics_schema.tmp_scheme_status_backup__as_run1 b
-- WHERE d.id = b.id;


-- ################################################################################
-- CLEANUP (optional, once the repair is confirmed stable)
-- ################################################################################
-- DROP TABLE IF EXISTS analytics_schema.tmp_stg_scheme_status__as_run1;
-- DROP TABLE IF EXISTS analytics_schema.tmp_scheme_status_backup__as_run1;
