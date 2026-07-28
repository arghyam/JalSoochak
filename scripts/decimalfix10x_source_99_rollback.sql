-- =============================================================================
-- 10x spike correction  —  SOURCE DB (jalsoochak)  —  ROLLBACK
-- =============================================================================
-- RUN AGAINST THE SOURCE DATABASE. Reverts decimalfix10x_source_02_apply.sql
-- for rows currently applied = TRUE, restoring readings + payload_json.
-- =============================================================================

BEGIN;

-- Restore only rows still holding the corrected (new) values, so an external edit made after apply is
-- never clobbered; reopen only the candidates actually reverted.
WITH reverted AS (
    UPDATE tenant_as.flow_reading_table s
       SET extracted_reading = f.old_extracted,
           confirmed_reading = f.old_confirmed,
           payload_json      = f.old_payload,
           updated_at        = NOW()
      FROM public.flow_reading_10x_fix_source f
     WHERE f.applied = TRUE
       AND s.id = f.src_id
       AND s.confirmed_reading = f.new_confirmed
       AND s.extracted_reading = f.new_extracted   -- only undo rows we actually set
    RETURNING f.fix_id
)
UPDATE public.flow_reading_10x_fix_source t
   SET applied = FALSE, applied_at = NULL
  FROM reverted r
 WHERE t.fix_id = r.fix_id;

-- === Rollback complete ===
COMMIT;
