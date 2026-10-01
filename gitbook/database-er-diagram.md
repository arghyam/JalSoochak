# Entity-Relationship Diagrams

These diagrams show the PostgreSQL schema as left by the Flyway migrations in `backend/database/` (common and tenant schemas, up to V55) and `backend/analytics-service/src/main/resources/db/migration/` (analytics warehouse, up to V55). Table and column descriptions are in [Database Design](database-design.md).

**Reading the diagrams**

* A **solid line** is a foreign key enforced by the database. A **dashed line** is a logical reference the services rely on but the database does not enforce, usually because it crosses schemas or would create a circular dependency.
* Only key and business columns are shown. Most tables also carry `created_at`, `updated_at`, `deleted_at` (soft delete) and `created_by` / `updated_by` / `deleted_by`. Those audit columns reference `common_schema.tenant_admin_user_master_table` or the tenant's `user_table`, and are left out of the diagrams to keep them readable.
* A table that appears in more than one diagram is shown in full once; elsewhere only the columns needed for the join are listed.
* Columns holding PII (names, phone numbers) are encrypted with AES-256 and have an HMAC `*_hash` column beside them for lookups.

## Schema overview

Each state gets its own `tenant_<stateCode>` schema, created by `common_schema.create_tenant_schema()` when the tenant is onboarded. The `analytics_schema` warehouse is filled from Kafka events, not by reading the tenant schemas directly, so there are no database links between it and the other schemas.

```mermaid
flowchart LR
    CS[("common_schema<br/>tenant_master_table<br/>tenant_config_master_table<br/>tenant_admin_user_master_table")]
    TS[("tenant_{stateCode}<br/>one per tenant<br/>user_table<br/>scheme_master_table<br/>flow_reading_table")]
    AS[("analytics_schema<br/>dim_* tables<br/>fact_* tables")]
    CS -. "state_code names the schema" .-> TS
    TS -. "tenant_id" .-> CS
    TS -- "Kafka events" --> AS
```

## Common schema (`common_schema`)

Shared by every tenant: the tenant registry and its configuration, admin users (Super Users and State Admins), login OTPs, provider secrets and the language catalog.

```mermaid
erDiagram
    tenant_master_table {
        int id PK
        varchar uuid
        varchar state_code "MP, UP, ...; lower-cased for the schema name"
        int lgd_code "state LGD code"
        varchar title
        int status "0 INACTIVE .. 7 REGISTERED"
        varchar api_key_hash
        timestamp onboarded_at
    }
    tenant_config_master_table {
        int id PK
        int tenant_id FK
        text config_key "one live row per tenant and key"
        text config_value
    }
    user_type_master_table {
        int id PK
        varchar c_name "SUPER_USER, STATE_ADMIN, ..."
    }
    tenant_admin_user_master_table {
        int id PK
        int tenant_id "0 for seeded/system users"
        varchar email
        text phone_number "encrypted"
        text phone_number_hash
        int admin_level FK
        int status
    }
    admin_user_token_table {
        bigint id PK
        varchar email
        varchar token_hash
        varchar token_type "INVITE / RESET"
        timestamptz expires_at
        timestamptz used_at
    }
    otp_table {
        bigint id PK
        int tenant_id
        bigint user_id "tenant user_table.id"
        text otp "encrypted"
        varchar otp_type
        int attempt_count
        timestamptz expires_at
    }
    tenant_secret_key {
        int id PK
        int tenant_id FK
        int key_version
        text wrapped_key "wrapped by the master key"
        varchar master_key_id
        varchar status
    }
    tenant_provider_secret {
        int id PK
        int tenant_id FK
        int key_version FK
        varchar channel "EMAIL, SMS, ..."
        varchar secret_name
        text ciphertext
    }
    language_master {
        int language_id PK
        varchar canonical_name
        varchar locale_code
    }
    language_alias {
        varchar alias PK
        int language_id FK
    }

    tenant_master_table ||--o{ tenant_config_master_table : "configured by"
    tenant_master_table ||--o{ tenant_secret_key : "owns"
    tenant_secret_key ||--o{ tenant_provider_secret : "encrypts"
    user_type_master_table ||--o{ tenant_admin_user_master_table : "admin_level"
    tenant_master_table |o..o{ tenant_admin_user_master_table : "tenant_id"
    tenant_admin_user_master_table ||..o{ admin_user_token_table : "email"
    tenant_master_table ||..o{ otp_table : "tenant_id"
    language_master ||--o{ language_alias : "spelled as"
```

