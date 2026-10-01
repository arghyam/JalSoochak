-- Generated with pg_dump from a database migrated through V61 (backend/database) plus
-- common_schema.create_tenant_schema('tenant_as'). The state sync writes these tables, so its
-- integration tests run against their real shape. Regenerate rather than hand-edit; triggers are
-- dropped (their functions live outside these tables).

CREATE SCHEMA IF NOT EXISTS common_schema;
CREATE SCHEMA IF NOT EXISTS tenant_as;

CREATE TABLE common_schema.state_sync_issue_table (
    id bigint NOT NULL,
    run_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    entity character varying(32) NOT NULL,
    upstream_code character varying(255),
    category character varying(64) NOT NULL,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE common_schema.state_sync_issue_table_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE common_schema.state_sync_issue_table_id_seq OWNED BY common_schema.state_sync_issue_table.id;

CREATE TABLE common_schema.state_sync_run_table (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    run_kind character varying(32) NOT NULL,
    mode character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    triggered_by character varying(64) NOT NULL,
    owner character varying(255) NOT NULL,
    started_at timestamp without time zone DEFAULT now() NOT NULL,
    heartbeat_at timestamp without time zone DEFAULT now() NOT NULL,
    finished_at timestamp without time zone,
    source_watermark timestamp without time zone,
    counts jsonb DEFAULT '{}'::jsonb NOT NULL,
    error text
);

CREATE SEQUENCE common_schema.state_sync_run_table_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE common_schema.state_sync_run_table_id_seq OWNED BY common_schema.state_sync_run_table.id;

CREATE TABLE common_schema.tenant_master_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    state_code character varying(10) NOT NULL,
    lgd_code integer NOT NULL,
    title character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    onboarded_at timestamp without time zone,
    status integer NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer,
    deleted_at timestamp without time zone,
    deleted_by integer,
    api_key_hash character varying(64)
);

CREATE SEQUENCE common_schema.tenant_master_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE common_schema.tenant_master_table_id_seq OWNED BY common_schema.tenant_master_table.id;

CREATE TABLE common_schema.user_type_master_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    c_name character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    updated_by integer,
    deleted_at timestamp without time zone,
    deleted_by integer
);

CREATE SEQUENCE common_schema.user_type_master_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE common_schema.user_type_master_table_id_seq OWNED BY common_schema.user_type_master_table.id;

CREATE TABLE tenant_as.anomaly_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    user_id integer NOT NULL,
    scheme_id integer NOT NULL,
    type integer NOT NULL,
    reason text,
    ai_reading numeric,
    ai_confidence_percentage numeric,
    overridden_reading numeric,
    retries integer DEFAULT 0,
    previous_reading numeric,
    previous_reading_date timestamp without time zone,
    consecutive_days_overridden integer DEFAULT 0,
    remarks text,
    resolved_by integer,
    resolved_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    status integer NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer,
    flow_reading_id integer
);

CREATE SEQUENCE tenant_as.anomaly_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.anomaly_table_id_seq OWNED BY tenant_as.anomaly_table.id;

CREATE TABLE tenant_as.department_location_master_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    title character varying(255) NOT NULL,
    department_location_config_id integer,
    parent_id integer,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    updated_by integer,
    status integer NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer,
    state_dept_id character varying(255)
);

CREATE SEQUENCE tenant_as.department_location_master_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.department_location_master_table_id_seq OWNED BY tenant_as.department_location_master_table.id;

CREATE TABLE tenant_as.flow_reading_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    scheme_id integer NOT NULL,
    observation_time timestamp without time zone NOT NULL,
    reading_date date NOT NULL,
    extracted_reading numeric,
    confirmed_reading numeric,
    correlation_id character varying(255) NOT NULL,
    quantity numeric DEFAULT 0 NOT NULL,
    quality_flag character varying(20) DEFAULT 'provisional'::character varying NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    payload_json jsonb,
    reported_via character varying(50),
    duration integer,
    image_url text DEFAULT ''::text,
    ai_confidence_percentage numeric,
    latitude double precision,
    longitude double precision,
    meter_change_reason text,
    issue_report_reason text,
    created_by integer NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer,
    ingestion_source smallint DEFAULT 0 NOT NULL,
    submitted_state_scheme_id character varying(255),
    submitted_centre_scheme_id character varying(255),
    submitted_phone_hash character varying(64),
    ocr_correlation_id character varying(255),
    confirmed_reading_source smallint DEFAULT 0 NOT NULL,
    quarantine_reason smallint DEFAULT 0 NOT NULL,
    submitted_unit character varying(16),
    channel_id integer
);

