-- =============================================================================
-- 10x spike correction  —  SOURCE DB (jalsoochak)  —  ROLLBACK
-- =============================================================================
-- RUN AGAINST THE SOURCE DATABASE. Reverts decimalfix10x_source_02_apply.sql
-- for rows currently applied = TRUE, restoring readings + payload_json.
-- =============================================================================

BEGIN;

UPDATE tenant_as.flow_reading_table s
   SET extracted_reading = f.old_extracted,
       confirmed_reading = f.old_confirmed,
       payload_json      = f.old_payload,
       updated_at        = NOW()
  FROM public.flow_reading_10x_fix_source f
 WHERE f.applied = TRUE
   AND s.id = f.src_id;

UPDATE public.flow_reading_10x_fix_source
   SET applied = FALSE, applied_at = NULL
 WHERE applied = TRUE;

-- === Rollback complete ===
COMMIT;
