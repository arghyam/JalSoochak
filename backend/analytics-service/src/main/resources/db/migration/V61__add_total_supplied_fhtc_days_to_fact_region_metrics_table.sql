-- ============================================================
-- V61 - FHTC-days of the schemes that supplied water
-- ------------------------------------------------------------
-- fact_region_metrics_table
--   + total_supplied_fhtc_days  BIGINT NOT NULL DEFAULT 0  -- Σ over the bucket's supply scheme-days of the scheme's achieved FHTC
--
-- The LPCD denominator. LPCD = total_water_supplied_liters ÷ (total_supplied_fhtc_days × persons per
-- household), so a scheme that did not supply on a day adds nothing to that day's population: it no
-- longer dilutes the litres the supplying schemes delivered. A supply scheme-day is one fact_scheme_daily_table
-- row with supplied = 1 ({{SWD}}). The FHTC is the scheme's current count, from the same de-duplicated
-- dim_scheme_table row as total_achieved_fhtc, so a scheme that supplied on every day of the bucket adds
-- its FHTC × days_in_range, just as the old denominator did.
--
-- Additive across day buckets and across regions, like total_supply_days; no ratio is stored (V52).
--
-- A constant default, so adding the column does not rewrite the table. Rows built before this migration
-- read 0 until AggregateBackfillRunner rebuilds them; keep ANALYTICS_READ_FROM_AGGREGATES off until it
-- has finished.
-- ============================================================

ALTER TABLE analytics_schema.fact_region_metrics_table
    ADD COLUMN IF NOT EXISTS total_supplied_fhtc_days BIGINT NOT NULL DEFAULT 0;