COMMENT ON COLUMN tenant_as.flow_reading_table.extracted_reading IS 'What OCR read off the meter photo, in the channel''s standard unit. 0 when no photo was read.';

COMMENT ON COLUMN tenant_as.flow_reading_table.confirmed_reading IS 'The reading in the channel''s standard unit, whatever unit it was sent in (see submitted_unit). BFM: m3, cumulative meter index. ELM: kW.h, cumulative meter index. PDU: min, pump run time of this submission.';

COMMENT ON COLUMN tenant_as.flow_reading_table.submitted_unit IS 'UCUM code of the unit the reading was sent in: m3, kL or L (BFM), kW.h (ELM), min or h (PDU). confirmed_reading holds the value converted to the channel''s standard unit. NULL on rows from before V56, rows with no reading, and IOT and MAN rows.';

COMMENT ON COLUMN tenant_as.flow_reading_table.channel_id IS 'The channel the reading came through, a common_schema.channel_master_table id: 1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN, the codes ReadingChannel publishes. NULL on rows with no reading (placeholder, location and meter-change rows) and on BFM readings from before the channel was recorded.';

CREATE SEQUENCE tenant_as.flow_reading_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.flow_reading_table_id_seq OWNED BY tenant_as.flow_reading_table.id;

CREATE TABLE tenant_as.lgd_location_master_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    title character varying(255) NOT NULL,
    lgd_code character varying(50) NOT NULL,
    lgd_location_config_id integer,
    parent_id integer,
    house_hold_count numeric DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    updated_by integer,
    status integer NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer,
    state_lgd_id character varying(255)
);

CREATE SEQUENCE tenant_as.lgd_location_master_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.lgd_location_master_table_id_seq OWNED BY tenant_as.lgd_location_master_table.id;

CREATE TABLE tenant_as.location_config_master_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    region_type integer NOT NULL,
    level integer NOT NULL,
    level_name jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    updated_by integer,
    deleted_at timestamp without time zone,
    deleted_by integer
);

CREATE SEQUENCE tenant_as.location_config_master_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.location_config_master_table_id_seq OWNED BY tenant_as.location_config_master_table.id;

CREATE TABLE tenant_as.scheme_department_mapping_table (
    id integer NOT NULL,
    scheme_id integer NOT NULL,
    parent_department_id integer NOT NULL,
    parent_department_level character varying(255) NOT NULL,
    created_by integer NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer
);

CREATE SEQUENCE tenant_as.scheme_department_mapping_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.scheme_department_mapping_table_id_seq OWNED BY tenant_as.scheme_department_mapping_table.id;

CREATE TABLE tenant_as.scheme_lgd_mapping_table (
    id integer NOT NULL,
    scheme_id integer NOT NULL,
    parent_lgd_id integer NOT NULL,
    parent_lgd_level character varying(255) NOT NULL,
    created_by integer NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer
);

CREATE SEQUENCE tenant_as.scheme_lgd_mapping_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.scheme_lgd_mapping_table_id_seq OWNED BY tenant_as.scheme_lgd_mapping_table.id;

CREATE TABLE tenant_as.scheme_master_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    state_scheme_id character varying(255) NOT NULL,
    centre_scheme_id character varying(255) NOT NULL,
    scheme_name character varying(255) NOT NULL,
    fhtc_count integer DEFAULT 0 NOT NULL,
    planned_fhtc integer DEFAULT 0 NOT NULL,
    house_hold_count integer DEFAULT 0 NOT NULL,
    latitude double precision,
    longitude double precision,
    work_status integer NOT NULL,
    operating_status integer NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer,
    deleted_at timestamp without time zone,
    deleted_by integer,
    is_auto_provisioned boolean DEFAULT false NOT NULL,
    submitted_state_scheme_id_mismatch character varying(255),
    submitted_centre_scheme_id_mismatch character varying(255),
    id_mismatch_last_seen_at timestamp without time zone,
    state_scheme_code character varying(255),
    k_factor double precision,
    channel_id integer
);

