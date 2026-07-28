-- =============================================================================
-- 10x spike correction  —  ANALYTICS DB  —  STEP 2: APPLY
-- =============================================================================
-- RUN AGAINST THE ANALYTICS DATABASE. Pre-req: decimalfix10x_analytics_01_identify.sql
-- has run and public.flow_reading_10x_fix_analytics has been reviewed/curated.
--
-- One transaction, idempotent (value-guarded; water-quantity is recomputed):
--   Phase 0  back up the water-quantity rows about to change
--   Phase B  fact_meter_reading_table   : extracted/confirmed /= 10 (floored INTEGER)
--   Phase C  fact_water_quantity_table  : recompute spike day D and next day D+1,
--            exactly as analytics-service does
--            (max(0, confirmed(D) - confirmed(previous calendar day or 0))).
-- =============================================================================

BEGIN;

-- Phase 0: back up water-quantity rows for D and D+1
CREATE TABLE IF NOT EXISTS public.fact_water_quantity_backup_10x
    (LIKE analytics_schema.fact_water_quantity_table INCLUDING DEFAULTS);

INSERT INTO public.fact_water_quantity_backup_10x
SELECT fwq.*
FROM   analytics_schema.fact_water_quantity_table fwq
JOIN (
        SELECT DISTINCT scheme_id, d::date AS date
        FROM (
            SELECT scheme_id, reading_date     AS d FROM public.flow_reading_10x_fix_analytics WHERE applied = FALSE
            UNION
            SELECT scheme_id, reading_date + 1 AS d FROM public.flow_reading_10x_fix_analytics WHERE applied = FALSE
        ) u
     ) t
     ON  fwq.tenant_id = 1 AND fwq.scheme_id = t.scheme_id AND fwq.date = t.date
WHERE NOT EXISTS (SELECT 1 FROM public.fact_water_quantity_backup_10x b WHERE b.id = fwq.id);

-- Phase B: correct the analytics meter-reading facts
UPDATE analytics_schema.fact_meter_reading_table fmr
   SET extracted_reading = f.new_extracted,
       confirmed_reading = f.new_confirmed
  FROM public.flow_reading_10x_fix_analytics f
 WHERE f.applied = FALSE
   AND fmr.id = f.fmr_id
   AND fmr.confirmed_reading = f.old_confirmed;   -- value guard (idempotent)

-- Phase C: recompute water quantity for D and D+1
WITH targets AS (
    SELECT DISTINCT scheme_id, d::date AS date
    FROM (
        SELECT scheme_id, reading_date     AS d FROM public.flow_reading_10x_fix_analytics WHERE applied = FALSE
        UNION
        SELECT scheme_id, reading_date + 1 AS d FROM public.flow_reading_10x_fix_analytics WHERE applied = FALSE
    ) u
),
recomputed AS (
    SELECT t.scheme_id, t.date,
           cur.confirmed_reading AS cur_reading,
           GREATEST(0, cur.confirmed_reading - COALESCE(prv.confirmed_reading, 0)) AS wq
    FROM   targets t
    LEFT JOIN LATERAL (
        SELECT confirmed_reading FROM analytics_schema.fact_meter_reading_table f
        WHERE  f.tenant_id = 1 AND f.scheme_id = t.scheme_id AND f.reading_date = t.date
        ORDER  BY f.reading_at DESC, f.id DESC LIMIT 1
    ) cur ON TRUE
    LEFT JOIN LATERAL (
        SELECT confirmed_reading FROM analytics_schema.fact_meter_reading_table f
        WHERE  f.tenant_id = 1 AND f.scheme_id = t.scheme_id AND f.reading_date = t.date - 1
        ORDER  BY f.reading_at DESC, f.id DESC LIMIT 1
    ) prv ON TRUE
)
UPDATE analytics_schema.fact_water_quantity_table fwq
   SET water_quantity = rc.wq, updated_at = NOW()
  FROM recomputed rc
 WHERE rc.cur_reading IS NOT NULL
   AND fwq.tenant_id = 1 AND fwq.scheme_id = rc.scheme_id AND fwq.date = rc.date
   AND fwq.id = (SELECT max(id) FROM analytics_schema.fact_water_quantity_table x
                 WHERE x.tenant_id = 1 AND x.scheme_id = fwq.scheme_id AND x.date = fwq.date);

-- Mark applied
UPDATE public.flow_reading_10x_fix_analytics
   SET applied = TRUE, applied_at = NOW()
 WHERE applied = FALSE;

-- Summary  (=== analytics rows fixed this run ===)
SELECT count(*) AS analytics_rows_fixed
FROM   public.flow_reading_10x_fix_analytics
WHERE  applied = TRUE AND applied_at >= NOW() - INTERVAL '1 minute';

COMMIT;

-- Done. If dashboards read the analytics Redis cache, invalidate/warm it.
