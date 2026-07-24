# Analytics KPI Pre-Aggregation — Database Changes

Schema: `analytics_schema`. All times IST; day = 12am→12am; week = Sunday→Saturday.

> **Naming:** tables follow the house `fact_` / `dim_` + `_table` convention (matching
> `fact_water_quantity_table`, `dim_scheme_table`, …). **Migration map after the `dev` merge:**
> V40/V41 earlier fixes, V42 `included_work_statuses`, V43 water-quantity dedup index,
> **V44 `regularity_threshold_percent`** and **V45 Sunday-week columns** (both merged from `dev`),
> then the pre-aggregation objects **V46, V47, V48, V49, V50** (contiguous). An interim
> `fact_region_distribution_table` was designed then dropped (see below) before deployment;
> its migration was removed and the trailing versions closed up so there is no numbering gap.
> The water-norm table was moved off its original **V44** to clear a duplicate-V44 collision with
> `dev`'s `regularity_threshold_percent` migration, landing at **V50**.

## The single water figure (important)

There is **one** water measure across all tables: **`water_supplied_liters`** — built from the
de-duplicated latest `fact_water_quantity_table` row per (tenant, scheme, day), counted only when
`submission_status = SUBMITTED (1) OR IS NULL (legacy)` **and** `water_quantity > 0`.

This is exactly the canonical `{{SWS}}` / `{{SWD}}` SQL fragments in `SchemeRegularityRepository`,
which the national dashboard, region-wise water quantity, average-per-region and periodic KPIs
**all read since the water-unification fix** (branch `water-quantity-calculation-fix`). The earlier
plan split water into "reported" (all rows → region-wise) vs "submitted" (national rule) columns;
those two rules no longer exist in code, so the split columns
(`water_quantity_liters`/`water_quantity_submitted_liters`,
`total_water_supplied_liters`+`total_water_submitted_liters` as a pair) are **dropped** in favour of
the single column. With one column, the national and region-wise figures cannot diverge again.

Consequences:

- `water_quantity_row_count` is **dropped** — after de-duplication a scheme-day has at most one
  qualifying water row, so the row count equals the `supplied` flag; averages divide by
  `total_supply_days` instead.
- `confirmed_reading_total` / `total_confirmed_reading` are **dropped** — since the unification no
  KPI reads confirmed-meter-reading litres (average-per-region and periodic-regularity water were
  switched to the `{{SWS}}` figure); meter readings still feed `submitted`,
  `compliant_count`/`anomalous_count` and the hourly activity table.

## The work-status filter: persisted per period, baked per scope (important)

Dashboards restrict schemes by `work_status` with a **three-tier** config (own tenant →
national tenant-0 → env default), and tenant screens vs the national dashboard intentionally
use **different chains** (`{{WS}}` vs `{{NWS}}` in the legacy SQL). Pre-aggregated KPIs
therefore need two things:

1. **Filter history** — `dim_tenant_work_status_filter_table` (V49) is an SCD-2 timeline of
   `included_work_statuses` per tier (`tenant_id > 0` = tenant's own filter, `tenant_id = 0`
   = national default). `dim_tenant_table.included_work_statuses` remains the *current*
   convenience copy (the legacy read-time SQL keeps using it); every filter-change event also
   closes/opens a history row. A KPI bucket is built with the filter row **in force on its
   `period_end`**, so stored history stays reproducible when the filter changes later — the
   same contract as the water-norm snapshots.
2. **Scoped rows** — `fact_region_metrics_table.work_status_scope` (`TENANT` | `NATIONAL`, part
   of the unique key): `TENANT` rows are built with the tenant chain across both hierarchies and
   all levels (what tenant dashboards read); `NATIONAL` rows with the uniform national chain at
   LGD levels 1–2 only (all the national dashboard reads). The two stored value-sets can
   legitimately differ — that divergence is the *intended* behaviour, confirmed by the
   work-status fallback tests on `dev`.

The base grain (`fact_scheme_daily_table`) stays **unfiltered** — one row per scheme-day
regardless of `work_status` — so a filter change never requires rebuilding it; only region
rollups re-run. KPIs derived at read time from the base grain (continuous/critical/reason
distributions/per-scheme water) apply the tenant chain from the history table themselves
(as of the requested range end; critical uses the current filter since it is a
current-state KPI). Reason/status distributions are likewise computed on read from the base
grain with the tenant chain — there is **no** pre-rolled distribution table (see the dropped
`fact_region_distribution_table` note below).

