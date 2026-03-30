-- ============================================================
-- DIM DEPARTMENT LOCATION TABLE
-- ============================================================
ALTER TABLE analytics_schema.dim_department_location_table
ADD COLUMN IF NOT EXISTS geom GEOMETRY;