-- Add status column to the user dimension table

ALTER TABLE analytics_schema.dim_user_table
    ADD COLUMN IF NOT EXISTS status INT;