COMMENT ON COLUMN tenant_as.scheme_master_table.channel_id IS 'The channel the scheme''s readings are sent through, as chosen in the WhatsApp channel selection, a common_schema.channel_master_table id: 1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN. NULL until one is chosen.';

CREATE SEQUENCE tenant_as.scheme_master_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.scheme_master_table_id_seq OWNED BY tenant_as.scheme_master_table.id;

CREATE TABLE tenant_as.user_scheme_mapping_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    user_id integer NOT NULL,
    scheme_id integer NOT NULL,
    status integer NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by integer,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer,
    deleted_at timestamp without time zone,
    deleted_by integer
);

CREATE SEQUENCE tenant_as.user_scheme_mapping_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.user_scheme_mapping_table_id_seq OWNED BY tenant_as.user_scheme_mapping_table.id;

CREATE TABLE tenant_as.user_table (
    id integer NOT NULL,
    uuid character varying(36) DEFAULT (gen_random_uuid())::text NOT NULL,
    tenant_id integer NOT NULL,
    title text NOT NULL,
    email character varying(255),
    user_type integer NOT NULL,
    phone_number text NOT NULL,
    phone_number_hash text,
    title_hash text,
    password text,
    status integer NOT NULL,
    email_verification_status boolean DEFAULT false,
    phone_verification_status boolean DEFAULT false,
    language_id integer,
    whatsapp_connection_id bigint,
    created_by integer,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by integer,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by integer,
    is_auto_provisioned boolean DEFAULT false NOT NULL,
    state_user_id character varying(255)
);

CREATE SEQUENCE tenant_as.user_table_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tenant_as.user_table_id_seq OWNED BY tenant_as.user_table.id;

ALTER TABLE ONLY common_schema.state_sync_issue_table ALTER COLUMN id SET DEFAULT nextval('common_schema.state_sync_issue_table_id_seq'::regclass);

ALTER TABLE ONLY common_schema.state_sync_run_table ALTER COLUMN id SET DEFAULT nextval('common_schema.state_sync_run_table_id_seq'::regclass);

ALTER TABLE ONLY common_schema.tenant_master_table ALTER COLUMN id SET DEFAULT nextval('common_schema.tenant_master_table_id_seq'::regclass);

ALTER TABLE ONLY common_schema.user_type_master_table ALTER COLUMN id SET DEFAULT nextval('common_schema.user_type_master_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.anomaly_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.anomaly_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.department_location_master_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.department_location_master_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.flow_reading_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.flow_reading_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.lgd_location_master_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.lgd_location_master_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.location_config_master_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.location_config_master_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.scheme_department_mapping_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.scheme_department_mapping_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.scheme_lgd_mapping_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.scheme_lgd_mapping_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.scheme_master_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.scheme_master_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.user_scheme_mapping_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.user_scheme_mapping_table_id_seq'::regclass);

ALTER TABLE ONLY tenant_as.user_table ALTER COLUMN id SET DEFAULT nextval('tenant_as.user_table_id_seq'::regclass);

ALTER TABLE ONLY common_schema.state_sync_issue_table
    ADD CONSTRAINT state_sync_issue_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY common_schema.state_sync_run_table
    ADD CONSTRAINT state_sync_run_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY common_schema.tenant_master_table
    ADD CONSTRAINT tenant_master_table_lgd_code_key UNIQUE (lgd_code);

ALTER TABLE ONLY common_schema.tenant_master_table
    ADD CONSTRAINT tenant_master_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY common_schema.tenant_master_table
    ADD CONSTRAINT tenant_master_table_state_code_key UNIQUE (state_code);

ALTER TABLE ONLY common_schema.tenant_master_table
    ADD CONSTRAINT tenant_master_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY common_schema.user_type_master_table
    ADD CONSTRAINT user_type_master_table_c_name_key UNIQUE (c_name);

