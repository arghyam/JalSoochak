-- ============================================================
-- Migration: V31 - Add user_channel_preference & user_language_preference to common_schema
-- ============================================================
-- These tables already exist in deployed environments (created out-of-band) but were
-- never tracked by Flyway, so a freshly provisioned database would be missing them and
-- telemetry-service (ReadingChannelResolver / language resolution) would fail on the
-- first Glific-originated reading. This migration codifies the existing schema.
--
-- Written entirely with IF NOT EXISTS so it is a safe no-op on databases where the
-- tables were already created manually, and provisions them on fresh environments.
-- contact_id is stored normalised to digits by the telemetry repositories.
-- ============================================================

CREATE TABLE IF NOT EXISTS common_schema.user_channel_preference (
    id            BIGSERIAL     NOT NULL,
    tenant_id     INTEGER       NOT NULL,
    contact_id    VARCHAR(50)   NOT NULL,
    channel_value VARCHAR(100)  NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT user_channel_preference_pkey PRIMARY KEY (id),
    CONSTRAINT uq_user_channel_pref UNIQUE (tenant_id, contact_id)
);

CREATE INDEX IF NOT EXISTS idx_user_channel_pref_tenant_contact
    ON common_schema.user_channel_preference (tenant_id, contact_id);

CREATE TABLE IF NOT EXISTS common_schema.user_language_preference (
    id             BIGSERIAL    NOT NULL,
    tenant_id      INTEGER      NOT NULL,
    contact_id     TEXT         NOT NULL,
    language_value TEXT         NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT user_language_preference_pkey PRIMARY KEY (id),
    CONSTRAINT user_language_preference_tenant_id_contact_id_key UNIQUE (tenant_id, contact_id)
);

CREATE INDEX IF NOT EXISTS idx_user_language_pref_contact
    ON common_schema.user_language_preference (contact_id);
