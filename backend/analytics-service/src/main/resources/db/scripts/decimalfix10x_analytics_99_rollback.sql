-- =============================================================================
-- 10x spike correction  —  ANALYTICS DB  —  ROLLBACK
-- =============================================================================
-- RUN AGAINST THE ANALYTICS DATABASE. Reverts decimalfix10x_analytics_02_apply.sql
-- for rows currently applied = TRUE.
-- =============================================================================

BEGIN;

-- Restore meter-reading facts from the saved old values
UPDATE analytics_schema.fact_meter_reading_table fmr
   SET extracted_reading = f.old_extracted,
       confirmed_reading = f.old_confirmed
  FROM public.flow_reading_10x_fix_analytics f
 WHERE f.applied = TRUE
   AND fmr.id = f.fmr_id;

-- Restore water-quantity facts from backup
UPDATE analytics_schema.fact_water_quantity_table fwq
   SET water_quantity = b.water_quantity,
       updated_at     = b.updated_at
  FROM public.fact_water_quantity_backup_10x b
 WHERE b.id = fwq.id;

-- Reopen candidates for possible re-apply
UPDATE public.flow_reading_10x_fix_analytics
   SET applied = FALSE, applied_at = NULL
 WHERE applied = TRUE;

-- === Rollback complete ===
COMMIT;
