# Database Design

## 1. Overview

JalSoochak V2 uses two PostgreSQL database instances:

* **Operational database** (`jalsoochak_db`, port 5432) — all transactional data with schema-per-tenant isolation
* **Analytics database** (`jalsoochak_analytics_db`, port 5433) — star schema data warehouse for BI queries, kept separate to protect operational performance

Schema migrations for the operational database are managed exclusively by Flyway, running automatically when `tenant-service` starts.

---

## 2. Multi-Tenancy: Schema Per Tenant

Each state tenant has a dedicated PostgreSQL schema within the operational database. This provides full data isolation without the complexity of separate database instances per tenant.

```
jalsoochak_db
├── common_schema                        ← Shared across all tenants
│   ├── tenant_master_table
│   ├── tenant_admin_user_master_table
│   ├── tenant_config_master_table
│   ├── user_type_master_table
│   ├── channel_master_table
│   └── language_master_table
│
├── tenant_mp                            ← Madhya Pradesh tenant schema
│   ├── user_table
│   ├── scheme_master_table
│   ├── flow_reading_table
│   ├── lgd_location_master_table
│   ├── department_location_master_table
│   ├── scheme_lgd_mapping_table
│   ├── scheme_department_mapping_table
│   ├── user_scheme_mapping_table
│   ├── notification_table
│   ├── anomaly_table
│   └── user_invite_table
│
└── tenant_<stateCode>                   ← Created dynamically on tenant onboarding
```

### 2.1 Schema Provisioning

A new tenant schema is created automatically when the Super User provisions a new tenant via the API. The provisioning PL/pgSQL function (`create_tenant_schema()`) is registered in the Flyway migration `V2` and is idempotent — safe to call multiple times.

{% hint style="warning" %}
`tenant-service` is the **sole owner** of Flyway migrations. Do not run migrations manually or from any other service, as doing so risks version inconsistencies.
{% endhint %}

---

## 3. Common Schema Tables

### `tenant_master_table`

The tenant registry. One row per state tenant.

| Column | Type | Description |
|--------|------|-------------|
| `id` | SERIAL | Primary key |
| `state_code` | VARCHAR | Two-letter state code (e.g. `mp`, `up`) |
| `title` | VARCHAR | Display name of the state tenant |
| `status` | INTEGER | Tenant lifecycle status (see below) |
| `created_at` | TIMESTAMP | Provisioning timestamp |

**Tenant Status Codes:**

| Code | Status | Description |
|------|--------|-------------|
| `0` | `INACTIVE` | Registered but not yet configured |
| `1` | `ONBOARDED` | PostgreSQL schema provisioned |
| `2` | `CONFIGURED` | Configuration keys set |
| `3` | `ACTIVE` | Fully operational |
| `4` | `SUSPENDED` | Temporarily disabled |
| `5` | `DEGRADED` | Partial service failure |
| `6` | `ARCHIVED` | Retired tenant |

### `tenant_config_master_table`

Per-tenant key-value configuration store. Supports per-tenant customisation without code changes.

| Column | Type | Description |
|--------|------|-------------|
| `tenant_id` | INTEGER | FK → `tenant_master_table` |
| `config_key` | VARCHAR | Configuration key (e.g. `nudge_message_hindi`) |
| `config_value` | TEXT | Configuration value |

**Standard configuration keys:**

| Key Pattern | Purpose |
|-------------|---------|
| `language_<id>` | Language name for a given language ID (e.g. `language_1` = `Hindi`) |
| `nudge_message_<langKey>` | Localised nudge message template |
| `escalation_message_<langKey>` | Localised escalation message template |
| `water_norm` | Expected LPCD (litres per capita per day) |
| `escalation_l1_threshold` | Days missed before Level 1 escalation fires (default: 3) |
| `escalation_l2_threshold` | Days missed before Level 2 escalation fires (default: 7) |
| `nudge_cron` | Cron expression for the nudge scheduler |
| `escalation_cron` | Cron expression for the escalation scheduler |

---

## 4. Tenant Schema Tables

### `user_table`

Stores all users for a tenant: operators, section officers, district officers.

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGSERIAL | Primary key |
| `uuid` | UUID | Public identifier |
| `tenant_id` | INTEGER | FK → `common_schema.tenant_master_table` |
| `title` | BYTEA | AES-256 encrypted full name |
| `title_hash` | VARCHAR | HMAC-SHA256 for equality lookups |
| `email` | VARCHAR | Login email address |
| `user_type` | INTEGER | Role (1=SUPER_USER, 2=STATE_ADMIN, 3=DISTRICT_OFFICER, 4=SECTION_OFFICER, 5=OPERATOR) |
| `phone_number` | BYTEA | AES-256 encrypted phone number |
| `phone_number_hash` | VARCHAR | HMAC-SHA256 for lookup without decryption |
| `language_id` | INTEGER | Preferred language for notifications |
| `status` | INTEGER | 0=INACTIVE, 1=ACTIVE, 2=SUSPENDED |
| `whatsapp_connection_id` | BIGINT | Glific contact ID (set after first WhatsApp delivery) |
| `phone_verification_status` | INTEGER | 0=UNVERIFIED, 1=VERIFIED |

### `scheme_master_table`