ALTER TABLE ONLY common_schema.user_type_master_table
    ADD CONSTRAINT user_type_master_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY common_schema.user_type_master_table
    ADD CONSTRAINT user_type_master_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.anomaly_table
    ADD CONSTRAINT anomaly_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.anomaly_table
    ADD CONSTRAINT anomaly_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.department_location_master_table
    ADD CONSTRAINT department_location_master_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.department_location_master_table
    ADD CONSTRAINT department_location_master_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.flow_reading_table
    ADD CONSTRAINT flow_reading_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.flow_reading_table
    ADD CONSTRAINT flow_reading_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.lgd_location_master_table
    ADD CONSTRAINT lgd_location_master_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.lgd_location_master_table
    ADD CONSTRAINT lgd_location_master_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.location_config_master_table
    ADD CONSTRAINT location_config_master_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.location_config_master_table
    ADD CONSTRAINT location_config_master_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.scheme_department_mapping_table
    ADD CONSTRAINT scheme_department_mapping_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.scheme_lgd_mapping_table
    ADD CONSTRAINT scheme_lgd_mapping_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.scheme_master_table
    ADD CONSTRAINT scheme_master_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.scheme_master_table
    ADD CONSTRAINT scheme_master_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.user_scheme_mapping_table
    ADD CONSTRAINT user_scheme_mapping_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.user_scheme_mapping_table
    ADD CONSTRAINT user_scheme_mapping_table_uuid_key UNIQUE (uuid);

ALTER TABLE ONLY tenant_as.user_table
    ADD CONSTRAINT user_table_email_key UNIQUE (email);

ALTER TABLE ONLY tenant_as.user_table
    ADD CONSTRAINT user_table_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_as.user_table
    ADD CONSTRAINT user_table_uuid_key UNIQUE (uuid);

CREATE INDEX idx_state_sync_issue_run ON common_schema.state_sync_issue_table USING btree (run_id);

CREATE INDEX idx_state_sync_issue_tenant_category ON common_schema.state_sync_issue_table USING btree (tenant_id, category, created_at DESC);

CREATE INDEX idx_state_sync_run_tenant_started ON common_schema.state_sync_run_table USING btree (tenant_id, started_at DESC);

CREATE UNIQUE INDEX idx_tenant_api_key_hash ON common_schema.tenant_master_table USING btree (api_key_hash) WHERE (api_key_hash IS NOT NULL);

CREATE INDEX idx_tenant_master_lgd_code ON common_schema.tenant_master_table USING btree (lgd_code);

CREATE INDEX idx_tenant_master_state_code ON common_schema.tenant_master_table USING btree (state_code);

CREATE INDEX idx_tenant_master_status ON common_schema.tenant_master_table USING btree (status);

CREATE UNIQUE INDEX uq_state_sync_run_one_running_per_tenant ON common_schema.state_sync_run_table USING btree (tenant_id) WHERE ((status)::text = 'RUNNING'::text);

CREATE INDEX idx_tenant_as_anom_flow_reading ON tenant_as.anomaly_table USING btree (flow_reading_id) WHERE (flow_reading_id IS NOT NULL);

CREATE INDEX idx_tenant_as_anom_scheme ON tenant_as.anomaly_table USING btree (scheme_id);

CREATE INDEX idx_tenant_as_anom_status ON tenant_as.anomaly_table USING btree (status);

CREATE INDEX idx_tenant_as_anom_type ON tenant_as.anomaly_table USING btree (type);

CREATE INDEX idx_tenant_as_anom_user ON tenant_as.anomaly_table USING btree (user_id);

CREATE INDEX idx_tenant_as_dept_parent ON tenant_as.department_location_master_table USING btree (parent_id);

CREATE INDEX idx_tenant_as_dept_status ON tenant_as.department_location_master_table USING btree (status);

CREATE INDEX idx_tenant_as_flow_corr ON tenant_as.flow_reading_table USING btree (correlation_id);

CREATE INDEX idx_tenant_as_flow_creator_time ON tenant_as.flow_reading_table USING btree (created_by, observation_time DESC, id DESC) WHERE (deleted_at IS NULL);

CREATE INDEX idx_tenant_as_flow_date ON tenant_as.flow_reading_table USING btree (reading_date);

CREATE INDEX idx_tenant_as_flow_ingestion_source ON tenant_as.flow_reading_table USING btree (ingestion_source) WHERE (ingestion_source <> 0);

CREATE INDEX idx_tenant_as_flow_ocr_corr ON tenant_as.flow_reading_table USING btree (ocr_correlation_id);

CREATE INDEX idx_tenant_as_flow_scheme ON tenant_as.flow_reading_table USING btree (scheme_id);

CREATE INDEX idx_tenant_as_flow_scheme_creator_date ON tenant_as.flow_reading_table USING btree (scheme_id, created_by, reading_date DESC);

CREATE INDEX idx_tenant_as_flow_scheme_date ON tenant_as.flow_reading_table USING btree (scheme_id, reading_date DESC);

