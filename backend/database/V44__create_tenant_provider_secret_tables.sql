-- ============================================================
-- Migration: V44 - Per-tenant messaging provider secret store
-- ------------------------------------------------------------
-- MESSAGING-PROVIDER-SECRETS
--
-- Envelope encryption for the credentials a tenant's own email/SMS provider
-- account needs (SendGrid apiKey, SMTP password, SMSCountry authKey and
-- authToken). Nothing reads these rows yet — message-service starts consuming
-- them in a later change.
--
--   MESSAGING_SECRET_MASTER_KEY_V<n>   (env var, base64 32 bytes, never in the DB)
--         |  AES-256-GCM wrap, AAD = "<tenantId>|<keyVersion>|<masterKeyId>"
--         v
--   tenant_secret_key      (tenant_id, key_version) -> wrapped_key
--         |  AES-256-GCM, AAD = "<tenantId>|<channel>|<secretName>|<keyVersion>"
--         v
--   tenant_provider_secret (tenant_id, channel, secret_name) -> ciphertext
--
-- Why a per-tenant data key (DEK) rather than encrypting every secret directly
-- under the master key (KEK):
--
--   1. ROTATION COST. Rotating the KEK re-wraps one row per tenant; the secret
--      ciphertexts are never touched. Encrypting directly under the KEK would
--      mean decrypting and re-encrypting every secret in the platform.
--   2. BLAST RADIUS. A state that believes its credentials are exposed can have
--      just its own DEK rotated (new key_version, old one RETIRED) without
--      touching any other tenant's rows.
--   3. The KEK never has to leave the deployment environment to do bulk work.
--
-- master_key_id records WHICH KEK wrapped each DEK, so two KEKs can be readable
-- at once during a rotation and ops can audit progress in plain SQL
-- ("SELECT DISTINCT master_key_id FROM ...") instead of inside an opaque keyset
-- blob.
--
-- The AAD on both levels binds each ciphertext to its own row: a wrapped_key or
-- a ciphertext copied to another tenant, channel, secret name or key version
-- fails authentication instead of decrypting to the wrong tenant's credential.
--
-- Both tables live in common_schema, so create_tenant_schema() is deliberately
-- NOT touched by this migration.
--
-- Every related change is marked "MESSAGING-PROVIDER-SECRETS".
-- ============================================================

-- ── Per-tenant data key, wrapped by the master key ──────────────────────────
CREATE TABLE common_schema.tenant_secret_key (
    id            SERIAL       PRIMARY KEY,
    tenant_id     INTEGER      NOT NULL,
    key_version   INTEGER      NOT NULL,
    wrapped_key   TEXT         NOT NULL,                    -- base64(iv || AES-GCM(KEK, DEK))
    master_key_id VARCHAR(32)  NOT NULL,                    -- which KEK wrapped it
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | RETIRED
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by    INTEGER,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by    INTEGER,

    CONSTRAINT uq_tenant_secret_key UNIQUE (tenant_id, key_version),
    CONSTRAINT fk_tenant_secret_key_tenant
        FOREIGN KEY (tenant_id) REFERENCES common_schema.tenant_master_table(id),
    CONSTRAINT fk_tenant_secret_key_created_by
        FOREIGN KEY (created_by) REFERENCES common_schema.tenant_admin_user_master_table(id),
    CONSTRAINT fk_tenant_secret_key_updated_by
        FOREIGN KEY (updated_by) REFERENCES common_schema.tenant_admin_user_master_table(id)
);

-- At most one usable key per tenant. RETIRED versions are kept so a rotation is
-- auditable, and so an old ciphertext that somehow escaped re-encryption can
-- still be traced to the key it was written under.
CREATE UNIQUE INDEX uq_tenant_secret_key_active
    ON common_schema.tenant_secret_key (tenant_id)
    WHERE status = 'ACTIVE';

-- ── One row per (tenant, channel, secret name) ──────────────────────────────
CREATE TABLE common_schema.tenant_provider_secret (
    id           SERIAL       PRIMARY KEY,
    uuid         VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    tenant_id    INTEGER      NOT NULL,
    channel      VARCHAR(16)  NOT NULL,   -- EMAIL | SMS  (WHATSAPP reserved, unused)
    secret_name  VARCHAR(64)  NOT NULL,   -- apiKey | password | authKey | authToken
    ciphertext   TEXT         NOT NULL,   -- base64(iv || AES-GCM(DEK, value))
    key_version  INTEGER      NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by   INTEGER,
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by   INTEGER,
    deleted_at   TIMESTAMP,
    deleted_by   INTEGER,

    -- Unconditional, unlike tenant_config_master_table's partial unique index:
    -- a soft-deleted secret must be REVIVED by the next write of the same name
    -- rather than sitting beside a second live row, so the upsert's ON CONFLICT
    -- has to see deleted rows too.
    CONSTRAINT uq_tenant_provider_secret UNIQUE (tenant_id, channel, secret_name),
    -- Composite FK, not just tenant_id: it is what makes an orphaned or
    -- cross-version key_version unstorable, so the AAD can trust the row.
    CONSTRAINT fk_tenant_provider_secret_key
        FOREIGN KEY (tenant_id, key_version)
        REFERENCES common_schema.tenant_secret_key (tenant_id, key_version),
    CONSTRAINT fk_tenant_provider_secret_created_by
        FOREIGN KEY (created_by) REFERENCES common_schema.tenant_admin_user_master_table(id),
    CONSTRAINT fk_tenant_provider_secret_updated_by
        FOREIGN KEY (updated_by) REFERENCES common_schema.tenant_admin_user_master_table(id),
    CONSTRAINT fk_tenant_provider_secret_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES common_schema.tenant_admin_user_master_table(id)
);

-- The read path always asks for one tenant's live secrets on one channel.
CREATE INDEX idx_tenant_provider_secret_tenant_channel
    ON common_schema.tenant_provider_secret (tenant_id, channel)
    WHERE deleted_at IS NULL;
