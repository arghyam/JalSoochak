-- Minimal schema for integration tests.
-- Mirrors the production DDL from backend/database/ migrations.

CREATE SCHEMA IF NOT EXISTS common_schema;
CREATE SCHEMA IF NOT EXISTS tenant_mp;

-- ── Role / user-type lookup ────────────────────────────────────────────────

CREATE TABLE common_schema.user_type_master_table (
    id   SERIAL PRIMARY KEY,
    c_name VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO common_schema.user_type_master_table (id, c_name)
VALUES (1, 'SUPER_USER'), (2, 'STATE_ADMIN');

-- ── Tenant master ──────────────────────────────────────────────────────────

CREATE TABLE common_schema.tenant_master_table (
    id         SERIAL PRIMARY KEY,
    state_code VARCHAR(10)  NOT NULL UNIQUE,
    title      VARCHAR(200) NOT NULL,
    status     INTEGER      NOT NULL DEFAULT 3
);

INSERT INTO common_schema.tenant_master_table (id, state_code, title, status)
VALUES (1, 'MP', 'Madhya Pradesh', 3);

-- ── System users (SUPER_USER / STATE_ADMIN) ────────────────────────────────

CREATE TABLE common_schema.tenant_admin_user_master_table (
    id                          SERIAL       PRIMARY KEY,
    uuid                        VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    email                       VARCHAR(255) NOT NULL,
    phone_number                TEXT         NOT NULL,
    phone_number_hash           TEXT,
    tenant_id                   INTEGER      NOT NULL DEFAULT 0,
    admin_level                 INTEGER      REFERENCES common_schema.user_type_master_table(id),
    password                    TEXT         NOT NULL,
    email_verification_status   BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verification_status   BOOLEAN      NOT NULL DEFAULT FALSE,
    status                      INTEGER      NOT NULL,
    created_by                  INTEGER,
    updated_by                  INTEGER,
    deleted_by                  INTEGER,
    deleted_at                  TIMESTAMP,
    created_at                  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_admin_user_created_by
        FOREIGN KEY (created_by) REFERENCES common_schema.tenant_admin_user_master_table(id),
    CONSTRAINT fk_admin_user_updated_by
        FOREIGN KEY (updated_by) REFERENCES common_schema.tenant_admin_user_master_table(id),
    CONSTRAINT fk_admin_user_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES common_schema.tenant_admin_user_master_table(id)
);

CREATE INDEX idx_admin_user_uuid
    ON common_schema.tenant_admin_user_master_table(uuid);

-- ── Admin token table (V16) ────────────────────────────────────────────────

CREATE TABLE common_schema.admin_user_token_table (
    id          BIGSERIAL    PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    token_type  VARCHAR(20)  NOT NULL,
    metadata    JSONB,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ,
    deleted_by  INTEGER,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  INTEGER,

    CONSTRAINT chk_aut_token_type
        CHECK (token_type IN ('INVITE', 'RESET')),
    CONSTRAINT chk_aut_metadata
        CHECK (
            (token_type = 'INVITE' AND metadata IS NOT NULL) OR
            (token_type = 'RESET'  AND metadata IS NULL)
        ),
    CONSTRAINT fk_aut_deleted_by
        FOREIGN KEY (deleted_by)
        REFERENCES common_schema.tenant_admin_user_master_table(id),
    CONSTRAINT fk_aut_created_by
        FOREIGN KEY (created_by)
        REFERENCES common_schema.tenant_admin_user_master_table(id)
);

CREATE UNIQUE INDEX uq_active_admin_token
    ON common_schema.admin_user_token_table(email, token_type)
    WHERE used_at IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX idx_admin_token_hash
    ON common_schema.admin_user_token_table(token_hash);

CREATE INDEX idx_admin_token_email
    ON common_schema.admin_user_token_table(email);

-- ── Tenant-scoped user table (one example: tenant_mp) ─────────────────────

CREATE TABLE tenant_mp.user_table (
    id                        SERIAL       PRIMARY KEY,
    uuid                      VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    tenant_id                 INTEGER      NOT NULL,
    user_type                 INTEGER      NOT NULL,
    title                     VARCHAR(255),
    email                     VARCHAR(255) UNIQUE,
    phone_number              TEXT,
    phone_number_hash         TEXT,
    title_hash                TEXT,
    password                  TEXT,
    status                    INTEGER      NOT NULL,
    email_verification_status BOOLEAN,
    phone_verification_status BOOLEAN,
    language_id               INTEGER,
    whatsapp_connection_id    BIGINT,
    created_by                BIGINT,
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by                BIGINT,
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at                TIMESTAMP,
    deleted_by                INTEGER
);

-- ── OTP table (V24) ────────────────────────────────────────────────────────

CREATE TABLE common_schema.otp_table (
    id            BIGSERIAL    PRIMARY KEY,
    otp           TEXT         NOT NULL,
    tenant_id     INTEGER      NOT NULL,
    user_id       BIGINT       NOT NULL,
    otp_type      VARCHAR(30)  NOT NULL,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    CONSTRAINT chk_otp_type CHECK (otp_type IN ('LOGIN', 'PASSWORD_CHANGE'))
);

CREATE UNIQUE INDEX uq_active_otp
    ON common_schema.otp_table(user_id, tenant_id, otp_type)
    WHERE used_at IS NULL;

CREATE INDEX idx_otp_expires ON common_schema.otp_table(expires_at);
CREATE INDEX idx_otp_tenant  ON common_schema.otp_table(tenant_id);
CREATE INDEX idx_otp_user_tenant_type_active
    ON common_schema.otp_table(user_id, tenant_id, otp_type, expires_at)
    WHERE used_at IS NULL;