CREATE INDEX idx_tenant_as_lgd_code ON tenant_as.lgd_location_master_table USING btree (lgd_code);

CREATE INDEX idx_tenant_as_lgd_parent ON tenant_as.lgd_location_master_table USING btree (parent_id);

CREATE INDEX idx_tenant_as_lgd_status ON tenant_as.lgd_location_master_table USING btree (status);

CREATE INDEX idx_tenant_as_scheme_auto_prov ON tenant_as.scheme_master_table USING btree (is_auto_provisioned) WHERE is_auto_provisioned;

CREATE INDEX idx_tenant_as_scheme_centre_id ON tenant_as.scheme_master_table USING btree (centre_scheme_id);

CREATE INDEX idx_tenant_as_scheme_id_mismatch ON tenant_as.scheme_master_table USING btree (id_mismatch_last_seen_at) WHERE ((submitted_state_scheme_id_mismatch IS NOT NULL) OR (submitted_centre_scheme_id_mismatch IS NOT NULL));

CREATE INDEX idx_tenant_as_scheme_op_st ON tenant_as.scheme_master_table USING btree (operating_status);

CREATE INDEX idx_tenant_as_scheme_state_id ON tenant_as.scheme_master_table USING btree (state_scheme_id);

CREATE INDEX idx_tenant_as_scheme_work_st ON tenant_as.scheme_master_table USING btree (work_status);

CREATE INDEX idx_tenant_as_sdm_dept ON tenant_as.scheme_department_mapping_table USING btree (parent_department_id);

CREATE INDEX idx_tenant_as_sdm_scheme ON tenant_as.scheme_department_mapping_table USING btree (scheme_id);

CREATE INDEX idx_tenant_as_slm_lgd ON tenant_as.scheme_lgd_mapping_table USING btree (parent_lgd_id);

CREATE INDEX idx_tenant_as_slm_scheme ON tenant_as.scheme_lgd_mapping_table USING btree (scheme_id);

CREATE INDEX idx_tenant_as_user_auto_prov ON tenant_as.user_table USING btree (is_auto_provisioned) WHERE is_auto_provisioned;

CREATE INDEX idx_tenant_as_user_language_id ON tenant_as.user_table USING btree (language_id);

CREATE INDEX idx_tenant_as_user_phone_hash ON tenant_as.user_table USING btree (phone_number_hash);

CREATE INDEX idx_tenant_as_user_status ON tenant_as.user_table USING btree (status);

CREATE INDEX idx_tenant_as_user_tenant ON tenant_as.user_table USING btree (tenant_id);

CREATE INDEX idx_tenant_as_user_title_hash ON tenant_as.user_table USING btree (title_hash);

CREATE INDEX idx_tenant_as_user_type ON tenant_as.user_table USING btree (user_type);

CREATE INDEX idx_tenant_as_usm_scheme ON tenant_as.user_scheme_mapping_table USING btree (scheme_id);

CREATE INDEX idx_tenant_as_usm_status ON tenant_as.user_scheme_mapping_table USING btree (status);

CREATE INDEX idx_tenant_as_usm_user ON tenant_as.user_scheme_mapping_table USING btree (user_id);

