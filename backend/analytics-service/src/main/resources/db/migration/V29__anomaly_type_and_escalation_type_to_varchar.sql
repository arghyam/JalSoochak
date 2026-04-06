-- Change type columns from INTEGER to VARCHAR; existing values are cast with USING ...::text.

ALTER TABLE analytics_schema.anomaly_table
    ALTER COLUMN type TYPE VARCHAR(128) USING type::text;

ALTER TABLE analytics_schema.fact_escalation_table
    ALTER COLUMN escalation_type TYPE VARCHAR(128) USING escalation_type::text;
