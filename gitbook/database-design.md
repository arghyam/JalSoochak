# Database Design

## 10. Database Design

Data is stored in **PostgreSQL** using **schema-per-tenant** isolation: a shared `common_schema` holds cross-tenant metadata, and each state has its own `tenant_<stateCode>` schema, provisioned by the `common_schema.create_tenant_schema()` PL/pgSQL function when the tenant is created. The common-schema tables and this function are defined by the Flyway migrations in `backend/database/`, which tenant-service packages and runs on startup. The **analytics star-schema warehouse** lives in `analytics_schema`, on analytics-service's own datasource. The tables below are grouped by functional domain (column format: `name (type, notes)`). For how the tables relate to each other, see the [Entity-Relationship Diagrams](database-er-diagram.md).

### 10.1 Tenancy & Users

**`common_schema.tenant_master_table`** — the tenant registry

* `id (PK)`, `uuid`
* `state_code (varchar, e.g. MP/UP; lower-cased for the schema name)`
* `lgd_code (int, state LGD code)`
* `title (varchar, display name)`
* `status (int, 0=INACTIVE / 1=ONBOARDED / 2=CONFIGURED / 3=ACTIVE / 4=SUSPENDED / 5=DEGRADED / 6=ARCHIVED / 7=REGISTERED, a pre-seeded state not yet onboarded)`
* `api_key_hash (varchar, hashed tenant API key)`

**`common_schema.tenant_config_master_table`** — per-tenant key/value configuration

* `tenant_id (FK)`
* `config_key (text, e.g. WATER_NORM, nudge_message_hindi; one live row per tenant and key)`
* `config_value (text)`

* **`common_schema.tenant_admin_user_master_table`** — Super Users and State Admins, with `admin_level` pointing at `user_type_master_table`
* **`common_schema.admin_user_token_table`** — hashed invite and password-reset tokens for admin users
* **`common_schema.otp_table`** — encrypted login and password-change OTPs for field staff (lives in the common schema because the tenant is unknown before login)
* **`common_schema.tenant_provider_secret` / `tenant_secret_key`** — per-tenant email/SMS provider credentials, encrypted under a per-tenant data key that is itself wrapped by a master key

**`tenant_<state>.user_table`** — operators and officers within a tenant

* `id (PK)`, `uuid`
* `title (text, AES-256 encrypted name)`, `title_hash (text, HMAC lookup)`
* `email (varchar, nullable for field staff)`
* `user_type (int, id in common_schema.user_type_master_table, e.g. PUMP_OPERATOR / SECTION_OFFICER / SUB_DIVISIONAL_OFFICER / STATE_ADMIN)`
* `phone_number (text, AES-256 encrypted)`, `phone_number_hash (text, HMAC lookup)`
* `language_id (int)`, `status (int)`, `whatsapp_connection_id (bigint, WhatsApp provider contact id)`
* `state_user_id (varchar, the State IT system's id for the user)`

### 10.2 Location & Hierarchies

* **`lgd_location_master_table`** — LGD nodes: State → District → Block → Panchayat → Village
* **`department_location_master_table`** — Departmental nodes: State → Zone → Circle → Division → Sub-Division, with `state_dept_id` holding the State IT system's id
* **`location_config_master_table`** — the per-tenant level names of both hierarchies (`region_type`, `level`, `level_name`); the chains above are the defaults seeded at tenant creation

### 10.3 Schemes, Pumps & Assignments

**`scheme_master_table`**

* `id (PK)`, `uuid`
* `state_scheme_id (varchar, idempotent upload key)`, `centre_scheme_id (varchar, JJM id)`
* `scheme_name (varchar)`
* `fhtc_count (int)`, `planned_fhtc (int)`, `house_hold_count (int)`
* `state_scheme_code (varchar, the State system's public scheme code)`
* `latitude / longitude (double)`
* `channel (int, the scheme's reading channel)`
* `work_status (int)`, `operating_status (int)`

* **`scheme_lgd_mapping_table` / `scheme_department_mapping_table`** — scheme ↔ location links
* **`user_scheme_mapping_table`** — operator ↔ scheme assignments
* **`asset_pump_registry_table`** — pumps installed on a scheme (pump model, efficiency, head, discharge capacity, motor ratings)

