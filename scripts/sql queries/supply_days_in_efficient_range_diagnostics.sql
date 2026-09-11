-- =====================================================================================
-- supplyDaysInEfficientRange — per-scheme / per-day audit      (READ ONLY, ANALYTICS DB)
-- =====================================================================================
-- Plain SQL — no psql meta-commands, no bind variables. Runs as-is in DBeaver.
--
--   HOW TO RUN IN DBEAVER
--     1. Open this file against the connection holding analytics_schema.
--     2. Edit the literals in the `params` CTE of QUERY B (and the tenant id in QUERY A).
--     3. Ctrl+Enter runs the statement under the cursor.
--        Alt+X runs both statements and opens one result tab each.
--     If DBeaver ever prompts for a parameter value, turn off
--        Preferences > Editors > SQL Editor > SQL Processing > "Bind variables".
--     Nothing here writes.
--
-- WHAT THIS REPRODUCES
-- --------------------
-- The `region_supply_days` CTE of SchemeRegularityRepository.getRegionWiseWaterQuantityByLgd
-- (and its identical twins at the tenant / national / department scopes), exploded to one row
-- per (scheme, day) so you can see the inputs behind every +1.
--
-- The production rule, verbatim:
--
--   target = required_lpcd * (fhtc_count * person_count_per_household)
--   lower  = target * (1 - under_supply_range_percentage / 100)
--   upper  = target * (1 + over_supply_range_percentage  / 100)
--   counts <=> daily_ewater_quantity IS NOT NULL AND daily_ewater_quantity BETWEEN lower AND upper
--
-- Note what the day's quantity is and is NOT:
--   * It is the LATEST row per (tenant_id, scheme_id, date) — the {{LWQ}} DISTINCT ON
--     de-duplication (updated_at DESC, id DESC), not a sum over all rows for that day.
--   * It is RAW `water_quantity`, with NO submission_status filter. This differs from the
--     `ewater_quantity` total column on the same API, which uses {{SWS}} and excludes
--     NOT_SUBMITTED rows. Consequence: a NOT_SUBMITTED / outage day carrying a positive
--     quantity in band STILL counts as a supply day in efficient range. `submission_status`
--     is surfaced below as an informational column so you can spot those rows; it does not
--     affect the verdict, because it does not affect it in production either.
--
-- Scheme selection mirrors the dashboard's `schemes_in_scope`: DISTINCT ON (scheme_id) with
-- tie-break fhtc_count DESC, house_hold_count DESC, planned_fhtc DESC, so a scheme fanned out
-- across several dim_scheme_table rows (V16/V24 mapping key) yields exactly one row here.
-- The region-wise API instead dedupes per (scheme_id, child_lgd_id) and therefore counts a
-- fanned-out scheme once PER child region — see the reconciliation note at the bottom.
--
-- work_status is pinned to the literal in `params` (4 = handed-over). Production instead
-- resolves an effective SET via DashboardWorkStatusFilter:
--   own tenant (dim_tenant_table.included_work_statuses) -> tenant 0 -> env default
--   (analytics.dashboard.included-work-statuses, currently `4`).
-- QUERY A prints that chain so you can confirm the literal matches what the API applies.
-- =====================================================================================


-- =====================================================================================
-- QUERY A — config actually in force                                    (sanity check)
-- =====================================================================================
-- Confirm required_lpcd / person_count_per_household are non-zero, and that
-- included_work_statuses does not contradict the work_status literal used in QUERY B.
-- Change the 1 on the `tenant_id` line below to audit a different tenant.
-- =====================================================================================
WITH params AS (
    SELECT 1::int AS tenant_id            -- <<< tenant (1 = Assam)
)
SELECT t.tenant_id,
       t.title,
       COALESCE(t.required_lpcd, 0)                 AS required_lpcd,
       COALESCE(t.person_count_per_household, 5)    AS person_count_per_household,
       COALESCE(t.under_supply_range_percentage, 0) AS under_supply_pct,
       COALESCE(t.over_supply_range_percentage, 0)  AS over_supply_pct,
       t.included_work_statuses                     AS own_tenant_work_statuses,
       (SELECT dt.included_work_statuses
          FROM analytics_schema.dim_tenant_table dt
         WHERE dt.tenant_id = 0)                    AS national_work_statuses
FROM analytics_schema.dim_tenant_table t
CROSS JOIN params p
WHERE t.tenant_id IN (0, p.tenant_id)
ORDER BY t.tenant_id;


