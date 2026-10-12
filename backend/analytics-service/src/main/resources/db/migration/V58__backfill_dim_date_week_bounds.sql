-- ============================================================
-- V58 - Backfill dim_date_table week bounds
-- ------------------------------------------------------------
-- V50 added week_start_date / week_end_date and filled the rows that existed then, but
-- DateDimensionServiceImpl (DimDateBackfillTask's nightly insert) did not set them until now, so
-- every row it created after V50 has both NULL. Same Sunday -> Saturday derivation as V50:
-- EXTRACT(DOW) is 0 for Sunday.
-- ============================================================

UPDATE analytics_schema.dim_date_table
SET week_start_date = (full_date - (EXTRACT(DOW FROM full_date))::int),
    week_end_date   = (full_date - (EXTRACT(DOW FROM full_date))::int + 6)
WHERE week_start_date IS NULL
   OR week_end_date IS NULL;
