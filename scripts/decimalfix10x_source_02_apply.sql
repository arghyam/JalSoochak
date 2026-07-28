-- =============================================================================
-- 10x spike correction  —  SOURCE DB (jalsoochak)  —  STEP 2: APPLY
-- =============================================================================
-- RUN AGAINST THE SOURCE DATABASE. Pre-req: decimalfix10x_source_01_identify.sql
-- has run and public.flow_reading_10x_fix_source has been reviewed/curated.
--
-- One transaction, idempotent (value-guarded). Divides extracted/confirmed by 10
-- and rebuilds payload_json the way the app builds it
-- (jsonb_build_object('confirmed_reading', ..., 'extracted_reading', ...)).
-- =============================================================================

BEGIN;

-- Correct the readings and mark applied only for rows the value-guarded UPDATE actually mutated. A
-- candidate whose row was already fixed / changed / deleted fails the guard, is not returned, and stays
-- applied = FALSE — so a later rollback only touches rows this run really changed.
WITH updated AS (
    UPDATE tenant_as.flow_reading_table s
       SET extracted_reading = f.new_extracted,
           confirmed_reading = f.new_confirmed,
           payload_json      = jsonb_build_object(
                                   'confirmed_reading', COALESCE(f.new_confirmed, 0),
                                   'extracted_reading', COALESCE(f.new_extracted, 0)),
           updated_at        = NOW()
      FROM public.flow_reading_10x_fix_source f
     WHERE f.applied = FALSE
       AND s.id = f.src_id
       AND s.deleted_at IS NULL
       AND s.confirmed_reading = f.old_confirmed   -- value guard (idempotent)
    RETURNING f.fix_id
)
UPDATE public.flow_reading_10x_fix_source t
   SET applied = TRUE, applied_at = NOW()
  FROM updated u
 WHERE t.fix_id = u.fix_id;

-- Summary  (=== source rows fixed this run ===)
SELECT count(*) AS source_rows_fixed
FROM   public.flow_reading_10x_fix_source
WHERE  applied = TRUE AND applied_at >= NOW() - INTERVAL '1 minute';

COMMIT;
