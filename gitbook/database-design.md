# Database Design

## 10. Database Design

Data is stored in **PostgreSQL** using **schema-per-tenant** isolation: a shared `common_schema` holds cross-tenant metadata, and each state has its own `tenant_<stateCode>` schema. A separate PostgreSQL instance holds the **analytics star-schema warehouse**. The tables below are grouped by functional domain (column format: `name (type, notes)`).

### 10.1 Tenancy & Users

**`common_schema.tenant_master_table`** — the tenant registry

* `id (PK)`
* `state_code (varchar, e.g. mp/up)`
* `title (varchar, display name)`
* `status (int, lifecycle: INACTIVE → ONBOARDED → CONFIGURED → ACTIVE → SUSPENDED → ARCHIVED)`
* `api_key_hash (varchar, hashed tenant API key)`

**`common_schema.tenant_config_master_table`** — per-tenant key/value configuration

* `tenant_id (FK)`
* `config_key (varchar, e.g. water_norm, nudge_message_hindi)`
* `config_value (text)`

**`tenant_<state>.user_table`** — operators and officers within a tenant

* `id (PK)`, `uuid`
* `title (bytea, AES-256 encrypted name)`, `title_hash (varchar, HMAC lookup)`
* `email (varchar)`
* `user_type (int, OPERATOR / SECTION_OFFICER / DISTRICT_OFFICER / STATE_ADMIN)`
* `phone_number (bytea, AES-256 encrypted)`, `phone_number_hash (varchar, HMAC lookup)`
* `language_id (int)`, `status (int)`, `whatsapp_connection_id (bigint, Glific contact id)`

**`tenant_<state>.user_token_table`** — DB-backed refresh tokens and OTPs (hashed) for revocation.

### 10.2 Location & Hierarchies

* **`lgd_location_master_table`** — LGD nodes: State → District → Block → Panchayat → Village
* **`department_location_master_table`** — Departmental nodes: State → Zone → Circle → Division → Sub-Division

### 10.3 Schemes, Pumps & Assignments

**`scheme_master_table`**

* `id (PK)`, `uuid`
* `state_scheme_id (varchar, idempotent upload key)`, `centre_scheme_id (varchar, JJM id)`
* `scheme_name (varchar)`
* `fhtc_count (int)`, `planned_fhtc (int)`, `house_hold_count (int)`
* `latitude / longitude (decimal)`
* `work_status (int)`, `operating_status (int)`

* **`scheme_lgd_mapping_table` / `scheme_department_mapping_table`** — scheme ↔ location links
* **`user_scheme_mapping_table`** — operator ↔ scheme assignments

### 10.4 Readings & Submissions

**`flow_reading_table`** — one row per accepted meter reading

* `id (PK)`, `uuid`, `scheme_id (FK)`
* `reading_at (timestamp)`, `reading_date (date, daily de-duplication)`
* `extracted_reading (bigint, FlowVision AI value)`, `confirmed_reading (bigint, operator value)`
* `confidence (float)`, `quantity (bigint, delta vs previous reading)`
* `channel (int, 1=WhatsApp / 2=Web / 3=IoT)`, `image_url (varchar)`
* `reading_type (int, normal / meter-change / issue)`, `submission_status (int)`

### 10.5 Messaging & Nudge Configuration

* Notification templates and language keys live in `tenant_config_master_table` (`nudge_message_<lang>`, `escalation_message_<lang>`, `language_<id>`).
* **`channel_master_table`** (common schema) — submission/notification channel definitions.
* A notification/log table records dispatched nudges and escalations per tenant.

### 10.6 Anomalies & Status

* **`anomaly_table`** — detected anomalies (missed submission, implausible jump, outage) with type, status, and the related scheme/operator, feeding dashboards and escalations.

### 10.7 Sync Tracking

* Onboarding and ongoing **integration sync** from State IT systems is tracked with sync logs, error logs, and de-duplication keys (e.g. `state_scheme_id`) so bulk re-uploads update rather than duplicate.

### 10.8 Analytics Warehouse (separate instance)

A star schema fed asynchronously via Kafka:

* **Dimensions:** `dim_date`, `dim_tenant`, `dim_user`, `dim_scheme` (with an `is_active` flag for schemes idle ≥ 30 days), `dim_lgd_location`, `dim_department_location`, `dim_user_scheme_mapping`
* **Facts:** `fact_meter_reading`, `fact_water_quantity` (LPCD, norm achievement), `fact_escalation`, `fact_scheme_performance` (compliance rate)

{% hint style="danger" %}
Phone numbers and names are PII: stored encrypted (AES-256) with HMAC hashes for lookup, and never written to `INFO`/`WARN`/`ERROR` logs.
{% endhint %}