## Summary of objects

| # | Object | Migration | Change | Purpose |
|---|--------|-----------|--------|---------|
| 1 | `dim_tenant_table.included_work_statuses` | V42 | Altered (+1 col) | Current work-status filter (convenience copy; full history in V49) |
| 2 | `dim_tenant_table.regularity_threshold_percent` | V44 *(from dev)* | Altered (+1 col) | Per-tenant regularity threshold %; NULL → 3-tier fallback (tenant → national → env) |
| 3 | `dim_date_table` | V45 *(from dev)* | Altered (+2 cols) | Sunday-aligned week boundaries |
| 4 | `fact_scheme_daily_table` | V46 | New table | Base scheme×day pre-aggregation (midnight grain, unfiltered) |
| 5 | `fact_region_metrics_table` | V47 | New table | Pre-rolled region KPIs per DAY/WEEK/MONTH bucket × filter scope |
| 6 | `fact_submission_activity_hourly_table` | V48 | New table | Hourly reading-submission activity (state-level fast path) |
| 7 | `dim_tenant_work_status_filter_table` | V49 | New table | SCD-2 history of the work-status filter per tier (tenant / national) |
| 8 | `dim_tenant_water_norm_table` | V50 | New table | SCD-2 history of water-norm values used in KPI calcs |

> An interim `fact_region_distribution_table` was designed at V48 then dropped before deployment
> (write-only + non-additive across ranges; distributions are computed on read instead). Its
> migration was removed and the later versions renumbered down, so there is no version gap.

---

## 1. `dim_tenant_water_norm_table` (V50) — new

| Column | Type | Constraints | Use / Need |
|--------|------|-------------|------------|
| `id` | BIGSERIAL | PRIMARY KEY | Surrogate key |
| `tenant_id` | INT | NOT NULL, FK → `dim_tenant_table(tenant_id)` | Owning tenant |
| `effective_from` | DATE | NOT NULL | Start of validity (inclusive) |
| `effective_to` | DATE | NULL = currently in effect | End of validity (exclusive); half-open `[from, to)` |
| `required_lpcd` | INT | — | Litres per capita per day norm at this period |
| `person_count_per_household` | INT | — | Persons/household assumption used in calcs |
| `over_supply_range_percentage` | INT | — | Upper efficient-range band % |
| `under_supply_range_percentage` | INT | — | Lower efficient-range band % |
| `created_at` | TIMESTAMP | NOT NULL, DEFAULT now | Row insert time |

| Index / Constraint | Definition | Use / Need |
|--------------------|------------|------------|
| `uq_dim_tenant_water_norm_open` | UNIQUE(`tenant_id`) WHERE `effective_to IS NULL` | At most one open (current) norm row per tenant |
| `idx_dim_tenant_water_norm_lookup` | (`tenant_id`, `effective_from`, `effective_to`) | Point-in-time norm lookup by date |

---

## 2. `dim_date_table` (V45) — altered (new columns only)

| Column | Type | Constraints | Use / Need |
|--------|------|-------------|------------|
| `week_start_date` | DATE | nullable (backfilled) | Sunday that starts the date's week |
| `week_end_date` | DATE | nullable (backfilled) | Saturday that ends the date's week |

| Index | Definition | Use / Need |
|-------|------------|------------|
| `idx_dim_date_week_start` | (`week_start_date`) | Weekly grouping/lookups |

---

## 3. `fact_scheme_daily_table` (V46) — new (base grain: scheme × calendar day)