Two common tables have no relationships and are not drawn: `channel_master_table` (submission and notification channels) and `language_master_table` (the language list shown in the admin UI).

## Tenant schema (`tenant_<stateCode>`)

One copy per tenant. It holds the tenant's users, location hierarchies, schemes and their assignments, meter readings, anomalies and cached reports.

### Locations and schemes

The LGD hierarchy (State → District → Block → Panchayat → Village) and the departmental hierarchy (State → Zone → Circle → Division → Sub-Division) are both self-referencing trees whose level names come from `location_config_master_table`. A scheme is placed in both.

```mermaid
erDiagram
    location_config_master_table {
        int id PK
        int region_type "LGD or DEPARTMENT"
        int level
        jsonb level_name
    }
    lgd_location_master_table {
        int id PK
        varchar title
        varchar lgd_code
        varchar state_lgd_id
        int lgd_location_config_id FK
        int parent_id FK
        numeric house_hold_count
    }
    department_location_master_table {
        int id PK
        varchar title
        varchar state_dept_id "State IT system id"
        int department_location_config_id FK
        int parent_id FK
    }
    scheme_master_table {
        int id PK
        varchar uuid
        varchar state_scheme_id "idempotent upload key"
        varchar centre_scheme_id "JJM id"
        varchar state_scheme_code
        varchar scheme_name
        int fhtc_count
        int planned_fhtc
        int house_hold_count
        int channel
        int work_status
        int operating_status
        double k_factor
    }
    scheme_lgd_mapping_table {
        int id PK
        int scheme_id FK
        int parent_lgd_id FK
        varchar parent_lgd_level
    }
    scheme_department_mapping_table {
        int id PK
        int scheme_id FK
        int parent_department_id FK
        varchar parent_department_level
    }
    asset_pump_registry_table {
        int id PK
        int scheme_id FK
        varchar pump_model
        double pump_head
        double pump_discharge_capacity
        double motor_power
        int status
    }

    location_config_master_table ||--o{ lgd_location_master_table : "level of"
    location_config_master_table ||--o{ department_location_master_table : "level of"
    lgd_location_master_table |o--o{ lgd_location_master_table : "parent of"
    department_location_master_table |o--o{ department_location_master_table : "parent of"
    lgd_location_master_table ||--o{ scheme_lgd_mapping_table : "contains"
    scheme_master_table ||--o{ scheme_lgd_mapping_table : "located in"
    department_location_master_table ||--o{ scheme_department_mapping_table : "manages"
    scheme_master_table ||--o{ scheme_department_mapping_table : "managed by"
    scheme_master_table ||--o{ asset_pump_registry_table : "has pumps"
```

### Users, readings and operations

```mermaid
erDiagram
    scheme_master_table {
        int id PK
        varchar scheme_name
    }
    user_scheme_mapping_table {
        int id PK
        int user_id FK
        int scheme_id FK
        int status
    }
    user_table {
        int id PK
        varchar uuid
        int tenant_id "common_schema.tenant_master_table"
        int user_type "common_schema.user_type_master_table"
        text title "encrypted name"
        text title_hash
        text phone_number "encrypted"
        text phone_number_hash
        varchar email
        int language_id
        bigint whatsapp_connection_id
        varchar state_user_id "State IT system id"
        int status
    }
    flow_reading_table {
        int id PK
        varchar uuid
        int scheme_id FK
        int created_by FK "submitting operator"
        timestamp observation_time
        date reading_date
        numeric extracted_reading "OCR value"
        numeric confirmed_reading
        smallint confirmed_reading_source
        numeric quantity
        text image_url
        smallint ingestion_source "lenient-ingestion bitmask"
        smallint quarantine_reason
        varchar correlation_id
    }
    anomaly_table {
        int id PK
        int scheme_id FK
        int user_id FK
        int flow_reading_id FK
        int type
        int status
        int resolved_by FK
        timestamp resolved_at
    }
    notification_table {
        int id PK
        int user_id FK
        text message
        int channel
        boolean seen_status
    }
    reports_table {
        uuid id PK
        varchar report_type
        varchar format
        bigint data_version
        varchar object_key "object-storage key"
        int generated_by FK
        timestamptz generated_at
    }
    data_versions_table {
        varchar resource_type PK
        bigint version
    }

    scheme_master_table ||--o{ user_scheme_mapping_table : "operated by"
    user_table ||--o{ user_scheme_mapping_table : "assigned"
    user_table |o--o{ flow_reading_table : "submits"
    scheme_master_table ||--o{ flow_reading_table : "readings"
    flow_reading_table |o--o{ anomaly_table : "detected in"
    scheme_master_table ||--o{ anomaly_table : "flagged on"
    user_table |o--o{ anomaly_table : "raised for"
    user_table |o--o{ notification_table : "receives"
    user_table |o--o{ reports_table : "generates"
    data_versions_table ||..o{ reports_table : "cache version"
```