CREATE UNIQUE INDEX uq_tenant_as_dept_state_dept_id ON tenant_as.department_location_master_table USING btree (state_dept_id) WHERE ((state_dept_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_tenant_as_lgd_state_lgd_id ON tenant_as.lgd_location_master_table USING btree (state_lgd_id) WHERE ((state_lgd_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_tenant_as_scheme_auto_prov_ids ON tenant_as.scheme_master_table USING btree (state_scheme_id, centre_scheme_id) WHERE (is_auto_provisioned AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_tenant_as_scheme_state_scheme_code ON tenant_as.scheme_master_table USING btree (state_scheme_code) WHERE ((state_scheme_code IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_tenant_as_user_state_user_id ON tenant_as.user_table USING btree (state_user_id) WHERE ((state_user_id IS NOT NULL) AND (deleted_at IS NULL));

ALTER TABLE ONLY common_schema.state_sync_issue_table
    ADD CONSTRAINT state_sync_issue_table_run_id_fkey FOREIGN KEY (run_id) REFERENCES common_schema.state_sync_run_table(id);

ALTER TABLE ONLY common_schema.state_sync_issue_table
    ADD CONSTRAINT state_sync_issue_table_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES common_schema.tenant_master_table(id);

ALTER TABLE ONLY common_schema.state_sync_run_table
    ADD CONSTRAINT state_sync_run_table_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES common_schema.tenant_master_table(id);

ALTER TABLE ONLY tenant_as.anomaly_table
    ADD CONSTRAINT fk_anomaly_flow_reading FOREIGN KEY (flow_reading_id) REFERENCES tenant_as.flow_reading_table(id) ON DELETE SET NULL;

ALTER TABLE ONLY tenant_as.anomaly_table
    ADD CONSTRAINT fk_anomaly_resolved_by FOREIGN KEY (resolved_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.anomaly_table
    ADD CONSTRAINT fk_anomaly_scheme FOREIGN KEY (scheme_id) REFERENCES tenant_as.scheme_master_table(id);

ALTER TABLE ONLY tenant_as.anomaly_table
    ADD CONSTRAINT fk_anomaly_user FOREIGN KEY (user_id) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.department_location_master_table
    ADD CONSTRAINT fk_dept_location_config FOREIGN KEY (department_location_config_id) REFERENCES tenant_as.location_config_master_table(id);

ALTER TABLE ONLY tenant_as.department_location_master_table
    ADD CONSTRAINT fk_dept_parent FOREIGN KEY (parent_id) REFERENCES tenant_as.department_location_master_table(id);

ALTER TABLE ONLY tenant_as.flow_reading_table
    ADD CONSTRAINT fk_flow_created_by FOREIGN KEY (created_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.flow_reading_table
    ADD CONSTRAINT fk_flow_scheme FOREIGN KEY (scheme_id) REFERENCES tenant_as.scheme_master_table(id);

ALTER TABLE ONLY tenant_as.flow_reading_table
    ADD CONSTRAINT fk_flow_updated_by FOREIGN KEY (updated_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.lgd_location_master_table
    ADD CONSTRAINT fk_lgd_location_config FOREIGN KEY (lgd_location_config_id) REFERENCES tenant_as.location_config_master_table(id);

ALTER TABLE ONLY tenant_as.lgd_location_master_table
    ADD CONSTRAINT fk_lgd_location_parent FOREIGN KEY (parent_id) REFERENCES tenant_as.lgd_location_master_table(id);

ALTER TABLE ONLY tenant_as.scheme_department_mapping_table
    ADD CONSTRAINT fk_scheme_dept_created_by FOREIGN KEY (created_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.scheme_department_mapping_table
    ADD CONSTRAINT fk_scheme_dept_parent FOREIGN KEY (parent_department_id) REFERENCES tenant_as.department_location_master_table(id);

ALTER TABLE ONLY tenant_as.scheme_department_mapping_table
    ADD CONSTRAINT fk_scheme_dept_scheme FOREIGN KEY (scheme_id) REFERENCES tenant_as.scheme_master_table(id);

ALTER TABLE ONLY tenant_as.scheme_department_mapping_table
    ADD CONSTRAINT fk_scheme_dept_updated_by FOREIGN KEY (updated_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.scheme_lgd_mapping_table
    ADD CONSTRAINT fk_scheme_lgd_created_by FOREIGN KEY (created_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.scheme_lgd_mapping_table
    ADD CONSTRAINT fk_scheme_lgd_parent FOREIGN KEY (parent_lgd_id) REFERENCES tenant_as.lgd_location_master_table(id);

ALTER TABLE ONLY tenant_as.scheme_lgd_mapping_table
    ADD CONSTRAINT fk_scheme_lgd_scheme FOREIGN KEY (scheme_id) REFERENCES tenant_as.scheme_master_table(id);

ALTER TABLE ONLY tenant_as.scheme_lgd_mapping_table
    ADD CONSTRAINT fk_scheme_lgd_updated_by FOREIGN KEY (updated_by) REFERENCES tenant_as.user_table(id);

ALTER TABLE ONLY tenant_as.user_scheme_mapping_table
    ADD CONSTRAINT fk_user_scheme_scheme FOREIGN KEY (scheme_id) REFERENCES tenant_as.scheme_master_table(id);

ALTER TABLE ONLY tenant_as.user_scheme_mapping_table
    ADD CONSTRAINT fk_user_scheme_user FOREIGN KEY (user_id) REFERENCES tenant_as.user_table(id);

