-- Meter readings are decimal at the source and were being stored here as whole cubic metres.
--
-- The meters carry a decimal digit — FlowVision extracts it as the "red last digit" — and the source of
-- truth, tenant_<state>.flow_reading_table.extracted_reading / .confirmed_reading, is NUMERIC. Analytics
-- narrowed both to INT, and telemetry rounded HALF_UP before publishing, so up to 0.5 m3 was discarded
-- per reading. A daily volume is the difference of two readings, so that is up to +/-1000 L of error on
-- every daily delta once the value is denominated in litres (V45).
--
-- These columns therefore take the source's own type: bare NUMERIC, not a scale this warehouse invents.
-- The DW should not impose a precision the system of record does not have.
--
-- Existing rows are unaffected in value — every stored reading is already a whole number and stays one.
-- Postgres numeric equality ignores trailing zeros, so the extracted_reading = confirmed_reading and
-- IS DISTINCT FROM predicates behind the compliant/anomalous submission counts continue to hold for
-- them. Going forward those comparisons become exact rather than post-rounding, which is the intent:
-- an operator correcting 1235.4 to 1235.6 is a real correction and now counts as one.
--
-- No history is repaired by this change and none can be: the discarded decimals were lost at publish
-- time, and the full-precision flow_reading_table lives in a different production database. Readings
-- written from here on carry their decimals; earlier ones stay whole.
--
-- NOTE: INT -> NUMERIC is not binary-coercible, so this rewrites the table under an ACCESS EXCLUSIVE
-- lock. No index covers either column (the table's indexes are on tenant_id, scheme_id, reading_date and
-- the tenant_id/scheme_id pair), so the rewrite is the whole cost.
--
-- lock_timeout is scoped to this migration transaction only (Flyway wraps each migration in one, so
-- SET LOCAL reverts automatically at commit). Without it, a request queued behind a long-running query
-- on this table would sit waiting indefinitely for the ACCESS EXCLUSIVE lock — and every later request,
-- reads included, queues behind that one waiter in turn, turning a slow query into a full outage on the
-- table. Failing fast surfaces that contention as a failed deploy instead.
SET LOCAL lock_timeout = '5s';

ALTER TABLE analytics_schema.fact_meter_reading_table
    ALTER COLUMN extracted_reading TYPE NUMERIC,
    ALTER COLUMN confirmed_reading TYPE NUMERIC;