`anomaly_table.resolved_by` is a second foreign key to `user_table`, not drawn separately.

Three tenant tables have no relationships and are not drawn: `user_channel_preference` and `user_language_preference` (per-contact choices from the WhatsApp conversation, keyed by a unique `contact_id`), and `language_master_table` (the tenant's language list).

## Analytics warehouse (`analytics_schema`)

A star schema owned by analytics-service and filled asynchronously from Kafka. The `*_id` columns (`scheme_id`, `user_id`, `level_N_lgd_id`, ...) carry the source ids from the tenant schema, so joins between facts and dimensions are on `(tenant_id, <source id>)`. Only `tenant_id` and the date keys are enforced foreign keys.

### Dimensions

```mermaid
erDiagram
    dim_tenant_table {
        int tenant_id PK
        varchar state_code
        varchar title
        int status
        int required_lpcd
        int person_count_per_household
        numeric regularity_threshold_percent
    }
    dim_tenant_water_norm_table {
        bigint id PK
        int tenant_id FK
        date effective_from
        date effective_to "SCD type 2"
        int required_lpcd
        int person_count_per_household
    }
    dim_tenant_work_status_filter_table {
        bigint id PK
        int tenant_id
        date effective_from
        date effective_to
        int_array included_work_statuses
    }
    dim_lgd_location_table {
        int tenant_id FK
        int lgd_id "unique per tenant"
        varchar title
        int lgd_level
        int level_1_lgd_id "to level_6_lgd_id"
        geometry geom
    }
    dim_department_location_table {
        int tenant_id FK
        int department_id "unique per tenant"
        varchar title
        int department_level
        int level_1_dept_id "to level_6_dept_id"
        geometry geom
    }
    dim_scheme_table {
        int id PK
        int tenant_id FK
        int scheme_id "source scheme id"
        varchar scheme_name
        int parent_lgd_location_id
        int parent_department_location_id
        int work_status
        int operating_status
        int fhtc_count
        int house_hold_count
    }
    dim_user_table {
        bigint id PK
        int tenant_id FK
        int user_id "source user id"
        uuid uuid
        int user_type
        int status
    }
    dim_user_scheme_mapping_table {
        int id PK
        int tenant_id
        int user_id
        int scheme_id
        int status
    }

    dim_tenant_table ||--o{ dim_tenant_water_norm_table : "norm history"
    dim_tenant_table ||..o{ dim_tenant_work_status_filter_table : "filter history"
    dim_tenant_table ||--o{ dim_lgd_location_table : "has"
    dim_tenant_table ||--o{ dim_department_location_table : "has"
    dim_tenant_table ||--o{ dim_scheme_table : "has"
    dim_tenant_table ||--o{ dim_user_table : "has"
    dim_lgd_location_table ||..o{ dim_scheme_table : "parent_lgd_location_id"
    dim_department_location_table ||..o{ dim_scheme_table : "parent_department_location_id"
    dim_user_table ||..o{ dim_user_scheme_mapping_table : "user_id"
    dim_scheme_table ||..o{ dim_user_scheme_mapping_table : "scheme_id"
```

`dim_date_table` (`date_key` PK, `full_date`, year, month, week, fiscal year) is the calendar dimension used by the facts below.

### Reading and supply facts

```mermaid
erDiagram
    dim_scheme_table {
        int tenant_id
        int scheme_id
    }
    dim_user_table {
        int tenant_id
        int user_id
    }
    dim_date_table {
        int date_key PK
        date full_date
        int year
        int month
        int week
        int fiscal_year
    }
    fact_meter_reading_table {
        bigint id PK
        int tenant_id FK
        int scheme_id
        int user_id
        numeric extracted_reading
        numeric confirmed_reading
        date reading_date
        timestamp reading_at
        int submission_status
        text correlation_id
    }
    fact_water_quantity_table {
        bigint id PK
        int tenant_id FK
        int scheme_id
        int user_id
        date date FK
        bigint water_quantity
        int submission_status
        varchar outage_reason
    }
    fact_operator_attendance_table {
        bigint id PK
        int tenant_id FK
        int date_key FK
        int user_id
        int scheme_id
        int attendance
    }

    dim_scheme_table ||..o{ fact_meter_reading_table : "scheme_id"
    dim_user_table |o..o{ fact_meter_reading_table : "user_id"
    dim_scheme_table ||..o{ fact_water_quantity_table : "scheme_id"
    dim_date_table ||--o{ fact_water_quantity_table : "full_date"
    dim_scheme_table ||..o{ fact_operator_attendance_table : "scheme_id"
    dim_date_table ||--o{ fact_operator_attendance_table : "date_key"
```

### Escalation, anomaly and performance facts

```mermaid
erDiagram
    dim_scheme_table {
        int tenant_id
        int scheme_id
    }
    fact_escalation_table {
        bigint id PK
        int tenant_id FK
        int scheme_id
        int user_id
        varchar escalation_type
        int resolution_status
    }
    fact_scheme_performance_table {
        bigint id PK
        int tenant_id FK
        int scheme_id
        numeric performance_score
        date last_water_supply_date
    }
    fact_anomaly_table {
        bigint id PK
        int tenant_id
        int scheme_id
        int user_id
        varchar type
        int status
        text submission_correlation_id
    }
    submission_attempt_table {
        bigint id PK
        int tenant_id
        int scheme_id
        varchar phone_hash
        varchar reason "rejected before a reading was stored"
        timestamp attempted_at
    }

    dim_scheme_table ||..o{ fact_escalation_table : "scheme_id"
    dim_scheme_table ||..o{ fact_scheme_performance_table : "scheme_id"
    dim_scheme_table ||..o{ fact_anomaly_table : "scheme_id"
    dim_scheme_table |o..o{ submission_attempt_table : "scheme_id"
```

The `tenant_id` columns marked FK in the two fact diagrams above reference `dim_tenant_table`; that link is left out of the drawings.

### Pre-aggregations

Summary tables rebuilt from the facts above so dashboard cards are single indexed lookups. `fact_scheme_daily_table` copies each scheme's LGD and department ancestor ids from `dim_scheme_table`; the other two are rolled up per region at every level of either hierarchy.

```mermaid
erDiagram
    dim_scheme_table {
        int tenant_id
        int scheme_id
    }
    dim_lgd_location_table {
        int tenant_id
        int lgd_id
    }
    dim_department_location_table {
        int tenant_id
        int department_id
    }
    fact_scheme_daily_table {
        int tenant_id PK
        int scheme_id PK
        date reading_date PK
        int level_1_lgd_id "to level_6, and dept levels"
        smallint submitted
        smallint supplied
        bigint water_supplied_liters
        boolean is_final
    }
    fact_region_metrics_table {
        bigint id PK
        int tenant_id
        varchar period_scale "DAY, WEEK or MONTH"
        date period_start
        varchar hierarchy "LGD or DEPARTMENT"
        int region_id
        int scheme_count
        bigint total_water_supplied_liters
        boolean is_final
    }
    fact_submission_activity_hourly_table {
        bigint id PK
        int tenant_id
        timestamp hour_start
        varchar hierarchy
        int region_id
        int submission_count
    }

    dim_scheme_table ||..o{ fact_scheme_daily_table : "one row per day"
    dim_lgd_location_table ||..o{ fact_region_metrics_table : "region_id"
    dim_department_location_table ||..o{ fact_region_metrics_table : "region_id"
    dim_lgd_location_table ||..o{ fact_submission_activity_hourly_table : ""
    dim_department_location_table ||..o{ fact_submission_activity_hourly_table : ""
```
