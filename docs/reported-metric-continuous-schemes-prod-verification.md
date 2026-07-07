# Reported-metric (continuous-schemes by-LGD) — prod verification via SQL

How to verify the `REPORTED-METRIC` change **directly in the database**, without waiting on the app,
by reproducing the exact query the endpoint runs and comparing "reported" vs the old "supplied" count.

- **Endpoint changed (only this one):** `GET /api/v1/analytics/continuous-schemes?...&list=false`
  → `SchemeRegularityRepository.getContinuousSchemeCountByLgd`.
- **What changed:** a scheme now counts as "continuous" if it **reported** every day in the range
  (supplied a reading **or** had a rejected submission), not only if it supplied every day.
- Run everything below on the **analytics DB** (`analytics_schema`). Substitute the params from the
  request you're checking. Example uses `tenant_id = 17`, `lgd_id = 1`, `2026-01-01 .. 2026-06-18`.

> Prereqs: analytics-service deployed with the change **and** migration **V40** applied
> (`SELECT to_regclass('analytics_schema.submission_attempt_table');` must be non-null),
> and telemetry-service deployed with the `SUBMISSION_REJECTED` event.

---

## Step 1 — resolve the LGD level → the dim_scheme column

The query filters `dim_scheme_table` on a level-specific column. Find the level for your `lgd_id`:

```sql
SELECT lgd_level
FROM analytics_schema.dim_lgd_location_table
WHERE lgd_id = 1 AND tenant_id = 17;
```

Map the returned `lgd_level` to the column (this is exactly `resolveSchemeLgdColumn`):

| lgd_level | column to use (`<LEVEL_COLUMN>`) |
|---|---|
| 0 | `parent_lgd_location_id` |
| 1 | `level_1_lgd_id` |
| 2 | `level_2_lgd_id` |
| 3 | `level_3_lgd_id` |
| 4 | `level_4_lgd_id` |
| 5 | `level_5_lgd_id` |
| 6 | `level_6_lgd_id` |

