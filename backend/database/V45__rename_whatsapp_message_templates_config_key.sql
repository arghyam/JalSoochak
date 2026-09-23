-- ============================================================
-- Migration: V45 - Rename the GLIFIC_MESSAGE_TEMPLATES tenant config key
--                  to WHATSAPP_MESSAGE_TEMPLATES
-- ------------------------------------------------------------
-- The key holds the WhatsApp conversation's screens, prompts and options,
-- and its old name was the messaging vendor's rather than the channel's.
--
-- Callers still using the old name keep working. tenant-service accepts it as
-- a deprecated alias, resolves it to the new name before anything is read or
-- stored, and returns the value under both names. telemetry-service and
-- user-service read the new name and fall back to the old one, because
-- neither runs Flyway, so neither can tell whether this migration has run.
--
-- Only live rows are renamed. uq_tenant_config_key (V15) is a partial unique
-- index on (tenant_id, config_key) WHERE deleted_at IS NULL, so soft-deleted
-- rows cannot collide with anything, and they keep the old name.
--
-- If a tenant already has a live row under the new name, its old-name row is
-- left as it is, rather than failing the migration on that index.
-- tenant-service reads the new-name row and ignores the old one.
--
-- updated_at is left alone, because the value itself has not changed.
--
-- No migration seeds this key, so every row changed here was written at
-- runtime through PUT /api/v1/tenants/{tenantId}/config.
-- ============================================================

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
