# JalSoochak V2 — Project Overview

**JalSoochak** ("water informer" in Hindi) is a multi-tenant water management platform built for rural drinking water service delivery monitoring under India's **Jal Jeevan Mission (JJM)**. It tracks daily meter readings from field pump operators across Indian states, triggers automated notifications for missed readings, and provides analytics dashboards for program officers at state, district, and national levels.

---

## Table of Contents

1. [System Architecture](#1-system-architecture)
2. [Technology Stack](#2-technology-stack)
3. [Backend Services](#3-backend-services)
4. [Frontend](#4-frontend)
5. [Database Architecture](#5-database-architecture)
6. [Authentication & Security](#6-authentication--security)
7. [Kafka Event Architecture](#7-kafka-event-architecture)
8. [Core Workflows](#8-core-workflows)
9. [Notification Pipeline](#9-notification-pipeline)
10. [Analytics Data Warehouse](#10-analytics-data-warehouse)
11. [Configuration Reference](#11-configuration-reference)
12. [Infrastructure & Deployment](#12-infrastructure--deployment)
13. [Development Setup](#13-development-setup)
14. [Testing Strategy](#14-testing-strategy)

---

## 1. System Architecture

JalSoochak V2 is a **microservices system** built on Spring Boot 3.2.5 / Java 21. Services communicate synchronously via HTTP (through Eureka service discovery) and asynchronously via Apache Kafka.

```
┌─────────────────────────────────────────────────────────────┐
│                      React Frontend                         │
│              (Vite + TypeScript + Chakra UI)                │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS
┌──────────────────────────▼──────────────────────────────────┐
│                 API Gateway (Port 8080)                      │
│             Spring Cloud Gateway + Keycloak JWT             │
└──────┬──────────┬──────────┬──────────┬──────────┬──────────┘
       │          │          │          │          │
┌──────▼───┐ ┌───▼─────┐ ┌──▼──────┐ ┌─▼───────┐ ┌▼──────────┐
│tenant-svc│ │user-svc │ │telemetry│ │ scheme  │ │ analytics │
│  :8081   │ │  :8082  │ │  :8989  │ │  :8287  │ │   :8087   │
└──────┬───┘ └───┬─────┘ └────┬────┘ └──┬──────┘ └──┬────────┘
       │         │            │          │            │
┌──────▼─────────▼────────────▼──────────▼────────────▼───────┐
│                        Apache Kafka                          │
│  tenant-service-topic    user-service-topic                  │
│  scheme-service-topic    telemetry-service-topic             │
│  common-topic            anomaly-service-topic               │
└─────────────────────────┬────────────────────────────────────┘
                          │
               ┌──────────▼──────────┐
               │   message-service   │
               │       :8085         │
               │  (Spring WebFlux)   │
               └──────────┬──────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌────────────┐  ┌────────────┐  ┌───────────┐
   │   Glific   │  │  SendGrid  │  │SMSCountry │
   │ (WhatsApp) │  │  (Email)   │  │   (SMS)   │
   └────────────┘  └────────────┘  └───────────┘

Infrastructure:
  PostgreSQL :5432 (operational DB)    PostgreSQL :5433 (analytics DW)
  Redis :6379 (caching/sessions)       MinIO (object storage)
  Keycloak (identity provider)         FlowVision AI (meter image OCR)
  Eureka :8761 (service registry)
```

---

## 2. Technology Stack

### Backend

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21 (Eclipse Temurin) |
| Framework | Spring Boot 3.2.5 |
| Build | Maven 3.9+ |
| API | Spring Web MVC (REST) + Spring WebFlux (message-service) |
| Security | Spring Security + OAuth2 Resource Server (Keycloak JWT) |
| Persistence | Spring Data JPA + Hibernate + Flyway |
| Messaging | Apache Kafka (KRaft mode, no Zookeeper) |
| Service Registry | Netflix Eureka (Spring Cloud) |
| Caching | Redis (Spring Cache) |
| Object Storage | AWS SDK v2 (S3/MinIO-compatible) |
| PDF Generation | Apache PDFBox |

### Frontend

| Layer | Technology |
|-------|-----------|
| Runtime | Node.js (LTS) |
| Framework | React 18 + TypeScript |
| Build Tool | Vite |
| UI Library | Chakra UI v2 |
| State Management | Zustand + TanStack Query (React Query) |
| Routing | React Router v6 |
| Internationalisation | i18next + react-i18next (15 Indian languages) |
| HTTP Client | Axios |
| Charts | Recharts |

### Infrastructure

| Component | Technology |
|-----------|-----------|
| Databases | PostgreSQL 16 (operational + analytics) |
| Cache | Redis 7 |
| Message Broker | Apache Kafka (KRaft, 3-node cluster) |
| Identity Provider | Keycloak |
| Object Storage | MinIO (S3-compatible) |
| WhatsApp API | Glific (GraphQL) |
| Email | SendGrid (transactional templates) |
| SMS | SMSCountry |
| AI / OCR | FlowVision (meter image reading extraction) |

---

## 3. Backend Services

### Service Overview

| Service | Port | Purpose |
|---------|------|---------|
| [Service Discovery](service-discovery.md) | 8761 | Netflix Eureka server; service registry for all microservices |
| [Tenant Service](tenant-service.md) | 8081 | Tenant onboarding, configuration, database migrations, nudge/escalation schedulers |
| [User Service](user-service.md) | 8082 | Authentication (email + WhatsApp OTP), user lifecycle, PII encryption |
| [Telemetry Service](telemetry-service.md) | 8989 | Meter reading ingestion via Glific, web dashboard, and IoT; FlowVision AI integration |
| [Scheme Service](scheme-service.md) | 8287 | Water supply scheme management; LGD and department hierarchy mappings |
| [Message Service](message-service.md) | 8085 | Notification routing (WhatsApp, Email, SMS, Webhook); escalation PDF generation |
| [Analytics Service](analytics-service.md) | 8087 | Data warehouse consumer; national, state, and scheme-level BI queries |
| Anomaly Service | 8083 | Anomaly detection (documented separately) |

### Service Startup Order

Services must be started in this sequence:

```
1. PostgreSQL (operational + analytics instances)
2. Apache Kafka (KRaft cluster)
3. Redis
4. service-discovery (:8761)    ← all other services register here
5. tenant-service (:8081)       ← runs database migrations; wait for completion
6. All remaining services       ← can start in any order after step 5
```

{% hint style="warning" %}
Always wait for `tenant-service` to finish its Flyway migrations before starting other services. Starting them in parallel can cause services to connect before the schema is ready.
{% endhint %}

---

## 4. Frontend

### Directory Structure

```
frontend/
├── src/
│   ├── app/
│   │   ├── App.tsx              – Root component and routing
│   │   ├── Layout.tsx           – Shared sidebar and header layout
│   │   └── routes/              – Route definitions
│   ├── features/
│   │   ├── auth/                – Login, OTP, password reset, account activation
│   │   ├── tenants/             – Tenant management (Super User only)
│   │   ├── schemes/             – Scheme list, details, bulk upload
│   │   ├── users/               – Staff management, bulk upload
│   │   ├── telemetry/           – Meter reading list and manual entry
│   │   ├── analytics/           – Dashboard charts, compliance views
│   │   ├── notifications/       – Notification history
│   │   └── settings/            – Tenant configuration UI
│   ├── shared/
│   │   ├── components/          – Reusable UI components
│   │   ├── hooks/               – Custom React hooks
│   │   ├── utils/               – Date and number formatting helpers
│   │   └── api/                 – Axios client and API service wrappers
│   ├── config/
│   │   ├── api.ts               – Base URL and request interceptors
│   │   └── featureFlags.ts      – Feature toggles
│   ├── types/                   – Shared TypeScript interfaces
│   ├── assets/                  – SVGs, images, tenant logos
│   └── locales/                 – i18n JSON translation files (15 languages)
├── public/                      – Static assets
├── index.html
├── vite.config.ts
└── package.json
```

### Application Routes

| Route | Required Role | Description |
|-------|--------------|-------------|
| `/login` | Public | Email + password login |
| `/activate-account` | Public | Token-based account activation (from invite email) |
| `/reset-password` | Public | Token-based password reset |
| `/dashboard` | `STATE_ADMIN`, `DISTRICT_OFFICER`, `SECTION_OFFICER` | Main analytics dashboard |
| `/schemes` | All authenticated | Scheme list with filters |
| `/schemes/:id` | All authenticated | Single scheme detail and reading history |
| `/users` | `STATE_ADMIN` | Staff management |
| `/tenants` | `SUPER_USER` | Tenant management |
| `/analytics` | `SUPER_USER`, `STATE_ADMIN` | Business intelligence dashboards |
| `/settings` | `STATE_ADMIN` | Tenant configuration |

### Internationalisation

Supports 15 Indian languages via `i18next`:

| Language | | Language | |
|----------|-|----------|-|
| Hindi | Bengali | Telugu | Marathi |
| Tamil | Gujarati | Kannada | Malayalam |
| Odia | Punjabi | Assamese | Urdu |
| Maithili | Sanskrit | English | |

Language preference is set per user and stored in the database. All notification messages (WhatsApp nudges and escalations) are also sent in the operator's preferred language.

---

## 5. Database Architecture

### Multi-Tenancy: Schema Per Tenant

JalSoochak V2 uses a **schema-per-tenant** strategy in PostgreSQL. Each state gets its own isolated PostgreSQL schema within the same database instance.

```
jalsoochak_db  (PostgreSQL — operational)
├── common_schema                    ← Shared across all tenants
│   ├── tenant_master_table
│   ├── tenant_admin_user_master_table
│   ├── tenant_config_master_table
│   ├── user_type_master_table
│   ├── channel_master_table
│   └── language_master_table
│
├── tenant_mp                        ← Madhya Pradesh tenant schema
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
└── tenant_<stateCode>               ← Dynamically created per state

jalsoochak_analytics_db  (PostgreSQL — analytics data warehouse)
└── analytics_schema
    ├── dim_date_table
    ├── dim_tenant_table
    ├── dim_user_table
    ├── dim_scheme_table
    ├── dim_lgd_location_table
    ├── dim_department_location_table
    ├── dim_user_scheme_mapping_table
    ├── dim_operator_attendance
    ├── fact_meter_reading_table
    ├── fact_water_quantity_table
    ├── fact_escalation_table
    └── fact_scheme_performance_table
```

### Schema Provisioning

New tenant schemas are created automatically at runtime when a tenant is onboarded via the API. The provisioning function is idempotent — safe to call multiple times without creating duplicate tables.

{% hint style="info" %}
The analytics database is a **separate PostgreSQL instance** (default port 5433) to keep BI queries isolated from operational read/write traffic.
{% endhint %}

### Database Migrations

All schema migrations are managed by Flyway and run automatically on `tenant-service` startup.

| Migration Range | Description |
|----------------|-------------|
| V1 | Create `common_schema` and all shared tables |
| V2 | Register the tenant schema provisioning function |
| V3–V10 | Seed user types, language master data |
| V11–V20 | Schema enhancements: indexes, constraints |
| V21–V28 | PII hash columns, token table backfill |

{% hint style="warning" %}
`tenant-service` is the **sole owner** of Flyway migrations. Do not run migrations from any other service or manually unless explicitly required for a hotfix.
{% endhint %}

---

## 6. Authentication & Security

### JWT + Keycloak

All backend services are OAuth2 resource servers. Authentication is handled by **Keycloak**, which issues JWTs containing custom claims to identify the user's tenant and role.

**Custom JWT Claims** (require Keycloak mapper configuration):

| Claim | Example Value | Purpose |
|-------|--------------|---------|
| `realm_access.roles` | `["SUPER_USER"]` | Global platform roles |
| `tenant_state_code` | `"mp"` | Identifies which tenant schema to use |
| `user_type` | `"OPERATOR"` | User's functional role within the tenant |

**How authorities are resolved from the JWT:**

```
realm_access.roles["SUPER_USER"]  →  ROLE_SUPER_USER
realm_access.roles["STATE_ADMIN"] →  ROLE_STATE_ADMIN
tenant_state_code = "mp"          →  TENANT_MP
user_type = "OPERATOR"            →  USER_TYPE_OPERATOR
```

### User Roles

| Role | Access Level |
|------|-------------|
| `SUPER_USER` | Global platform admin; can create tenants and manage super admins |
| `STATE_ADMIN` | Tenant-wide admin; manages staff, scheme data, and configuration |
| `DISTRICT_OFFICER` | L2 escalation recipient; district-level visibility across schemes |
| `SECTION_OFFICER` | L1 escalation recipient; section-level visibility |
| `OPERATOR` | Field worker; submits daily meter readings via WhatsApp |

### PII Protection

Phone numbers and user names/titles are classified as **personally identifiable information (PII)** and are protected at rest:

| Data Field | Storage Method | Lookup Method |
|-----------|---------------|--------------|
| Phone numbers | AES-256 (CBC) encrypted | HMAC-SHA256 hash stored in `phone_number_hash` |
| User titles/names | AES-256 (CBC) encrypted | HMAC-SHA256 hash stored in `title_hash` |

{% hint style="danger" %}
Phone numbers must **never** appear in `INFO`, `WARN`, or `ERROR` log statements. They may only be logged at `DEBUG` level.
{% endhint %}

### Keycloak Setup Requirements

When configuring a new Keycloak instance for JalSoochak:

1. Create realm: `jalsoochak-realm`
2. Create client: `jalsoochak-client` (standard flow + bearer-only)
3. Add custom user attribute mappers to the client:
   - Attribute `tenant_state_code` → JWT claim `tenant_state_code`
   - Attribute `user_type` → JWT claim `user_type`
4. Create realm roles: `SUPER_USER`, `STATE_ADMIN`
5. Generate and securely store the client secret

---

## 7. Kafka Event Architecture

### Topics

| Topic | Producing Service | Consuming Services |
|-------|------------------|-------------------|
| `tenant-service-topic` | tenant-service | analytics-service |
| `user-service-topic` | user-service | analytics-service |
| `scheme-service-topic` | scheme-service | analytics-service |
| `telemetry-service-topic` | telemetry-service | analytics-service |
| `anomaly-service-topic` | anomaly-service | analytics-service |
| `common-topic` | All services | message-service, tenant-service, user-service |

### Event Catalog

| Event Type | Producing Service | Consuming Service(s) | Description |
|------------|------------------|---------------------|-------------|
| `TENANT_CREATED` | tenant-service | analytics-service | New state tenant provisioned |
| `TENANT_UPDATED` | tenant-service | analytics-service | Tenant config or status changed |
| `USER_CREATED` | user-service | analytics-service | New user registered |
| `USER_UPDATED` | user-service | analytics-service | User profile or role changed |
| `SCHEME_CREATED` | scheme-service | analytics-service | New scheme added |
| `SCHEME_UPDATED` | scheme-service | analytics-service | Scheme attributes updated |
| `METER_READING_SUBMITTED` | telemetry-service | analytics-service | Operator reading accepted and persisted |
| `NUDGE` | tenant-service | message-service | Operator missed today's reading — trigger reminder |
| `ESCALATION` | tenant-service | message-service | Persistent non-upload — alert assigned officer |
| `SEND_INVITE_EMAIL` | user-service | message-service | New staff invitation email |
| `SEND_REINVITE_EMAIL` | user-service | message-service | Re-send invitation email |
| `SEND_PASSWORD_RESET_EMAIL` | user-service | message-service | Password reset email |
| `SEND_WELCOME_MESSAGE` | user-service | message-service | WhatsApp welcome on account activation |
| `SEND_OTP` | user-service | message-service | WhatsApp OTP for staff login |
| `WHATSAPP_CONTACT_REGISTERED` | message-service | tenant-service, user-service | Glific contact ID obtained for operator |
| `ANOMALY_DETECTED` | anomaly-service | analytics-service | Anomaly record created |

### Dead-Letter Topics

When message delivery fails, events are routed to dead-letter topics for manual review and retry:

| Dead-Letter Topic | Failed Event Types | Common Cause |
|------------------|--------------------|-------------|
| `welcome-message-dlt` | `SEND_WELCOME_MESSAGE` | Blank phone number or missing WhatsApp connection ID |
| `account-email-dlt` | All `SEND_*_EMAIL` events | SendGrid API failure, blank email address |

---

## 8. Core Workflows

### Meter Reading Submission (WhatsApp Flow)

This is the primary data collection workflow used by field pump operators:

```
1.  Operator opens the JalSoochak WhatsApp flow via Glific
2.  Flow prompts: "Take a photo of the flow meter"
3.  Operator submits meter photo
4.  Glific forwards the image URL to POST /api/v1/observations (telemetry-service)
5.  telemetry-service calls FlowVision AI → extracts numeric reading + confidence score
      ├─ Confidence ≥ 85%: reading auto-accepted
      │   Operator is shown: "Extracted reading: 1250 — is this correct?"
      └─ Confidence < 85%: operator is prompted to enter the reading manually
6.  Operator confirms or overrides in the WhatsApp chat
7.  Glific sends the final confirmation back to telemetry-service
8.  telemetry-service validates the reading:
      ├─ Must be ≥ previous reading (meters don't run backwards)
      ├─ Delta must be within the expected range (outlier detection)
      └─ Only one reading per scheme per calendar day is allowed
9.  Reading is persisted to flow_reading_table
10. METER_READING_SUBMITTED event published to telemetry-service-topic
11. analytics-service consumes the event and updates the data warehouse
```

### New Tenant Onboarding

```
1. Super User creates tenant via POST /api/v1/tenants
2. tenant-service provisions a new PostgreSQL schema for the state
3. TENANT_CREATED event published → analytics-service syncs dimension tables
4. State Admin logs in and completes configuration:
     ├─ Set language, water norms, escalation thresholds (tenant config API)
     ├─ Upload LGD location hierarchy (CSV)
     ├─ Upload departmental hierarchy (CSV)
     ├─ Bulk upload water supply schemes (CSV)
     ├─ Bulk upload pump operators (CSV)
     └─ Upload operator-to-scheme mappings (CSV)
5. Tenant is marked ACTIVE and field operations can begin
```

### Staff Invitation Flow

```
1. State Admin invites a new staff member via POST /api/v1/users/invitations
2. A secure invite token is generated (expires in 7 days)
3. SEND_INVITE_EMAIL event published → message-service sends invitation email via SendGrid
4. Staff receives email with activation link: /activate-account?token=<token>
5. Staff sets their password: POST /api/v1/auth/invites/activate
6. Keycloak account is created; invite marked as USED
7. SEND_WELCOME_MESSAGE event published → WhatsApp welcome message sent to staff
```

---

## 9. Notification Pipeline

### Nudge (Daily Reminder)

Runs every morning for operators who have not submitted a reading that day:

```
NudgeSchedulerService [configurable cron — default 10:30 AM IST]
      │
      ├─ For each active tenant:
      │   Query operators with no reading submitted today
      │
      └─ For each operator found:
          Publish NUDGE event → common-topic
                │
          message-service (NotificationEventRouter)
                │
          Resolve localised message text
          (language_id → config key → translated template)
                │
          GlificGraphQLClient.sendHsmMessage(templateId, [operatorName, date])
                │
          Operator receives WhatsApp reminder
```

### Escalation (Officer Alert)

Runs every morning for operators with a streak of missed readings:

```
EscalationSchedulerService [configurable cron — default 10:32 AM IST]
      │
      ├─ Level 1 (Section Officer): operator missed ≥ L1 threshold days
      └─ Level 2 (District Officer): operator missed ≥ L2 threshold days
                                     or has never submitted a reading
      │
      Publish ESCALATION event → common-topic
            │
      message-service (NotificationEventRouter)
            │
      EscalationPdfService → generate PDF report of affected operators
            │
      MinioStorageService → upload PDF → get public URL
            │
      GlificGraphQLClient.createMessageMedia(url) → get mediaId
            │
      GlificGraphQLClient.createAndSendMessage(templateId, mediaId, officerPhone, text)
            │
      Officer receives WhatsApp message with PDF attachment
```

{% hint style="info" %}
Both nudge and escalation thresholds (day counts, cron schedules) are configurable per tenant via the tenant configuration API. There is no need to redeploy the service to change these values.
{% endhint %}

---

## 10. Analytics Data Warehouse

The Analytics Service maintains a star schema in a dedicated PostgreSQL database, enabling fast BI queries without impacting operational performance.

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

Additional Fact Tables:
  fact_water_quantity_table      – Daily LPCD calculations per scheme
  fact_escalation_table          – Escalation events with resolution tracking
  fact_scheme_performance_table  – Compliance rates over configurable periods

Additional Dimension Tables:
  dim_lgd_location_table         – LGD hierarchy (State → District → Block → Panchayat → Village)
  dim_department_location_table  – Departmental hierarchy
  dim_user_scheme_mapping_table  – Operator–scheme assignments over time
  dim_operator_attendance        – Daily operator presence tracking
```

**Key Metrics Computed:**

| Metric | Formula |
|--------|---------|
| **Compliance Rate** | `(days_with_reading ÷ total_active_days) × 100` per scheme |
| **LPCD** | `daily_quantity ÷ FHTC count` (litres per capita per day) |
| **Norm Achievement** | Whether LPCD meets the tenant's configured water norm |

---

## 11. Configuration Reference

### Environment Variables

{% hint style="danger" %}
Never commit real credentials to version control. Use secrets management (Kubernetes Secrets, Vault, or your deployment platform's secret store) for all values marked with `<...>`.
{% endhint %}

#### Database

```bash
# Operational database
SPRING_DATASOURCE_URL=jdbc:postgresql://<db-host>:5432/jalsoochak_db
SPRING_DATASOURCE_USERNAME=<db-username>
SPRING_DATASOURCE_PASSWORD=<db-password>

# Analytics database (analytics-service only)
SPRING_DATASOURCE_URL=jdbc:postgresql://<analytics-db-host>:5433/jalsoochak_analytics_db
```

#### Kafka

```bash
SPRING_KAFKA_BOOTSTRAP_SERVERS=<kafka-broker-1>:9092,<kafka-broker-2>:9092,<kafka-broker-3>:9092
```

#### Redis

```bash
REDIS_HOST=<redis-host>
REDIS_PORT=6379
REDIS_DATABASE=0
```

#### Keycloak

```bash
KEYCLOAK_ISSUER_URI=https://<keycloak-host>/realms/jalsoochak-realm
KEYCLOAK_AUTH_SERVER_URL=https://<keycloak-host>
KEYCLOAK_CLIENT_SECRET=<client-secret>
KEYCLOAK_ADMIN_USERNAME=<admin-username>
KEYCLOAK_ADMIN_PASSWORD=<admin-password>
```

#### PII Encryption

```bash
# Both must be base64-encoded 256-bit keys
PII_ENCRYPTION_KEY=<base64-encoded-aes-256-key>
PII_HMAC_KEY=<base64-encoded-hmac-256-key>
```

#### Service Discovery

```bash
EUREKA_ENABLED=true
EUREKA_URL=http://<eureka-host>:8761/eureka/
```

#### WhatsApp (Glific)

```bash
GLIFIC_API_URL=<glific-graphql-api-url>
GLIFIC_API_KEY=<glific-api-key>
GLIFIC_NUDGE_TEMPLATE_ID=<glific-nudge-hsm-template-id>
GLIFIC_ESCALATION_TEMPLATE_ID=<glific-escalation-hsm-template-id>
GLIFIC_NUDGE_FLOW_ID=<glific-nudge-flow-id>
```

#### Email (SendGrid)

```bash
SENDGRID_API_KEY=<sendgrid-api-key>
SENDGRID_TEMPLATE_DEFAULT_INVITATION=<sendgrid-invitation-template-id>
SENDGRID_TEMPLATE_PASSWORD_RESET=<sendgrid-password-reset-template-id>
```

#### Object Storage (MinIO / S3)

```bash
# For escalation PDF uploads
MINIO_ENDPOINT=<minio-or-s3-endpoint>
MINIO_ACCESS_KEY=<access-key>
MINIO_SECRET_KEY=<secret-key>
MINIO_BUCKET=<escalation-reports-bucket-name>
MINIO_BASE_URL=<public-base-url>

# For tenant assets (logos, etc.)
STORAGE_ENABLED=true
STORAGE_PROVIDER=s3
STORAGE_ENDPOINT=<s3-endpoint>
STORAGE_REGION=<aws-region>
STORAGE_ACCESS_KEY=<access-key>
STORAGE_SECRET_KEY=<secret-key>
STORAGE_BUCKET=<tenant-assets-bucket-name>
```

#### SMS (SMSCountry)

```bash
SMSCOUNTRY_BASE_URL=<smscountry-api-base-url>
SMSCOUNTRY_AUTH_KEY=<auth-key>
SMSCOUNTRY_AUTH_TOKEN=<auth-token>
SMSCOUNTRY_SENDER_ID=<approved-sender-id>
```

#### AI Meter Reading (FlowVision)

```bash
FLOWVISION_URL=<flowvision-api-endpoint>
```

#### Scheduling

```bash
# Cron expressions (Spring format: second minute hour day month weekday)
NUDGE_CRON=0 30 10 * * *           # Default: 10:30 AM IST
ESCALATION_CRON=0 32 10 * * *      # Default: 10:32 AM IST
ANALYTICS_SCHEDULER_CRON=0 0 19 * * *   # Default: 7 PM IST
ANALYTICS_SCHEDULER_ZONE=Asia/Kolkata
```

#### Logging & Debug

```bash
LOG_FILE_PATH=<path-to-log-file>

# Set to true to log notification events without sending actual WhatsApp/email messages
# Useful for staging environments
NOTIFICATIONS_DRY_RUN=false
```

---

## 12. Infrastructure & Deployment

### Health Checks

All services expose Spring Actuator endpoints for health monitoring and Prometheus metrics scraping:

```
GET http://<host>:<port>/actuator/health
GET http://<host>:<port>/actuator/info
GET http://<host>:<port>/actuator/prometheus
```

### Swagger / API Documentation

Each service exposes interactive API documentation at runtime:

```
Swagger UI:   http://<host>:<port>/swagger-ui/index.html
OpenAPI JSON: http://<host>:<port>/v3/api-docs
```

{% hint style="info" %}
Swagger documentation is **auto-generated** at runtime from the service's code and annotations. It updates automatically on every deployment — no manual maintenance is required.
{% endhint %}

### Deployment Prerequisites

| Component | Minimum Version | Notes |
|-----------|----------------|-------|
| Java | 21 (Eclipse Temurin) | Required for all backend services |
| PostgreSQL | 16 | Two separate instances required |
| Apache Kafka | 3.6+ | KRaft mode (no Zookeeper) |
| Redis | 7 | Single node or cluster |
| Keycloak | 23+ | Custom attribute mappers must be configured |
| MinIO | Latest | Or any S3-compatible object store |

### Kubernetes Deployment Notes

- Deploy each service as a separate `Deployment`
- Use `ConfigMap` for non-sensitive `application.yml` overrides
- Use `Secret` for all credentials (DB passwords, API keys, PII encryption keys)
- Use `StatefulSet` for PostgreSQL, Kafka, and Redis
- Configure `readinessProbe` pointing to `/actuator/health` for all services

---

## 13. Development Setup

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Maven 3.9+
- Docker and Docker Compose
- Node.js LTS (for frontend development)

{% hint style="warning" %}
If your system Java is newer than 21 (e.g., JDK 23+), set `JAVA_HOME` explicitly to a JDK 21 installation before building. Using a newer JDK will cause annotation processing failures during the build.
{% endhint %}

### Backend

```bash
# 1. Start infrastructure services
docker compose up -d postgres redis kafka minio

# 2. Build any service (skip tests for faster iteration)
cd backend/<service-name>
mvn clean package -DskipTests

# 3. Start service-discovery first
cd backend/service-discovery && mvn spring-boot:run

# 4. Start tenant-service and wait for migrations to complete
cd backend/tenant-service && mvn spring-boot:run

# 5. Start remaining services (any order)
cd backend/user-service && mvn spring-boot:run
cd backend/telemetry-service && mvn spring-boot:run
cd backend/scheme-service && mvn spring-boot:run
cd backend/message-service && mvn spring-boot:run
cd backend/analytics-service && mvn spring-boot:run
```

### Frontend

```bash
cd frontend
npm install
npm run dev        # Development server — http://localhost:5173
npm run build      # Production build → dist/
```

### Running Tests

```bash
# All tests for a service (Docker required for Testcontainers)
cd backend/<service-name>
mvn test

# Single test class
mvn test -Dtest=ClassName

# Single test method
mvn test -Dtest=ClassName#methodName
```

---

## 14. Testing Strategy

### Approach

The project follows **Test-Driven Development (TDD)**. Every production code change must ship with a corresponding test. No exceptions.

### Test Types

| Type | Framework | Scope |
|------|-----------|-------|
| Unit | Mockito | Business logic in isolation; no Spring context, no database |
| Integration (DB) | Testcontainers + `@SpringBootTest` | JPA queries, schema correctness against a real PostgreSQL container |
| Integration (HTTP) | WireMock | External API clients (Glific, SendGrid) stubbed locally |
| Controller | MockMvc | REST endpoint routing and authorization rules |

### Key Rules

1. **Use real databases in integration tests** — Testcontainers spins up a real PostgreSQL container. Never mock the database.
2. **Use WireMock for external HTTP** — Never call real Glific or SendGrid endpoints in tests.
3. **Disable infrastructure in test config** — `application.properties` in each test module disables Flyway, Eureka, and Kafka fail-fast.
4. **Suppress startup side effects** — Use `@MockBean` for beans that make network calls in `@PostConstruct` (e.g., Glific auth service).
5. **Test phone numbers are synthetic** — Use `91XXXXXXXXXX` format with non-real numbers. Never log them at INFO level even in tests.

---

## Related Documentation

| Document | Description |
|----------|-------------|
| [Tenant Service](tenant-service.md) | Tenant onboarding, database migrations, nudge/escalation scheduler |
| [User Service](user-service.md) | Authentication flows, user lifecycle, PII encryption |
| [Telemetry Service](telemetry-service.md) | Meter reading ingestion, FlowVision AI integration |
| [Scheme Service](scheme-service.md) | Scheme management, LGD and department mappings |
| [Message Service](message-service.md) | Notification routing, Glific, SendGrid, escalation PDF |
| [Analytics Service](analytics-service.md) | Data warehouse, star schema, BI query APIs |
| [Service Discovery](service-discovery.md) | Eureka server and service registry |
| [Notification Flows](notification-flows.md) | Detailed WhatsApp notification pipeline |