| Column | Type | Constraints | Use / Need |
|--------|------|-------------|------------|
| `tenant_id` | INT | NOT NULL, PK part | Tenant scope (scheme_id not globally unique) |
| `scheme_id` | INT | NOT NULL, PK part | Scheme |
| `reading_date` | DATE | NOT NULL, PK part | Calendar day (midnight grain) |
| `level_1_lgd_id` … `level_6_lgd_id` | INT | — | LGD ancestor keys (rollup without join) |
| `level_1_dept_id` … `level_6_dept_id` | INT | — | Department ancestor keys (rollup without join) |
| `submitted` | SMALLINT | NOT NULL, DEFAULT 0 | 1 if a meter reading was sent that day (reading-based) → submission rate, continuous ("reported every day"), distinct-submitting |
| `supplied` | SMALLINT | NOT NULL, DEFAULT 0 | 1 if the day's water row qualifies (`{{SWD}}`: SUBMITTED-or-legacy-NULL and > 0) → regularity / supply days |
| `water_supplied_liters` | BIGINT | NOT NULL, DEFAULT 0 | The **single** water figure (`{{SWS}}` semantics) → national, region-wise and periodic water KPIs alike |
| `compliant_count` | INT | NOT NULL, DEFAULT 0 | Readings where `extracted = confirmed` |
| `anomalous_count` | INT | NOT NULL, DEFAULT 0 | Readings where `extracted` distinct from `confirmed` |
| `household_count` | INT | NOT NULL, DEFAULT 0 | Scheme households (from `dim_scheme_table`) |
| `achieved_fhtc_count` | INT | NOT NULL, DEFAULT 0 | Achieved FHTC (from `dim_scheme_table`) |
| `planned_fhtc_count` | INT | NOT NULL, DEFAULT 0 | Planned FHTC (from `dim_scheme_table`) |
| `is_supply_efficient` | SMALLINT | NOT NULL, DEFAULT 0 | 1 if `water_supplied_liters` within `[under, over]` band (norm snapshot) |
| `outage_reason_code` | VARCHAR(64) | — | Outage reason that day (for distributions) |
| `non_submission_reason_code` | VARCHAR(64) | — | Non-submission reason that day |
| `scheme_status_code` | VARCHAR(32) | — | `dim_scheme_table.operating_status` snapshot |
| `norm_required_lpcd` | INT | — | Norm value actually used (audit/repro) |
| `norm_persons_per_household` | INT | — | Norm value actually used |
| `norm_over_supply_pct` | INT | — | Norm value actually used |
| `norm_under_supply_pct` | INT | — | Norm value actually used |
| `computed_at` | TIMESTAMP | NOT NULL, DEFAULT now | Last (re)computation time |
| `is_final` | BOOLEAN | NOT NULL, DEFAULT false | True once the day is closed (`reading_date < today`) |

| Index / Constraint | Definition | Use / Need |
|--------------------|------------|------------|
| `pk_fact_scheme_daily` | PRIMARY KEY (`tenant_id`, `scheme_id`, `reading_date`) | Grain key; idempotent UPSERT target |
| `idx_fact_scheme_daily_tenant_date` | (`tenant_id`, `reading_date`) | Tenant/day reads + rollups |
| `idx_fact_scheme_daily_date` | (`reading_date`) | Date-range scans |
| `idx_fact_scheme_daily_lgd2_date` | (`level_2_lgd_id`, `reading_date`) | District rollups |
| `idx_fact_scheme_daily_lgd3_date` | (`level_3_lgd_id`, `reading_date`) | Block rollups |
| `idx_fact_scheme_daily_lgd4_date` | (`level_4_lgd_id`, `reading_date`) | Gram-panchayat rollups |

Removed vs the earlier draft: `water_quantity_liters` + `water_quantity_submitted_liters` (merged
into `water_supplied_liters`), `water_quantity_row_count` (equals `supplied` after de-dup),
`confirmed_reading_total` (no KPI reads it — see "single water figure" above).

---

## 4. `fact_region_metrics_table` (V47) — new (region × period bucket)

