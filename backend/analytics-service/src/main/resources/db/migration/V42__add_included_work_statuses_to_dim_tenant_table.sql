ALTER TABLE analytics_schema.dim_tenant_table
    ADD COLUMN IF NOT EXISTS included_work_statuses INT[];
