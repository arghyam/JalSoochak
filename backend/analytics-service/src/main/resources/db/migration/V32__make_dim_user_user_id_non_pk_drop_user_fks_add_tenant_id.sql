-- Make dim_user_table.user_id non-PK/non-unique, drop user-related FKs,
-- and add tenant_id column if missing (analytics_schema).

-- 1) Add tenant_id if it doesn't exist (no constraint added here)
ALTER TABLE analytics_schema.dim_user_table
    ADD COLUMN IF NOT EXISTS tenant_id INT;

-- 2) Drop FKs that reference dim_user_table(user_id) (must come before dropping PK)
ALTER TABLE analytics_schema.dim_user_scheme_mapping_table
    DROP CONSTRAINT IF EXISTS dim_user_scheme_mapping_table_user_id_fkey;

ALTER TABLE analytics_schema.dim_operator_attendance_table
    DROP CONSTRAINT IF EXISTS dim_operator_attendance_table_user_id_fkey;

ALTER TABLE analytics_schema.dim_operator_attendance_table
    DROP CONSTRAINT IF EXISTS dim_operator_attendance_table_remark_by_fkey;

-- 3) Drop PK on dim_user_table(user_id) so user_id is non-unique/non-primary
ALTER TABLE analytics_schema.dim_user_table
    DROP CONSTRAINT IF EXISTS dim_user_table_pkey;

-- 4) Add nullable tenant_id columns where needed
ALTER TABLE analytics_schema.dim_user_scheme_mapping_table
    ADD COLUMN IF NOT EXISTS tenant_id INT;

ALTER TABLE analytics_schema.anomaly_table
    ADD COLUMN IF NOT EXISTS tenant_id INT;



