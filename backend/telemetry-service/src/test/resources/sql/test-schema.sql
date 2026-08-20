-- Minimal schema for Testcontainers-backed repository tests.
-- Mirrors only the columns the queries under test touch (see create_tenant_schema() in
-- backend/database/V27__fix_create_tenant_schema_missing_pii_columns.sql for the real DDL).

CREATE SCHEMA IF NOT EXISTS common_schema;

CREATE TABLE common_schema.user_type_master_table (
    id      SERIAL       PRIMARY KEY,
    c_name  VARCHAR(255) NOT NULL UNIQUE
);

INSERT INTO common_schema.user_type_master_table (id, c_name)
VALUES (1, 'PUMP_OPERATOR'),
       (2, 'SECTION_OFFICER');

-- Tenant schema on the current migration level (user_table has language_id).
CREATE SCHEMA IF NOT EXISTS tenant_as;

CREATE TABLE tenant_as.user_table (
    id           SERIAL    PRIMARY KEY,
    tenant_id    INTEGER   NOT NULL,
    title        TEXT      NOT NULL,
    email        VARCHAR(255),
    user_type    INTEGER   NOT NULL,
    phone_number TEXT      NOT NULL,
    status       INTEGER   NOT NULL,
    language_id  INTEGER,
    deleted_at   TIMESTAMP
);

CREATE TABLE tenant_as.user_scheme_mapping_table (
    id         SERIAL    PRIMARY KEY,
    user_id    INTEGER   NOT NULL,
    scheme_id  INTEGER   NOT NULL,
    status     INTEGER   NOT NULL,
    deleted_at TIMESTAMP
);

-- Tenant schema still on a pre-language_id migration level, to exercise the NULL::integer fallback.
CREATE SCHEMA IF NOT EXISTS tenant_zz;

CREATE TABLE tenant_zz.user_table (
    id           SERIAL    PRIMARY KEY,
    tenant_id    INTEGER   NOT NULL,
    title        TEXT      NOT NULL,
    email        VARCHAR(255),
    user_type    INTEGER   NOT NULL,
    phone_number TEXT      NOT NULL,
    status       INTEGER   NOT NULL,
    deleted_at   TIMESTAMP
);

CREATE TABLE tenant_zz.user_scheme_mapping_table (
    id         SERIAL    PRIMARY KEY,
    user_id    INTEGER   NOT NULL,
    scheme_id  INTEGER   NOT NULL,
    status     INTEGER   NOT NULL,
    deleted_at TIMESTAMP
);
