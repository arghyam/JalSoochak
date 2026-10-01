-- Minimal schema for integration tests.
-- Mirrors the production DDL from backend/database/ migrations (create_tenant_schema() in V30),
-- limited to the tables and columns the tested queries read.

CREATE SCHEMA IF NOT EXISTS tenant_mp;

CREATE TABLE tenant_mp.scheme_master_table (
    id               SERIAL        PRIMARY KEY,
    state_scheme_id  VARCHAR(255)  NOT NULL,
    scheme_name      VARCHAR(255)  NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);

CREATE TABLE tenant_mp.user_scheme_mapping_table (
    id         SERIAL    PRIMARY KEY,
    user_id    INTEGER   NOT NULL,
    scheme_id  INTEGER   NOT NULL REFERENCES tenant_mp.scheme_master_table(id),
    status     INTEGER   NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE TABLE tenant_mp.flow_reading_table (
    id                SERIAL       PRIMARY KEY,
    scheme_id         INTEGER      NOT NULL REFERENCES tenant_mp.scheme_master_table(id),
    reading_at        TIMESTAMP    NOT NULL,
    reading_date      DATE         NOT NULL,
    extracted_reading NUMERIC      NOT NULL,
    confirmed_reading NUMERIC      NOT NULL,
    correlation_id    VARCHAR(255) NOT NULL,
    channel           VARCHAR(50),
    created_by        INTEGER      NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP
);
