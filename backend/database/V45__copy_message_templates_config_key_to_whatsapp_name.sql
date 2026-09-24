-- ============================================================
-- Migration: V45 - Copy the GLIFIC_MESSAGE_TEMPLATES tenant config key
--                  to WHATSAPP_MESSAGE_TEMPLATES
-- ------------------------------------------------------------
-- The key holds the WhatsApp conversation's screens, prompts and options,
-- and its old name was the messaging vendor's rather than the channel's.
--
-- The rows are copied, not renamed, because code that knows only the old
-- name keeps running after this migration:
--   * Flyway runs when the first new tenant-service pod starts, while old
--     tenant-, telemetry- and user-service pods are still serving.
--   * A rollback leaves this migration applied.
-- Both read the old name only, so its row stays where it is. New code reads
-- the new name first: tenant-service prefers the new-name row, and telemetry-
-- and user-service fall back to the old name only when the new one is absent.
-- A tenant-service built before non-UI config keys were skipped on read
-- rejects the new-name row, so do not roll back past that fix.
--
-- tenant-service writes only the new name, so the old-name row keeps the
-- value it had here. That is what an old pod or a rollback sees. The
-- old-name rows are removed together with the alias.
--
-- Only live rows are copied. uq_tenant_config_key (V15) is a partial unique
-- index on (tenant_id, config_key) WHERE deleted_at IS NULL. A tenant that
-- already has a live new-name row keeps it, and its old-name row is not
-- copied over it.
--
-- The copy keeps the created and updated audit columns, because the value
-- itself has not changed.
--
-- No migration seeds this key, so every row copied here was written at
-- runtime through PUT /api/v1/tenants/{tenantId}/config.
-- ============================================================

INSERT INTO common_schema.tenant_config_master_table
       (tenant_id, config_key, config_value, created_at, created_by, updated_at, updated_by)
SELECT legacy.tenant_id,
       'WHATSAPP_MESSAGE_TEMPLATES',
       legacy.config_value,
       legacy.created_at,
       legacy.created_by,
       legacy.updated_at,
       legacy.updated_by
  FROM common_schema.tenant_config_master_table AS legacy
 WHERE legacy.config_key = 'GLIFIC_MESSAGE_TEMPLATES'
   AND legacy.deleted_at IS NULL
ON CONFLICT (tenant_id, config_key) WHERE deleted_at IS NULL DO NOTHING;
