-- ============================================================
-- DIM DATE — Sunday-aligned week columns
-- ============================================================
-- Weeks run Sunday -> Saturday everywhere. PostgreSQL EXTRACT(DOW) returns
-- 0 for Sunday, so the Sunday that starts a date's week is:
--   week_start_date = full_date - EXTRACT(DOW FROM full_date)
-- and week_end_date = week_start_date + 6 (the Saturday).
-- ============================================================

ALTER TABLE analytics_schema.dim_date_table
    ADD COLUMN IF NOT EXISTS week_start_date DATE,
    ADD COLUMN IF NOT EXISTS week_end_date   DATE;

UPDATE analytics_schema.dim_date_table
SET week_start_date = (full_date - (EXTRACT(DOW FROM full_date))::int),
    week_end_date   = (full_date - (EXTRACT(DOW FROM full_date))::int + 6)
WHERE week_start_date IS NULL
   OR week_end_date IS NULL;

CREATE INDEX IF NOT EXISTS idx_dim_date_week_start
    ON analytics_schema.dim_date_table(week_start_date);
