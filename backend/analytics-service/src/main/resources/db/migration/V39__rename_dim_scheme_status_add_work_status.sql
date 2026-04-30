-- Rename scheme status column to operating_status and add work_status.
--
-- operating_status semantics:
--   0   => INACTIVE
--   > 0 => ACTIVE
--
-- work_status: reserved for future workflow state tracking.

ALTER TABLE analytics_schema.dim_scheme_table
    RENAME COLUMN status TO operating_status;

-- Ensure existing rows have a defined operating_status.
UPDATE analytics_schema.dim_scheme_table
SET operating_status = 0
WHERE operating_status IS NULL;

ALTER TABLE analytics_schema.dim_scheme_table
    ALTER COLUMN operating_status SET DEFAULT 0;

ALTER TABLE analytics_schema.dim_scheme_table
    ALTER COLUMN operating_status SET NOT NULL;

ALTER TABLE analytics_schema.dim_scheme_table
    ADD COLUMN IF NOT EXISTS work_status INT;

