ALTER TABLE analytics_schema.dim_tenant_table
ADD COLUMN IF NOT EXISTS person_count_per_household INT DEFAULT 5,
ADD COLUMN IF NOT EXISTS over_supply_range_percentage INT DEFAULT 10,
ADD COLUMN IF NOT EXISTS under_supply_range_percentage INT DEFAULT 10;