### 10.4 Readings & Submissions

**`flow_reading_table`** — one row per meter-reading submission

* `id (PK)`, `uuid`, `scheme_id (FK)`
* `observation_time (timestamp)`, `reading_date (date, daily de-duplication)`
* `extracted_reading (numeric, OCR-extracted value)`, `confirmed_reading (numeric, operator value)`, `confirmed_reading_source (smallint, as extracted / rollover-resolved)`
* `ai_confidence_percentage (numeric)`, `quantity (numeric, delta vs previous reading)`
* `image_url (text)`, `latitude / longitude (double, submission location)`, `correlation_id / ocr_correlation_id (varchar)`
* `meter_change_reason (text)`, `issue_report_reason (text)` — set on meter-change and issue-report rows
* `ingestion_source (smallint, bitmask: 0 = normal; non-zero when the scheme or phone was not found and the lenient path recorded it)`
* `quarantine_reason (smallint, 0 = accepted / 1 = implausible water supply, stored but excluded downstream)`

### 10.5 Messaging & Nudge Configuration

* Notification templates and language keys live in `tenant_config_master_table` (`nudge_message_<lang>`, `escalation_message_<lang>`, `language_<id>`); the WhatsApp conversation's screens, prompts and options live under `WHATSAPP_MESSAGE_TEMPLATES`.
* **`channel_master_table`** (common schema) — submission/notification channel definitions.
* **`language_master`** / **`language_alias`** (common schema) — canonical languages with locale codes, and the spellings that resolve to them; each tenant schema also carries its own `language_master_table`.
* **`notification_table`** (tenant schema) — a per-user notification table provisioned in every tenant schema; no service writes to it yet.

### 10.6 Anomalies & Status

* **`anomaly_table`** — detected anomalies (unreadable image, manual override, consecutive overrides, duplicate image, reading below previous, no / low / over water supply, no submission, implausible water supply, location mismatch) with type, status, reason, and the related scheme/operator and `flow_reading_id`, feeding dashboards.

### 10.7 Sync Tracking

* Onboarding and ongoing **integration sync** from State IT systems relies on de-duplication keys (`state_scheme_id`, `state_scheme_code`, `state_user_id`, `state_dept_id`) so bulk re-uploads update rather than duplicate.
* Submissions that do not match master data are tracked on the rows themselves: `flow_reading_table.ingestion_source` and the submitted ids, and `scheme_master_table.submitted_*_mismatch` / `id_mismatch_last_seen_at` for scheme ids that disagree with the stored ones.
* **`data_versions_table`** / **`reports_table`** (tenant schema) — a version counter per resource type, and generated report files cached against it in object storage.

### 10.8 Analytics Warehouse (`analytics_schema`)

A star schema fed asynchronously via Kafka, with its own Flyway migrations in analytics-service (`src/main/resources/db/migration`). Every table name carries a `_table` suffix (e.g. `dim_date_table`):

* **Dimensions:** `dim_date_table`, `dim_tenant_table` (with `dim_tenant_water_norm_table` and `dim_tenant_work_status_filter_table` keeping their history by effective date), `dim_user_table`, `dim_scheme_table` (carrying the scheme's `work_status` and `operating_status` as ingested from the tenant schema), `dim_lgd_location_table`, `dim_department_location_table`, `dim_user_scheme_mapping_table`
* **Facts:** `fact_meter_reading_table`, `fact_water_quantity_table` (daily quantity per scheme, the basis for LPCD and norm achievement), `fact_escalation_table`, `fact_scheme_performance_table` (performance score, last supply date), `fact_operator_attendance_table`, `fact_anomaly_table` (anomalies received from telemetry-service)
* **Pre-aggregations:** `fact_scheme_daily_table`, `fact_region_metrics_table`, `fact_submission_activity_hourly_table`, rebuilt from the facts above
* **Other:** `submission_attempt_table` (submissions rejected before any reading was stored)

{% hint style="danger" %}
Phone numbers and names are PII: stored encrypted (AES-256) with HMAC hashes for lookup, and never written to `INFO`/`WARN`/`ERROR` logs.
{% endhint %}