-- =====================================================================================
-- QUERY B — per-scheme / per-day audit                                   (the main one)
-- =====================================================================================
-- One row per scheme per day in the window, including days with no reading at all
-- (counted_in_efficient_range = NO, reason = NO_RECORD) — exactly how the CROSS JOIN in
-- production treats them.
--
-- EDIT THE FIVE LITERALS IN `params` BELOW. Nothing else needs changing.
-- =====================================================================================
WITH params AS (
    SELECT
        1::int            AS tenant_id,       -- <<< tenant (1 = Assam)
        1::int            AS level_1_lgd_id,  -- <<< level-1 LGD
        4::int            AS work_status,     -- <<< 4 = handed-over
        DATE '2026-07-01' AS start_date,      -- <<< window start (inclusive)
        DATE '2026-07-31' AS end_date         -- <<< window end   (inclusive)
),
tenant_cfg AS (
    SELECT
        t.tenant_id,
        COALESCE(t.required_lpcd, 0)                 AS required_lpcd,
        COALESCE(t.person_count_per_household, 5)    AS person_count_per_household,
        COALESCE(t.over_supply_range_percentage, 0)  AS over_supply_range_percentage,
        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
    FROM analytics_schema.dim_tenant_table t
    CROSS JOIN params p
    WHERE t.tenant_id = p.tenant_id
),
schemes_in_scope AS (
    SELECT DISTINCT ON (s.scheme_id)
        s.scheme_id,
        s.tenant_id,
        s.scheme_name,
        s.state_scheme_id,
        s.work_status,
        s.level_2_lgd_id,
        COALESCE(s.fhtc_count, 0)       AS fhtc_count,
        COALESCE(s.house_hold_count, 0) AS house_hold_count,
        COALESCE(s.planned_fhtc, 0)     AS planned_fhtc
    FROM analytics_schema.dim_scheme_table s
    CROSS JOIN params p
    WHERE s.tenant_id      = p.tenant_id
      AND s.level_1_lgd_id = p.level_1_lgd_id
      AND s.work_status    = p.work_status
    ORDER BY s.scheme_id,
             COALESCE(s.fhtc_count, 0)       DESC,
             COALESCE(s.house_hold_count, 0) DESC,
             COALESCE(s.planned_fhtc, 0)     DESC
),
dates_in_range AS (
    SELECT d.full_date AS date
    FROM analytics_schema.dim_date_table d
    CROSS JOIN params p
    WHERE d.full_date BETWEEN p.start_date AND p.end_date
),
-- Identical to production's {{LWQ}} + ewater_by_scheme_day: latest row per
-- (tenant_id, scheme_id, date), raw water_quantity, no submission_status filter.
ewater_by_scheme_day AS (
    SELECT
        f.scheme_id,
        f.date,
        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity,
        MAX(f.submission_status)                   AS submission_status,
        MAX(f.updated_at)                          AS updated_at
    FROM (SELECT DISTINCT ON (fwq.tenant_id, fwq.scheme_id, fwq.date) fwq.*
            FROM analytics_schema.fact_water_quantity_table fwq
           ORDER BY fwq.tenant_id, fwq.scheme_id, fwq.date,
                    fwq.updated_at DESC, fwq.id DESC) f
    CROSS JOIN params p
    WHERE f.date BETWEEN p.start_date AND p.end_date
      AND f.tenant_id = p.tenant_id
    GROUP BY f.scheme_id, f.date
),
scheme_day AS (
    SELECT
        s.scheme_id,
        s.tenant_id,
        s.scheme_name,
        s.state_scheme_id,
        s.level_2_lgd_id,
        s.fhtc_count,
        dr.date,
        wd.daily_ewater_quantity,
        wd.submission_status,
        wd.updated_at,
        tc.required_lpcd,
        tc.person_count_per_household,
        tc.under_supply_range_percentage,
        tc.over_supply_range_percentage,
        (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
            AS target_qty,
        (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
            * (1 - (tc.under_supply_range_percentage::numeric / 100)) AS lower_threshold_qty,
        (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
            * (1 + (tc.over_supply_range_percentage::numeric / 100))  AS upper_threshold_qty
    FROM schemes_in_scope s
    CROSS JOIN dates_in_range dr
    CROSS JOIN tenant_cfg tc
    LEFT JOIN ewater_by_scheme_day wd
           ON wd.scheme_id = s.scheme_id
          AND wd.date      = dr.date
)
SELECT
    sd.scheme_id,
    sd.scheme_name,
    sd.state_scheme_id,
    sd.level_2_lgd_id,
    COALESCE(NULLIF(l.title, ''), l.lgd_c_name)  AS level_2_name,
    sd.date,
    sd.fhtc_count,
    sd.required_lpcd,
    sd.person_count_per_household,
    sd.target_qty,
    ROUND(sd.lower_threshold_qty, 2)             AS under_supply_threshold_qty,
    ROUND(sd.upper_threshold_qty, 2)             AS over_supply_threshold_qty,
    sd.under_supply_range_percentage             AS under_pct,
    sd.over_supply_range_percentage              AS over_pct,
    sd.daily_ewater_quantity                     AS water_quantity_supplied,   -- NULL = no record
    -- Informational only. Production ignores this when deciding the verdict below.
    CASE
        WHEN sd.submission_status IS NULL THEN NULL
        WHEN sd.submission_status = 1     THEN 'SUBMITTED'
        WHEN sd.submission_status = 0     THEN 'NOT_SUBMITTED'
        ELSE 'UNKNOWN(' || sd.submission_status || ')'
    END                                          AS submission_status,
    sd.updated_at                                AS reading_updated_at,
    CASE
        WHEN sd.daily_ewater_quantity IS NOT NULL
             AND sd.daily_ewater_quantity::numeric
                 BETWEEN sd.lower_threshold_qty AND sd.upper_threshold_qty
        THEN 'YES' ELSE 'NO'
    END                                          AS counted_in_efficient_range,
    CASE
        WHEN sd.daily_ewater_quantity IS NULL                           THEN 'NO_RECORD'
        WHEN sd.daily_ewater_quantity::numeric < sd.lower_threshold_qty THEN 'UNDER_SUPPLY'
        WHEN sd.daily_ewater_quantity::numeric > sd.upper_threshold_qty THEN 'OVER_SUPPLY'
        ELSE 'IN_RANGE'
    END                                          AS reason,
    -- Degenerate band: target 0 collapses lower = upper = 0, so ONLY a recorded
    -- quantity of exactly 0 counts, and every real supply day is scored OVER_SUPPLY.
    CASE
        WHEN sd.target_qty = 0 AND sd.fhtc_count = 0 THEN 'ZERO_BAND: fhtc_count = 0'
        WHEN sd.target_qty = 0                       THEN 'ZERO_BAND: required_lpcd = 0'
        ELSE NULL
    END                                          AS threshold_warning
FROM scheme_day sd
LEFT JOIN analytics_schema.dim_lgd_location_table l
       ON l.lgd_id    = sd.level_2_lgd_id
      AND l.tenant_id = sd.tenant_id
ORDER BY sd.scheme_id, sd.date;


-- =====================================================================================
-- QUERY C (optional) — per-scheme rollup, to reconcile against the API
-- =====================================================================================
-- To use: copy QUERY B, replace its final `SELECT ... ORDER BY sd.scheme_id, sd.date;`
-- block with the SELECT below (keeping every CTE above it), and run.
--
--   SELECT
--       sd.scheme_id,
--       sd.scheme_name,
--       sd.level_2_lgd_id,
--       sd.fhtc_count,
--       COUNT(*)                                   AS days_in_window,
--       COUNT(sd.daily_ewater_quantity)            AS days_with_a_record,
--       COUNT(*) FILTER (
--           WHERE sd.daily_ewater_quantity IS NOT NULL
--             AND sd.daily_ewater_quantity::numeric
--                 BETWEEN sd.lower_threshold_qty AND sd.upper_threshold_qty
--       )                                          AS supply_days_in_efficient_range,
--       COUNT(*) FILTER (
--           WHERE sd.daily_ewater_quantity::numeric < sd.lower_threshold_qty
--       )                                          AS under_days,
--       COUNT(*) FILTER (
--           WHERE sd.daily_ewater_quantity::numeric > sd.upper_threshold_qty
--       )                                          AS over_days,
--       COUNT(*) FILTER (WHERE sd.daily_ewater_quantity IS NULL) AS missing_days
--   FROM scheme_day sd
--   GROUP BY sd.scheme_id, sd.scheme_name, sd.level_2_lgd_id, sd.fhtc_count
--   ORDER BY supply_days_in_efficient_range, sd.scheme_id;
--
-- Per region: add level_2_lgd_id-only grouping. That matches the region-wise API's
-- supplyDaysInEfficientRange ONLY when no scheme fans out across dim_scheme rows with
-- differing level_2_lgd_id. Where one does, the API counts that scheme once per child
-- region while this script counts it once in total.
-- scripts/dim_scheme_fanout_diagnostics.sql identifies those schemes.
--
-- Performance: the {{LWQ}} sub-select is reproduced verbatim, so its predicates sit
-- outside the DISTINCT ON. idx_fact_water_dedup (V43) covers the ordering. If the window
-- is wide and the table large, pushing tenant_id/date inside that sub-select is a safe
-- rewrite — DISTINCT ON already partitions by (tenant_id, scheme_id, date).
-- =====================================================================================
