-- =============================================================================
-- 10x spike correction  —  ANALYTICS DB  —  ROLLBACK
-- =============================================================================
-- RUN AGAINST THE ANALYTICS DATABASE. Reverts decimalfix10x_analytics_02_apply.sql
-- for rows currently applied = TRUE.
-- =============================================================================

BEGIN;

-- Restore meter-reading facts from the saved old values, but only where the fact still holds the
-- corrected (new) value — so an external change made after apply is never clobbered. Capture which
-- candidates were actually reverted; water-quantity restore and reopening derive from that set.
CREATE TEMP TABLE reverted_now (fix_id BIGINT PRIMARY KEY, scheme_id INTEGER, reading_date DATE) ON COMMIT DROP;

WITH rev AS (
    UPDATE analytics_schema.fact_meter_reading_table fmr
       SET extracted_reading = f.old_extracted,
           confirmed_reading = f.old_confirmed
      FROM public.flow_reading_10x_fix_analytics f
     WHERE f.applied = TRUE
       AND fmr.id = f.fmr_id
       AND fmr.confirmed_reading = f.new_confirmed
       AND fmr.extracted_reading = f.new_extracted   -- only undo rows we actually set (both columns)
    RETURNING f.fix_id, f.scheme_id, f.reading_date
)
INSERT INTO reverted_now (fix_id, scheme_id, reading_date)
SELECT fix_id, scheme_id, reading_date FROM rev;

-- Restore water-quantity facts from backup, only for the D / D+1 of successfully reverted targets
UPDATE analytics_schema.fact_water_quantity_table fwq
   SET water_quantity = b.water_quantity,
       updated_at     = b.updated_at
  FROM public.fact_water_quantity_backup_10x b
  JOIN (
        SELECT DISTINCT scheme_id, d::date AS date
        FROM (
            SELECT scheme_id, reading_date     AS d FROM reverted_now
            UNION
            SELECT scheme_id, reading_date + 1 AS d FROM reverted_now
        ) u
     ) t
     ON  b.tenant_id = 1 AND b.scheme_id = t.scheme_id AND b.date = t.date
 WHERE b.id = fwq.id;

-- Reopen only the candidates actually reverted
UPDATE public.flow_reading_10x_fix_analytics t
   SET applied = FALSE, applied_at = NULL
  FROM reverted_now r
 WHERE t.fix_id = r.fix_id;

-- === Rollback complete ===
COMMIT;
