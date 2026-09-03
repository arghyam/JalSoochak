-- water_quantity is now denominated in LITRES, not the meter's native m3/KL.
--
-- Every consumer of this column already reads it as litres (total_water_supplied_liters in the CSV
-- export and the JSON DTOs, avgKld = litres/1000 and avgLpcd = litres/population in the officer daily
-- report, and the litre-denominated efficient-range and performance-score formulas), so the column was
-- silently 1000x low. The fix multiplies at the analytics ingestion boundary; this migration gives the
-- column the range that multiplication needs.
--
-- INT tops out at ~2.15e9, i.e. any daily delta above ~2.15 million m3 overflows once multiplied.
-- Such deltas exist in the current data (a missing previous-day reading made the whole cumulative
-- meter index the day's supply), and an overflow inside the Kafka consumer retries forever and stalls
-- the partition. BIGINT removes that failure mode outright.
--
-- Invisible above the entity: every water-quantity response field is already Long/BigDecimal
-- (ChildRegionWaterQuantityMetrics.waterQuantity, AverageWaterSupplyResponse.totalWaterSuppliedLiters,
-- PeriodicWaterQuantityMetrics.averageWaterQuantity) and every SQL aggregate over the column already
-- casts ::bigint.
--
-- NOTE: INT -> BIGINT is not binary-coercible in Postgres, so this rewrites the table under an
-- ACCESS EXCLUSIVE lock. Seconds at this row count; deploy in a low-traffic window.
--
-- lock_timeout is scoped to this migration transaction only (Flyway wraps each migration in one,
-- so SET LOCAL reverts automatically at commit). Without it, a request queued behind a long-running
-- query on this table would sit waiting indefinitely for the ACCESS EXCLUSIVE lock — and every
-- later request, reads included, queues behind that one waiter in turn, turning a slow query into a
-- full outage on the table. Failing fast surfaces that contention as a failed deploy instead.
SET LOCAL lock_timeout = '5s';

ALTER TABLE analytics_schema.fact_water_quantity_table
    ALTER COLUMN water_quantity TYPE BIGINT;
