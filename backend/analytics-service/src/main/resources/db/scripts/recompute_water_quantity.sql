-- Canonical recompute of analytics_schema.fact_water_quantity_table.water_quantity from readings.
--
-- Why this file exists as a shared resource rather than as a string inside the backfill script:
-- scripts/water_quantity_units_fix.py reads it to repair history, and
-- WaterQuantityBackfillParityIntegrationTest runs this exact text against the same fixture that live
-- ingestion writes. If the two definitions were separate copies they could drift, and the whole point
-- of the backfill is that it must land on the value FactServiceImpl would have written.
--
-- The definitions below mirror FactServiceImpl.updateWaterQuantityFromReading term for term:
--
--   current  = the latest reading on that date          -- findTopBy...ReadingDateOrderByReadingAtDescIdDesc
--              ordered by reading_at DESC, id DESC,      -- deliberately NOT filtered on > 0: a corrected
--              with no minimum value filter                 reading of 0 is a real 0 for the day
--   previous = the latest reading strictly BEFORE it     -- FactMeterReadingRepository.findLatestBefore
--              with confirmed_reading > 0, ordered by
--              reading_date DESC, reading_at DESC, id DESC
--   quantity = ROUND(GREATEST(0, current - previous)     -- BfmWaterQuantityCalculator + WaterVolumeUnits
--                    * 1000)
--
-- and the two boundary cases that caused the defects this backfill repairs:
--
--   no reading on the date  -> new_qty NULL. Live ingestion writes nothing at all in this case, so the
--                              backfill must not write either. The caller reports any such row whose
--                              stored value is non-zero as an exception instead of touching it.
--   no previous reading     -> 0, NOT the whole meter index. A cumulative index needs a baseline to be
--                              a volume; without one there is no derivable supply for the day.
--
-- Takes no parameters and covers the whole table: callers wrap it in a CTE and apply their own window
-- so that this text stays runnable as-is from psql and from a test.
SELECT fwq.id,
       fwq.tenant_id,
       fwq.scheme_id,
       fwq.date,
       fwq.water_quantity                                  AS old_qty,
       cur.confirmed_reading                               AS current_reading,
       prev.confirmed_reading                              AS previous_reading,
       prev.reading_date                                   AS previous_date,
       CASE
           WHEN cur.confirmed_reading IS NULL THEN NULL
           WHEN prev.confirmed_reading IS NULL THEN 0
           -- The readings are NUMERIC (the meters carry a decimal digit), so the subtraction happens at
           -- their own precision and the result is rounded once, at the litre boundary — the same order
           -- BfmWaterQuantityCalculator and WaterVolumeUnits use. Rounding the readings first and
           -- subtracting after is what cost up to 1000 L per day. ROUND() on numeric rounds
           -- half-away-from-zero, which is the Java HALF_UP for the non-negative values GREATEST admits;
           -- it is spelled out rather than left to the implicit assignment cast into the BIGINT column,
           -- because the parity with the Java path is the point of this file.
           ELSE ROUND(GREATEST(0, cur.confirmed_reading - prev.confirmed_reading) * 1000)
       END::bigint                                         AS new_qty,
       -- Which row of a (tenant, scheme, date) group every consumer actually reads. The table has no
       -- uniqueness on that triple; ingestion and the LATEST_WATER_QUANTITY / DISTINCT ON de-duplication
       -- in SchemeRegularityRepository both resolve it by updated_at DESC, id DESC, so this mirrors that
       -- ordering exactly (plain DESC, i.e. NULLS FIRST, as in the query it mirrors). Recorded rather
       -- than filtered on: the repair sets every row of a group to the same value so that a stray
       -- duplicate cannot be left behind holding a stale cubic-metre figure.
       (row_number() OVER (PARTITION BY fwq.tenant_id, fwq.scheme_id, fwq.date
                           ORDER BY fwq.updated_at DESC, fwq.id DESC) = 1) AS is_latest
FROM analytics_schema.fact_water_quantity_table fwq
LEFT JOIN LATERAL (
    SELECT r.confirmed_reading
    FROM analytics_schema.fact_meter_reading_table r
    WHERE r.tenant_id = fwq.tenant_id
      AND r.scheme_id = fwq.scheme_id
      AND r.reading_date = fwq.date
    ORDER BY r.reading_at DESC, r.id DESC
    LIMIT 1
) cur ON TRUE
LEFT JOIN LATERAL (
    SELECT r.confirmed_reading, r.reading_date
    FROM analytics_schema.fact_meter_reading_table r
    WHERE r.tenant_id = fwq.tenant_id
      AND r.scheme_id = fwq.scheme_id
      AND r.reading_date < fwq.date
      AND r.confirmed_reading > 0
    ORDER BY r.reading_date DESC, r.reading_at DESC, r.id DESC
    LIMIT 1
) prev ON TRUE
