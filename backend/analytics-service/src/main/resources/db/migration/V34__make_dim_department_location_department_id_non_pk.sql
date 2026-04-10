-- Make dim_department_location_table.department_id non-PK / non-unique.

ALTER TABLE analytics_schema.dim_department_location_table
    DROP CONSTRAINT IF EXISTS dim_department_location_table_pkey;

