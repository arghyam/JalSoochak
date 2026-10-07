-- Minimal schema for integration tests.
-- Mirrors the production DDL from backend/database/ migrations (create_tenant_schema() in V30),
-- limited to the tables and columns the tested queries read.

CREATE SCHEMA IF NOT EXISTS tenant_mp;

CREATE TABLE tenant_mp.scheme_master_table (
    id               SERIAL            PRIMARY KEY,
    state_scheme_id  VARCHAR(255)      NOT NULL,
    centre_scheme_id VARCHAR(255)      NOT NULL,
    scheme_name      VARCHAR(255)      NOT NULL,
    fhtc_count       INTEGER           NOT NULL DEFAULT 0,
    planned_fhtc     INTEGER           NOT NULL DEFAULT 0,
    house_hold_count INTEGER           NOT NULL DEFAULT 0,
    latitude         DOUBLE PRECISION,
    longitude        DOUBLE PRECISION,
    work_status      INTEGER           NOT NULL,
    operating_status INTEGER           NOT NULL,
    created_at       TIMESTAMP         NOT NULL DEFAULT NOW(),
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
    channel_id        INTEGER,
    created_by        INTEGER      NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP
);

CREATE TABLE tenant_mp.location_config_master_table (
    id    SERIAL  PRIMARY KEY,
    level INTEGER NOT NULL
);

CREATE TABLE tenant_mp.lgd_location_master_table (
    id                     SERIAL  PRIMARY KEY,
    lgd_location_config_id INTEGER REFERENCES tenant_mp.location_config_master_table(id),
    parent_id              INTEGER REFERENCES tenant_mp.lgd_location_master_table(id)
);

CREATE TABLE tenant_mp.department_location_master_table (
    id                            SERIAL  PRIMARY KEY,
    department_location_config_id INTEGER REFERENCES tenant_mp.location_config_master_table(id),
    parent_id                     INTEGER REFERENCES tenant_mp.department_location_master_table(id)
);

CREATE TABLE tenant_mp.scheme_lgd_mapping_table (
    id            SERIAL    PRIMARY KEY,
    scheme_id     INTEGER   NOT NULL REFERENCES tenant_mp.scheme_master_table(id),
    parent_lgd_id INTEGER   NOT NULL REFERENCES tenant_mp.lgd_location_master_table(id),
    deleted_at    TIMESTAMP
);

CREATE TABLE tenant_mp.scheme_department_mapping_table (
    id                   SERIAL    PRIMARY KEY,
    scheme_id            INTEGER   NOT NULL REFERENCES tenant_mp.scheme_master_table(id),
    parent_department_id INTEGER   NOT NULL REFERENCES tenant_mp.department_location_master_table(id),
    deleted_at           TIMESTAMP
);