Water supply schemes — the primary operational units tracked by the platform.

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGSERIAL | Primary key |
| `uuid` | UUID | Public identifier |
| `state_scheme_id` | VARCHAR | State-assigned unique scheme code (idempotent upload key) |
| `centre_scheme_id` | VARCHAR | Central government JJM scheme identifier |
| `scheme_name` | VARCHAR | Human-readable scheme name |
| `fhtc_count` | INTEGER | Functional Household Tap Connections |
| `planned_fhtc` | INTEGER | Target FHTC count |
| `house_hold_count` | INTEGER | Total households served |
| `latitude` | DECIMAL | GPS latitude |
| `longitude` | DECIMAL | GPS longitude |
| `work_status` | INTEGER | Construction status (0=PLANNED, 1=IN_PROGRESS, 2=COMPLETED, 3=COMMISSIONED) |
| `operating_status` | INTEGER | Operational status (0=NOT_STARTED, 1=FUNCTIONAL, 2=PARTIALLY_FUNCTIONAL, 3=NON_FUNCTIONAL, 4=UNDER_MAINTENANCE) |

### `flow_reading_table`

One row per accepted meter reading. The central fact table of the operational database.

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGSERIAL | Primary key |
| `uuid` | UUID | Public identifier |
| `scheme_id` | BIGINT | FK → `scheme_master_table` |
| `reading_at` | TIMESTAMP | Exact submission timestamp |
| `reading_date` | DATE | Calendar date (used for daily deduplication) |
| `extracted_reading` | BIGINT | Value extracted by FlowVision AI |
| `confirmed_reading` | BIGINT | Final value confirmed by the operator |
| `confidence` | FLOAT | AI confidence score (0–100) |
| `quantity` | BIGINT | Delta from previous reading in litres |
| `channel` | INTEGER | 1=WhatsApp, 2=Web, 3=IoT |
| `image_url` | VARCHAR | Object storage URL of the meter photo |
| `reading_type` | INTEGER | 1=Normal, 2=Meter replacement, 3=Issue report |
| `submission_status` | INTEGER | 1=Submitted, 2=Confirmed, 3=Rejected |

### `user_token_table`

Database-backed refresh token and OTP storage for revocation support.

| Column | Type | Description |
|--------|------|-------------|
| `user_id` | BIGINT | FK → `user_table` |
| `token_hash` | VARCHAR | SHA-256 hash of the token |
| `token_type` | VARCHAR | `REFRESH` or `OTP` |
| `expires_at` | TIMESTAMP | Token expiry time |
| `revoked` | BOOLEAN | Whether the token has been invalidated |

---

## 5. PII Storage

Phone numbers and user names are classified as PII and stored encrypted at rest.

| Field | Encrypted Column | Hash Column | Purpose of Hash |
|-------|-----------------|-------------|----------------|
| Phone number | `phone_number` (BYTEA) | `phone_number_hash` (VARCHAR) | Equality lookups without decryption |
| User name/title | `title` (BYTEA) | `title_hash` (VARCHAR) | Equality lookups without decryption |

* **Encryption:** AES-256 CBC mode using `PII_ENCRYPTION_KEY`
* **Hash:** HMAC-SHA256 using `PII_HMAC_KEY`

{% hint style="danger" %}
The `PII_ENCRYPTION_KEY` and `PII_HMAC_KEY` must be kept secret and rotated according to your organisation's key management policy. Losing these keys means encrypted data cannot be recovered.
{% endhint %}

---

## 6. Analytics Data Warehouse

The analytics database (`jalsoochak_analytics_db`) uses a star schema for efficient BI queries.

### 6.1 Dimension Tables

| Table | Description |
|-------|-------------|
| `dim_date_table` | Pre-populated date dimension (day, week, month, quarter, year attributes) |
| `dim_tenant_table` | State tenant registry |
| `dim_user_table` | User dimension (operators, officers) |
| `dim_scheme_table` | Water supply scheme dimension; `is_active` flag for schemes idle ≥ 30 days |
| `dim_lgd_location_table` | LGD administrative hierarchy (State → District → Block → Panchayat → Village) |
| `dim_department_location_table` | Departmental hierarchy (State → Zone → Circle → Division → Sub-Division) |
| `dim_user_scheme_mapping_table` | Operator–scheme assignments over time |
| `dim_operator_attendance` | Daily operator presence tracking (`has_reading`, `days_missed`) |

### 6.2 Fact Tables

| Table | Grain | Key Metrics |
|-------|-------|-------------|
| `fact_meter_reading_table` | One row per accepted reading | `extracted_reading`, `confirmed_reading`, `confidence`, `quantity`, `channel` |
| `fact_water_quantity_table` | One row per scheme per day | `daily_quantity`, `lpcd`, `water_norm`, `norm_achieved` |
| `fact_escalation_table` | One row per escalation event | `escalation_level`, `operator_count`, `days_missed`, `triggered_at`, `resolved_at` |
| `fact_scheme_performance_table` | One row per scheme per period | `total_days`, `days_with_reading`, `compliance_rate`, `avg_daily_quantity` |

### 6.3 Star Schema Diagram

```
              ┌─────────────┐
              │  dim_date   │
              └──────┬──────┘
                     │
┌──────────┐  ┌──────▼──────────────┐  ┌────────────┐
│dim_tenant├──┤ fact_meter_reading  ├──┤ dim_scheme │
└──────────┘  └──────┬──────────────┘  └────────────┘
                     │
              ┌──────▼──────┐
              │  dim_user   │
              └─────────────┘
```

---

## 7. Flyway Migrations

All schema migrations are in `backend/database/` and run automatically on `tenant-service` startup.

| Migration Range | Description |
|----------------|-------------|
| V1 | Create `common_schema` and all shared tables |
| V2 | Register the `create_tenant_schema()` PL/pgSQL provisioning function |
| V3–V10 | Seed user types and language master data |
| V11–V20 | Schema enhancements: indexes and constraints |
| V21–V28 | PII hash columns, token table backfill for existing tenants |