| Column | Type | Constraints | Use / Need |
|--------|------|-------------|------------|
| `id` | BIGSERIAL | PRIMARY KEY | Surrogate key |
| `period_scale` | VARCHAR(8) | NOT NULL | `DAY` \| `WEEK` \| `MONTH` |
| `period_start` | DATE | NOT NULL | Bucket start (Sunday for WEEK; 1st for MONTH) |
| `period_end` | DATE | NOT NULL | Bucket end (Saturday for WEEK; last for MONTH) |
| `tenant_id` | INT | NOT NULL | Tenant scope |
| `hierarchy` | VARCHAR(8) | NOT NULL | `LGD` \| `DEPT` |
| `region_level` | SMALLINT | NOT NULL | Hierarchy level 1–6 |
| `region_id` | INT | NOT NULL | LGD/department id at that level |
| `work_status_scope` | VARCHAR(8) | NOT NULL, DEFAULT 'TENANT' | `TENANT` (tenant filter chain; all hierarchies/levels) \| `NATIONAL` (national chain; LGD levels 1–2) — filter resolved from the history in force on `period_end` |
| `days_in_range` | INT | NOT NULL, DEFAULT 0 | Days in the bucket |
| `scheme_count` | INT | NOT NULL, DEFAULT 0 | Schemes in region (additive across nodes) |
| `total_supply_days` | INT | NOT NULL, DEFAULT 0 | Σ `supplied` (additive) → regularity; also the divisor for average water per supply-day |
| `total_submission_days` | INT | NOT NULL, DEFAULT 0 | Σ `submitted` (additive) → submission rate |
| `active_scheme_count` | INT | NOT NULL, DEFAULT 0 | Active schemes |
| `inactive_scheme_count` | INT | NOT NULL, DEFAULT 0 | Inactive schemes |
| `total_water_supplied_liters` | BIGINT | NOT NULL, DEFAULT 0 | Σ `water_supplied_liters` — the **only** water total; national **and** region-wise cards read this |
| `total_household_count` | BIGINT | NOT NULL, DEFAULT 0 | Σ households over distinct schemes |
| `total_achieved_fhtc` | BIGINT | NOT NULL, DEFAULT 0 | Σ achieved FHTC |
| `total_planned_fhtc` | BIGINT | NOT NULL, DEFAULT 0 | Σ planned FHTC |
| `supply_days_in_efficient_range` | INT | NOT NULL, DEFAULT 0 | Σ efficient-range supply days |
| `compliant_submission_count` | INT | NOT NULL, DEFAULT 0 | Σ compliant readings |
| `anomalous_submission_count` | INT | NOT NULL, DEFAULT 0 | Σ anomalous readings |
| `continuous_scheme_count` | INT | NOT NULL, DEFAULT 0 | Per-bucket only — NOT summable across buckets |
| `critical_scheme_count` | INT | NOT NULL, DEFAULT 0 | Per-bucket only (best-effort; parity TBD) |
| `distinct_submitting_schemes` | INT | NOT NULL, DEFAULT 0 | Per-bucket only — NOT summable across buckets |
| `norm_required_lpcd` | INT | — | Norm used (audit/repro) |
| `norm_persons_per_household` | INT | — | Norm used |
| `norm_over_supply_pct` | INT | — | Norm used |
| `norm_under_supply_pct` | INT | — | Norm used |
| `computed_at` | TIMESTAMP | NOT NULL, DEFAULT now | Last (re)computation time |
| `is_final` | BOOLEAN | NOT NULL, DEFAULT false | True when bucket closed (`period_end < today`) |

| Index / Constraint | Definition | Use / Need |
|--------------------|------------|------------|
| `uq_fact_region_metrics` | UNIQUE(`period_scale`, `period_start`, `tenant_id`, `hierarchy`, `region_level`, `region_id`, `work_status_scope`) | Natural key; idempotent UPSERT (one row per filter scope) |
| `idx_fact_region_metrics_lookup` | (`period_scale`, `tenant_id`, `hierarchy`, `region_level`, `region_id`, `period_start`) | Dashboard card / range reads |
| `idx_fact_region_metrics_tenant` | (`tenant_id`, `period_scale`, `period_start`) | Tenant-wide scans |