Substitute that column for `<LEVEL_COLUMN>` in Steps 3–4. (`daysInRange` in the app is
`(end_date − start_date) + 1` — inclusive; the query computes it inline below so you don't have to.)

---

## Step 2 — quick totals (sanity)

```sql
-- schemes in scope for this region (distinct)
SELECT COUNT(DISTINCT scheme_id)
FROM analytics_schema.dim_scheme_table
WHERE <LEVEL_COLUMN> = 1 AND tenant_id = 17;
```

---

## Step 3 — the NEW "reported" count (should equal the endpoint's `continuousSchemeCount`)

This is byte-for-byte the logic in `getContinuousSchemeCountByLgd` (levers A/B/C):

```sql
WITH params AS (
    SELECT 17::int              AS tenant_id,
           1::int               AS lgd_id,
           DATE '2026-01-01'    AS start_date,
           DATE '2026-06-18'    AS end_date
),
schemes_in_scope AS (
    -- matches the code exactly: the endpoint selects (scheme_id, scheme_name), not just scheme_id
    SELECT DISTINCT s.scheme_id, s.scheme_name
    FROM analytics_schema.dim_scheme_table s, params p
    WHERE s.<LEVEL_COLUMN> = p.lgd_id
      AND s.tenant_id = p.tenant_id
),
reported_events AS (
    -- (A) readings (REPORTED = any reading; SUPPLIED would add: AND m.confirmed_reading > 0)
    SELECT m.scheme_id, m.reading_date AS event_date
    FROM analytics_schema.fact_meter_reading_table m
    JOIN schemes_in_scope ss ON ss.scheme_id = m.scheme_id, params p
    WHERE m.tenant_id = p.tenant_id
      AND m.reading_date BETWEEN p.start_date AND p.end_date

    UNION ALL   -- (B) arrived-but-rejected image submissions
    SELECT a.scheme_id, (a.created_at + INTERVAL '5 hours 30 minutes')::date AS event_date
    FROM analytics_schema.anomaly_table a
    JOIN schemes_in_scope ss ON ss.scheme_id = a.scheme_id, params p
    WHERE a.tenant_id = p.tenant_id
      AND a.type IN ('DUPLICATE_IMAGE_SUBMISSION','UNREADABLE_IMAGE','READING_LESS_THAN_PREVIOUS')
      AND (a.created_at + INTERVAL '5 hours 30 minutes')::date BETWEEN p.start_date AND p.end_date

),
reported_days AS (
    SELECT scheme_id, COUNT(DISTINCT event_date)::int AS reported_days
    FROM reported_events GROUP BY scheme_id
)
SELECT COUNT(*)::bigint AS reported_continuous_count
FROM schemes_in_scope ss
LEFT JOIN reported_days rd ON rd.scheme_id = ss.scheme_id,
     params p
WHERE COALESCE(rd.reported_days, 0) = ((p.end_date - p.start_date) + 1);
```

**Pass criteria:** this equals the `continuousSchemeCount` returned by
`/api/v1/analytics/continuous-schemes?...&list=false` for the same params.

> **Timezone:** after migration **V41**, `anomaly_table.created_at` is a plain `TIMESTAMP` holding
> **UTC** (matching every other table in the repo), so `(created_at + INTERVAL '5:30')::date` gives the
> IST reporting day **session-independently** — the same result in psql (`Asia/Kolkata`) or the app's
> JDBC session. (Do *not* use `+5:30` while the column is still `timestamptz`: there it depends on the
> session TZ and double-shifts on `Asia/Kolkata`. V41 removes that.) The deployed query also has a
> `submission_attempt_table` branch (pre-anomaly rejects), omitted here because it is empty
> pre-deployment; `attempted_at` is likewise plain `TIMESTAMP` in UTC, so it uses the same `+5:30`.

---

## Step 4 — the OLD "supplied" count (the delta this change adds)

Same query, but restrict readings to supply and drop the reject branches:

```sql
WITH params AS (
    SELECT 17::int AS tenant_id, 1::int AS lgd_id,
           DATE '2026-01-01' AS start_date, DATE '2026-06-18' AS end_date
),
schemes_in_scope AS (
    SELECT DISTINCT s.scheme_id
    FROM analytics_schema.dim_scheme_table s, params p
    WHERE s.<LEVEL_COLUMN> = p.lgd_id AND s.tenant_id = p.tenant_id
),
supply_days AS (
    SELECT m.scheme_id,
           COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
    FROM analytics_schema.fact_meter_reading_table m
    JOIN schemes_in_scope ss ON ss.scheme_id = m.scheme_id, params p
    WHERE m.tenant_id = p.tenant_id AND m.reading_date BETWEEN p.start_date AND p.end_date
    GROUP BY m.scheme_id
)
SELECT COUNT(*)::bigint AS supplied_continuous_count
FROM schemes_in_scope ss
LEFT JOIN supply_days sd ON sd.scheme_id = ss.scheme_id, params p
WHERE COALESCE(sd.supply_days, 0) = ((p.end_date - p.start_date) + 1);
```

**Expected:** `reported ≥ supplied`. The difference = schemes whose only "gap" days were
rejected submissions (duplicate / unreadable / pre-anomaly) rather than true non-supply.

---

## Step 5 — verify the reject-capture pipeline (Part 2)

After deploy, as validation-rejected submissions come in, rows should appear:

```sql
-- Is the table live and populating?
SELECT COUNT(*)                        AS total,
       COUNT(*) FILTER (WHERE scheme_id IS NOT NULL) AS resolved_to_scheme,
       MIN(attempted_at)               AS first_seen,
       MAX(attempted_at)               AS last_seen
FROM analytics_schema.submission_attempt_table
WHERE tenant_id = 17;

-- Recent captured rejects
SELECT scheme_id, submitted_state_scheme_id, submitted_centre_scheme_id, reason, attempted_at
FROM analytics_schema.submission_attempt_table
WHERE tenant_id = 17
ORDER BY attempted_at DESC
LIMIT 50;
```

To force one end-to-end: POST a body to `/api/v1/telemetry/readings` that fails bean validation but
carries a `state_scheme_id` (e.g. blank `phone_number`, valid `state_scheme_id`). Within a moment a row
should appear above with `reason` starting `validation:` and `scheme_id` resolved (if that gov id is in
`dim_scheme`).

---

## Notes / caveats

- **Only this view changed** — `continuous-schemes` **by-LGD, `list=false`**. The `list=true` rows,
  and the department/user continuous variants, still use the original "supplied" logic.
- **Day boundary:** readings use `reading_date`; rejects use IST-adjusted `created_at` / `attempted_at`
  (`+ 5:30`) to line up. Range edges are approximate.
- **Reject-capture reads ~0 for days whose gap is delivery-side** (submissions that never reached us).
  Levers A (reported) + B (rejects) are what move this count today; C future-proofs.
- **Revert in parts:** `grep -rn "REPORTED-METRIC" backend/`. Re-add `AND m.confirmed_reading > 0` for
  (A); delete a `UNION ALL` block for (B)/(C). The `submission_attempt_table` and event are additive and
  safe to leave even if the KPI is reverted.
