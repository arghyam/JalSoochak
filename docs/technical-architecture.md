# Technical Architecture

## 1. Architectural Style

JalSoochak V2 uses a **microservices architecture** implemented in Java 21 + Spring Boot 3.2.5.

Key characteristics:

* Each service owns a bounded domain, runs independently, and exposes a REST API
* **Synchronous communication** between services via HTTP, using Netflix Eureka for service discovery (services call each other by logical name, not hardcoded host/port)
* **Asynchronous communication** via Apache Kafka for event-driven workflows (meter readings, notifications, analytics sync)
* **Schema-per-tenant multi-tenancy** in PostgreSQL — each state tenant has a fully isolated database schema
* **Keycloak** for identity and access management — JWTs carry tenant and role claims consumed by all services

---

## 2. System Architecture Diagram

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

Supporting Infrastructure:
  PostgreSQL :5432 (operational DB)    PostgreSQL :5433 (analytics DW)
  Redis :6379 (caching/sessions)       MinIO (object storage)
  Keycloak (identity provider)         FlowVision AI (meter image OCR)
  Eureka :8761 (service registry)
```

---

## 3. Backend Services

| Service | Port | Responsibility |
|---------|------|---------------|
| **service-discovery** | 8761 | Netflix Eureka server; service registry for all microservices |
| **tenant-service** | 8081 | Tenant onboarding, schema provisioning, configuration, nudge/escalation schedulers, Flyway migrations |
| **user-service** | 8082 | Authentication (email + WhatsApp OTP), user lifecycle, PII encryption, staff invitations |
| **telemetry-service** | 8989 | Meter reading ingestion via Glific webhook; FlowVision AI integration; reading validation |
| **scheme-service** | 8287 | Water supply scheme management; LGD and departmental hierarchy |
| **message-service** | 8085 | Notification delivery (WhatsApp via Glific, email via SendGrid, SMS); escalation PDF generation |
| **analytics-service** | 8087 | Data warehouse consumer; star schema population; BI query APIs |
| **anomaly-service** | 8083 | Anomaly detection on submitted readings |

### 3.1 Service Startup Order

Services must start in this sequence:

```
1. PostgreSQL (operational + analytics instances)
2. Apache Kafka (KRaft cluster)
3. Redis
4. service-discovery (:8761)    ← all other services register here
5. tenant-service (:8081)       ← runs Flyway migrations; wait for completion
6. All remaining services       ← any order after step 5
```

{% hint style="warning" %}
Always wait for `tenant-service` to finish its Flyway migrations before starting other services. Other services may fail to connect or query tables that do not yet exist if started in parallel.
{% endhint %}

---

## 4. Multi-Tenancy Model

JalSoochak uses a **schema-per-tenant** strategy in PostgreSQL. Each state deployment gets a fully isolated schema within the same database instance.

```
jalsoochak_db
├── common_schema                    ← Shared across all tenants
│   ├── tenant_master_table          (state tenants)
│   ├── tenant_admin_user_master_table
│   ├── tenant_config_master_table   (per-tenant config key-values)
│   ├── user_type_master_table
│   └── language_master_table
│
├── tenant_mp                        ← Madhya Pradesh tenant
│   ├── user_table
│   ├── scheme_master_table
│   ├── flow_reading_table
│   └── ...
│
└── tenant_<stateCode>               ← Created dynamically per state
```

Tenant schemas are provisioned automatically when a new tenant is created via the API. The provisioning function is idempotent — safe to call multiple times.

---

## 5. Kafka Event Architecture

### 5.1 Topics

| Topic | Producing Service | Consuming Services |
|-------|------------------|-------------------|
| `tenant-service-topic` | tenant-service | analytics-service |
| `user-service-topic` | user-service | analytics-service |
| `scheme-service-topic` | scheme-service | analytics-service |
| `telemetry-service-topic` | telemetry-service | analytics-service |
| `anomaly-service-topic` | anomaly-service | analytics-service |
| `common-topic` | All services | message-service, tenant-service, user-service |

### 5.2 Key Events

| Event Type | Producing Service | Description |
|------------|------------------|-------------|
| `METER_READING_SUBMITTED` | telemetry-service | Operator reading accepted and persisted |
| `NUDGE` | tenant-service | Operator missed today's reading — trigger reminder |
| `ESCALATION` | tenant-service | Persistent non-upload — alert assigned officer |
| `SEND_INVITE_EMAIL` | user-service | New staff invitation email |
| `SEND_WELCOME_MESSAGE` | user-service | WhatsApp welcome on account activation |
| `SEND_OTP` | user-service | WhatsApp OTP for staff login |
| `WHATSAPP_CONTACT_REGISTERED` | message-service | Glific contact ID obtained for operator |
| `TENANT_CREATED` | tenant-service | New state tenant provisioned |
| `USER_CREATED` | user-service | New user registered |
| `SCHEME_CREATED` | scheme-service | New scheme added |

### 5.3 Dead-Letter Topics

When message delivery fails after retries, events are routed to dead-letter topics for manual review and replay:

| Dead-Letter Topic | Failed Event Types | Common Cause |
|------------------|--------------------|-------------|
| `welcome-message-dlt` | `SEND_WELCOME_MESSAGE` | Missing WhatsApp connection ID |
| `account-email-dlt` | All `SEND_*_EMAIL` events | SendGrid API failure, blank email address |

---

## 6. Security Model

### 6.1 Authentication — Keycloak + JWT

All backend services act as OAuth2 resource servers. Keycloak issues JWTs containing custom claims:

| Claim | Example | Purpose |
|-------|---------|---------|
| `realm_access.roles` | `["STATE_ADMIN"]` | User's global platform roles |
| `tenant_state_code` | `"mp"` | Identifies which tenant schema to use |
| `user_type` | `"OPERATOR"` | User's functional role within the tenant |

Each service's `JwtAuthConverter` extracts these claims and maps them to Spring Security authorities (`ROLE_STATE_ADMIN`, `TENANT_MP`, `USER_TYPE_OPERATOR`).

### 6.2 PII Protection

Phone numbers and user names are classified as personally identifiable information and protected at rest:

| Field | Storage | Lookup |
|-------|---------|--------|
| Phone numbers | AES-256 (CBC) encrypted (`BYTEA`) | HMAC-SHA256 hash in `phone_number_hash` |
| User names/titles | AES-256 (CBC) encrypted (`BYTEA`) | HMAC-SHA256 hash in `title_hash` |

{% hint style="danger" %}
Phone numbers must **never** appear in `INFO`, `WARN`, or `ERROR` log statements. They may only be logged at `DEBUG` level.
{% endhint %}

### 6.3 Role-Based Access Control

| Role | Access Level |
|------|-------------|
| `SUPER_USER` | Global platform admin — creates tenants, manages super admins |
| `STATE_ADMIN` | Tenant-wide admin — manages staff, schemes, and configuration |
| `DISTRICT_OFFICER` | District-level dashboard and Level 2 escalation recipient |
| `SECTION_OFFICER` | Section-level dashboard and Level 1 escalation recipient |
| `OPERATOR` | Field worker — submits daily meter readings via WhatsApp |

---

## 7. Data Flow: Field Submission via WhatsApp

```
Pump Operator (WhatsApp)
        │  sends meter photo
        ▼
   Glific Platform
        │  POST /api/v1/observations (signed webhook)
        ▼
  telemetry-service
        │  calls FlowVision AI → extracted reading + confidence
        │  validates reading (monotonic, outlier, duplicate checks)
        │  persists to flow_reading_table
        │  publishes METER_READING_SUBMITTED → telemetry-service-topic
        ▼
  analytics-service
        │  consumes METER_READING_SUBMITTED
        │  upserts fact_meter_reading_table
        │  recalculates fact_water_quantity_table (LPCD)
        ▼
  Dashboard APIs (real-time updated)
```

---

## 8. Analytics Data Warehouse

The analytics-service maintains a star schema in a **dedicated PostgreSQL instance** to isolate BI queries from operational traffic:

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

Additional fact tables: `fact_water_quantity_table`, `fact_escalation_table`, `fact_scheme_performance_table`

{% hint style="info" %}
The analytics database runs on a separate PostgreSQL instance (default port 5433). This ensures that heavy reporting queries do not compete with operational read/write traffic.
{% endhint %}
