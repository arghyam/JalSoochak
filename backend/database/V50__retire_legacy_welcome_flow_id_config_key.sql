-- ============================================================
-- Migration: V50 - Retire the glific_welcome_flow_id tenant config key
-- ------------------------------------------------------------
-- message-service now reads a tenant's welcome flow id from welcome_flow_id
-- only. Until now it fell back to glific_welcome_flow_id when the tenant had
-- no welcome_flow_id row. That read ignores deleted_at and takes the newest
-- row by updated_at, then id, so "no row" meant none in any state, live or
-- soft-deleted, and a soft-deleted row could be the one it returned.
--
-- Each row under the old name is resolved the way that read resolved it, so
-- no tenant's welcome flow changes:
--
--   1. The tenant has no welcome_flow_id row. The fallback was reading this
--      tenant's old-name rows, so step 1 renames all of them, soft-deleted
--      ones included, and the read goes on returning the same row.
--   2. The tenant has a welcome_flow_id row. The read returned that row and
--      never reached the old name, so step 2 soft-deletes the live old-name
--      row, leaving no live row under a name nothing reads.
--
-- Step 1 cannot break uq_tenant_config_key (V15), which allows one live row
-- per tenant and key: a tenant it renames has no welcome_flow_id row at all.
--
-- A renamed row keeps its updated_at, because its value has not changed and
-- the read orders by it. A soft-deleted row gets updated_at stamped, as
-- tenant-service's own soft delete stamps it; deleted_by stays null, because
-- no user deleted it.
--
-- Rows already soft-deleted under the old name in case 2 are left as they
-- are. Nothing reads them.
-- ============================================================

-- Step 1: rename every old-name row of a tenant that has no welcome_flow_id row.
UPDATE common_schema.tenant_config_master_table AS legacy
   SET config_key = 'welcome_flow_id'
 WHERE legacy.config_key = 'glific_welcome_flow_id'
   AND NOT EXISTS (
       SELECT 1
         FROM common_schema.tenant_config_master_table AS canonical
        WHERE canonical.tenant_id = legacy.tenant_id
          AND canonical.config_key = 'welcome_flow_id'
   );

-- Step 2: soft-delete every live old-name row that is left.
UPDATE common_schema.tenant_config_master_table
   SET deleted_at = NOW(),
       updated_at = NOW()
 WHERE config_key = 'glific_welcome_flow_id'
   AND deleted_at IS NULL;
