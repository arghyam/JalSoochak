-- ============================================================
-- V60 - The unit each reading was submitted in
-- ------------------------------------------------------------
-- fact_meter_reading_table
--   + submitted_unit  VARCHAR(16)  -- flow_reading_table.submitted_unit, the reading's UCUM code
--
-- An electricity meter can be read on its kWh register or its kVAh register, and telemetry stores
-- either index as the meter shows it. The water quantity needs to know which: a kVAh day's increase
-- is turned into kWh with the pumps' power factor, and a reading on one register is never the
-- starting point for a reading on the other. See MeterRegister.
--
-- Nullable with no default, so adding it does not rewrite the table. NULL means the channel's
-- standard unit (m3 for BFM, kWh for ELM, minutes for PDU), which is what every row written before
-- this migration, and every event from a telemetry-service that does not send the unit yet, is in.
-- Same width as the source column (tenant V56).
-- ============================================================

ALTER TABLE analytics_schema.fact_meter_reading_table
    ADD COLUMN IF NOT EXISTS submitted_unit VARCHAR(16);
