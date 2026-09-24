-- ============================================================
-- Migration: V47 - Retire the GLIFIC_MESSAGE_TEMPLATES tenant config key
-- ------------------------------------------------------------
-- tenant-service, telemetry-service and user-service now know the key by its
-- new name only, so none of them reads a row still stored under the old one.
--
-- V45 renamed the live rows it found, but two kinds can still be live under
-- the old name. Each is resolved the way every reader already resolves it, so
-- no tenant's templates change:
--
--   1. The tenant's only live templates row. An instance still on the old name
--      wrote it after V45 had run, during that rolling deploy. Readers have
--      been reading it through the fallback, and without it the tenant would
--      lose its templates, so step 1 renames it, exactly as V45 renamed its
--      rows.
--   2. A row alongside a live row under the new name. Readers have been
--      reading the new-name row and ignoring this one, so step 2 soft-deletes
--      it, leaving no live row under a name nothing reads.
--
-- Step 1 runs first, so step 2 only ever meets the second kind:
-- uq_tenant_config_key (V15) allows one live row per tenant and key, and
-- step 1 has renamed every live old-name row that had no live new-name twin.
--
-- A renamed row keeps its updated_at, because its value has not changed. A
-- soft-deleted row gets updated_at stamped, as tenant-service's own soft
-- delete stamps it; deleted_by stays null, because no user deleted it.
--
-- Rows already soft-deleted under the old name are left as they are. Nothing
-- reads them.
-- ============================================================

-- Step 1: rename a live old-name row that has no live new-name twin.
UPDATE common_schema.tenant_config_master_table AS legacy
   SET config_key = 'WHATSAPP_MESSAGE_TEMPLATES'
 WHERE legacy.config_key = 'GLIFIC_MESSAGE_TEMPLATES'
   AND legacy.deleted_at IS NULL
   AND NOT EXISTS (
       SELECT 1
         FROM common_schema.tenant_config_master_table AS canonical
        WHERE canonical.tenant_id = legacy.tenant_id
          AND canonical.config_key = 'WHATSAPP_MESSAGE_TEMPLATES'
          AND canonical.deleted_at IS NULL
   );

-- Step 2: soft-delete every live old-name row that is left.
UPDATE common_schema.tenant_config_master_table
   SET deleted_at = NOW(),
       updated_at = NOW()
 WHERE config_key = 'GLIFIC_MESSAGE_TEMPLATES'
   AND deleted_at IS NULL;