**No derived-ratio columns are stored.** The three convenience averages
`average_regularity` / `reading_submission_rate` / `avg_water_supply_per_scheme` were **dropped**:
ratios are always recomputed on read from the additive numerators/denominators above, for the exact
grouping requested — averaging pre-averaged rows is unsafe (Simpson's paradox) when buckets/regions
differ in size.

**Regularity KPI (post-`dev`-merge):** a scheme is "regular" when its supply days in the window
`>= thresholdDays = max(1, round(pct/100 × days_in_range))`, where `pct` comes from the three-tier
`regularity_threshold_percent` fallback (V44). regularity % = `regularSchemeCount / scheme_count`.
`regularSchemeCount` is **not** stored on this table — it is a non-additive count computed on read
from `fact_scheme_daily_table` (via `AggregateReadRepository.getRegularSchemeCount`), the same way
continuous/critical/distinct counts are handled.

Removed vs the earlier draft: `total_water_submitted_liters` (national now reads
`total_water_supplied_liters` like everyone else), `water_quantity_row_count` (use
`total_supply_days` as the average divisor), `total_confirmed_reading` (no KPI reads it).

---

## 5. `fact_region_distribution_table` (V48) — **DROPPED (not created)**

Originally planned as a long-format distribution table (`OUTAGE_REASON` / `NON_SUBMISSION_REASON`
/ `SUBMISSION_STATUS` / `SCHEME_STATUS` counts per region/period). **Removed before deployment**
because it was write-only — the read path already computes reason/status distributions on the fly
from `fact_scheme_daily_table` (`outage_reason_code`, `non_submission_reason_code`,
`scheme_status_code`, `submitted`) with the tenant filter chain — and its per-bucket
`COUNT(DISTINCT scheme_id)` is non-additive across arbitrary date ranges, so it could not serve the
actual range queries anyway. Migration **V48 is intentionally left unused** (version gap, not a
mistake).

---

## 6. `fact_submission_activity_hourly_table` (V48) — new (HOUR grain)

| Column | Type | Constraints | Use / Need |
|--------|------|-------------|------------|
| `id` | BIGSERIAL | PRIMARY KEY | Surrogate key |
| `hour_start` | TIMESTAMP | NOT NULL | Hour bucket (truncated to the hour) |
| `tenant_id` | INT | NOT NULL | Tenant scope |
| `hierarchy` | VARCHAR(8) | NOT NULL, DEFAULT 'LGD' | Region hierarchy |
| `region_level` | SMALLINT | NOT NULL, DEFAULT 1 | Region level (state by default) |
| `region_id` | INT | NOT NULL | Region id |
| `submission_count` | INT | NOT NULL, DEFAULT 0 | Readings submitted in the hour |
| `distinct_scheme_count` | INT | NOT NULL, DEFAULT 0 | Distinct schemes submitting in the hour |
| `computed_at` | TIMESTAMP | NOT NULL, DEFAULT now | Last (re)computation time |

| Index / Constraint | Definition | Use / Need |
|--------------------|------------|------------|
| `uq_fact_submission_activity_hourly` | UNIQUE(`hour_start`, `tenant_id`, `hierarchy`, `region_level`, `region_id`) | Natural key; idempotent UPSERT |
| `idx_fact_submission_activity_hourly_lookup` | (`tenant_id`, `hour_start`) | Hourly activity reads |

---

## 7. `dim_tenant_work_status_filter_table` (V49) — new

SCD-2 history of the dashboard work-status filter per tier: `tenant_id > 0` = a tenant's own
filter, `tenant_id = 0` = the national default. Same half-open interval contract as
`dim_tenant_water_norm_table`; maintained by the `INCLUDED_WORK_STATUSES_UPDATED` event
handler (close open row + open new one when the set actually changes). Seeded with one open
row per tenant that has a configured filter at migration time (effective from the tenant's
creation date, reproducing the legacy retroactive behaviour for backfills).

| Column | Type | Constraints | Use / Need |
|--------|------|-------------|------------|
| `id` | BIGSERIAL | PRIMARY KEY | Surrogate key |
| `tenant_id` | INT | NOT NULL (no FK — tenant 0 is a config-only sentinel) | Tier owner |
| `effective_from` | DATE | NOT NULL | Start of validity (inclusive) |
| `effective_to` | DATE | NULL = currently in effect | End of validity (exclusive); half-open `[from, to)` |
| `included_work_statuses` | INT[] | NULL/empty = tier not configured (falls through) | The filter set for this tier during the interval |
| `created_at` | TIMESTAMP | NOT NULL, DEFAULT now | Row insert time |

| Index / Constraint | Definition | Use / Need |
|--------------------|------------|------------|
| `uq_dim_tenant_work_status_filter_open` | UNIQUE(`tenant_id`) WHERE `effective_to IS NULL` | At most one open (current) filter row per tier |
| `idx_dim_tenant_work_status_filter_lookup` | (`tenant_id`, `effective_from`, `effective_to`) | Point-in-time filter lookup by date |

---

## 8. `dim_tenant_table` — KPI parameter columns (current values)

`dim_tenant_table` holds the **current** value of each dashboard norm/parameter (the SCD-2 tables
above keep the history). Two columns were added for the KPI work; the water-norm values
(`required_lpcd` V22, `over/under_supply_range_percentage` V30, `person_count_per_household`) predate
this branch and are snapshotted into `dim_tenant_water_norm_table` (V50).

| Column | Type | Migration | Use / Need |
|--------|------|-----------|------------|
| `included_work_statuses` | INT[] | V42 | Current work-status filter set; nightly aggregation resolves the point-in-time value from `dim_tenant_work_status_filter_table` (V49) instead, but legacy read-time SQL still uses this copy |
| `regularity_threshold_percent` | NUMERIC(5,2) | V44 *(from dev)* | % of days a scheme must supply to be "regular". **Deliberately no DEFAULT** — NULL means "not configured" and drives the three-tier fallback (own tenant → national tenant-0 → env default) in `RegularityThresholdFilter` |
