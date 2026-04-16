# Tenant Service

**Port:** `8081` | **Module:** `backend/tenant-service`

## Overview

The Tenant Service is the **control plane** of JalSoochak V2. It is responsible for:

- Onboarding new state tenants and provisioning their PostgreSQL schemas
- Managing per-tenant configuration (languages, water norms, escalation thresholds, cron schedules)
- Uploading LGD (Local Government Directory) and departmental location hierarchies
- Running daily scheduled jobs that identify operators with missed readings and trigger nudge/escalation notifications
- Executing all Flyway database migrations on startup — it is the sole migration runner for the operational database

---

## Architecture

```
tenant-service
├── controller/
│   ├── TenantController              – Tenant CRUD and configuration endpoints
│   └── LocationHierarchyController   – LGD and department hierarchy upload/retrieval
├── service/
│   ├── TenantManagementService       – Tenant provisioning and schema creation
│   ├── TenantConfigService           – Configuration key-value CRUD
│   ├── NudgeSchedulerService         – Morning cron: operators with no reading today
│   ├── EscalationSchedulerService    – Morning cron: operators with missed reading streaks
│   └── LocationHierarchyService      – CSV parsing and location hierarchy persistence
├── repository/
│   ├── TenantRepository              – JPA for tenant_master_table
│   ├── NudgeRepository               – JdbcTemplate; schema-scoped nudge queries
│   └── EscalationRepository          – JdbcTemplate; schema-scoped escalation queries
├── entity/
│   ├── Tenant                        – tenant_master_table entity
│   └── TenantConfig                  – tenant_config_master_table entity
├── kafka/
│   ├── KafkaProducer                 – Publishes to tenant-service-topic and common-topic
│   └── KafkaConsumer                 – Consumes from common-topic
└── config/
    ├── SecurityConfig                – JWT resource server and role-based access rules
    ├── JwtAuthConverter              – Extracts ROLE_*, TENANT_*, USER_TYPE_* from JWT
    └── FlywayConfig                  – Flyway migration runner configuration
```

---

## REST API

### Tenant Management

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/tenants` | `SUPER_USER` | Create a new tenant and provision its PostgreSQL schema |
| `GET` | `/api/v1/tenants` | `SUPER_USER` | List all tenants |
| `GET` | `/api/v1/tenants/{tenantId}` | `SUPER_USER`, `STATE_ADMIN` | Get tenant details |
| `PUT` | `/api/v1/tenants/{tenantId}/status` | `SUPER_USER` | Update tenant status |
| `PUT` | `/api/v1/tenants/{tenantId}/logo` | `STATE_ADMIN` | Upload tenant logo to object storage |

### Tenant Status Lifecycle

| Code | Status | Description |
|------|--------|-------------|
| `0` | `INACTIVE` | Registered but not yet configured |
| `1` | `ONBOARDED` | PostgreSQL schema provisioned |
| `2` | `CONFIGURED` | Configuration keys set |
| `3` | `ACTIVE` | Fully operational |
| `4` | `SUSPENDED` | Temporarily disabled |
| `5` | `DEGRADED` | Partial service failure |
| `6` | `ARCHIVED` | Retired tenant |

### Tenant Configuration

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/tenants/{tenantId}/config` | `STATE_ADMIN` | Retrieve all configuration key-value pairs |
| `PUT` | `/api/v1/tenants/{tenantId}/config` | `STATE_ADMIN` | Create or update configuration keys |

**Standard Configuration Keys:**

| Key Pattern | Purpose |
|-------------|---------|
| `language_<id>` | Language name for a given language ID (e.g. `language_1` = `Hindi`) |
| `nudge_message_<langKey>` | Localised nudge WhatsApp message template |
| `escalation_message_<langKey>` | Localised escalation WhatsApp message template |
| `water_norm` | Expected litres per capita per day (LPCD) |
| `water_supply_threshold` | Minimum acceptable daily supply percentage |
| `nudge_cron` | Cron expression for the nudge scheduler |
| `escalation_cron` | Cron expression for the escalation scheduler |
| `escalation_l1_threshold` | Days missed before a Level 1 escalation fires (default: 3) |
| `escalation_l2_threshold` | Days missed before a Level 2 escalation fires (default: 7) |

### Location Hierarchy

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/tenants/{tenantId}/location-hierarchy/{type}` | `STATE_ADMIN` | Get hierarchy (`lgd` or `department`) |
| `PUT` | `/api/v1/tenants/{tenantId}/location-hierarchy/{type}` | `STATE_ADMIN` | Upload hierarchy data via CSV |

**LGD Hierarchy Levels:** State → District → Block → Panchayat → Village

**Department Hierarchy Levels:** State → Zone → Circle → Division → Sub-Division

---

## Kafka Events

### Published

**Topic: `tenant-service-topic`**

| Event Type | Trigger | Key Payload Fields |
|------------|---------|-------------------|
| `TENANT_CREATED` | New tenant provisioned | `eventType`, `tenantId`, `stateCode`, `title`, `status` |
| `TENANT_UPDATED` | Config or status changed | `eventType`, `tenantId`, `stateCode`, `title`, `status` |
| `TENANT_DEACTIVATED` | Status set to SUSPENDED or ARCHIVED | `eventType`, `tenantId`, `stateCode` |

**Topic: `common-topic`**

| Event Type | Trigger | Key Payload Fields |
|------------|---------|-------------------|
| `NUDGE` | Morning cron — operator has no reading today | `recipientPhone`, `operatorName`, `schemeId`, `tenantId`, `languageId`, `userId`, `whatsappConnectionId` |
| `ESCALATION` | Morning cron — missed reading streak detected | `escalationLevel` (L1/L2), `officerPhone`, `officerName`, `operators[]`, `correlationId`, `tenantId` |

### Consumed

**Topic: `common-topic`**

| Event Type | Action |
|------------|--------|
| `WHATSAPP_CONTACT_REGISTERED` | Updates the operator's WhatsApp registration status in the tenant schema |

---

## Scheduled Jobs

### NudgeSchedulerService

**Default schedule:** 10:30 AM IST (configurable via `NUDGE_CRON` or tenant config)

1. Fetch all active tenants from `common_schema.tenant_master_table`
2. For each tenant, query operators in `user_scheme_mapping_table` who have no entry in `flow_reading_table` for today
3. For each such operator, publish a `NUDGE` event to `common-topic`

### EscalationSchedulerService

**Default schedule:** 10:32 AM IST (configurable via `ESCALATION_CRON` or tenant config)

1. Fetch all active tenants
2. Query each tenant's operators for missed-reading streaks
3. **Level 1** — Operator missed ≥ `escalation_l1_threshold` consecutive days → alert the assigned Section Officer
4. **Level 2** — Operator missed ≥ `escalation_l2_threshold` days, or has never uploaded → alert the District Officer
5. Publish `ESCALATION` event for each violation

{% hint style="info" %}
All SQL queries in `NudgeRepository` and `EscalationRepository` use schema names that are validated against an allowlist before interpolation, preventing SQL injection through tenant codes.
{% endhint %}

---

## Database

### Tables (common_schema)

| Table | Purpose |
|-------|---------|
| `tenant_master_table` | Tenant registry (state code, title, status) |
| `tenant_config_master_table` | Per-tenant key-value configuration |
| `tenant_admin_user_master_table` | State admins and super admins |
| `user_type_master_table` | Role definitions |

### Tables (tenant_\<stateCode\>)

| Table | Purpose |
|-------|---------|
| `user_table` | Operators, section officers, district officers |
| `user_scheme_mapping_table` | Operator ↔ scheme assignments |
| `flow_reading_table` | Meter readings (queried for nudge/escalation checks) |
| `lgd_location_master_table` | LGD hierarchy nodes |
| `department_location_master_table` | Department hierarchy nodes |

### Flyway Migrations

`tenant-service` is the sole owner of all database migrations. Migration files live in `backend/database/`:

| Migration | Description |
|-----------|-------------|
| `V1` | Create `common_schema` with all shared tables |
| `V2` | Register the `create_tenant_schema()` PL/pgSQL function |
| `V3–V10` | Seed user types and language master data |
| `V11–V20` | Schema enhancements: indexes and constraints |
| `V21–V28` | PII hash columns; token table backfill |

{% hint style="warning" %}
Never run Flyway migrations manually or from another service. All migrations must go through `tenant-service` to maintain version consistency.
{% endhint %}

---

## Configuration

```yaml
server:
  port: 8081

spring:
  application:
    name: tenant-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  flyway:
    enabled: true
    locations: classpath:db/migration,filesystem:../database
    baseline-on-migrate: true

kafka:
  bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}

scheduler:
  nudge:
    cron: ${NUDGE_CRON:0 30 10 * * *}
  escalation:
    cron: ${ESCALATION_CRON:0 32 10 * * *}

notifications:
  dry-run: ${NOTIFICATIONS_DRY_RUN:false}

eureka:
  client:
    enabled: ${EUREKA_ENABLED:true}
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
```

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| Spring Web | REST controllers |
| Spring Data JPA | Tenant and config entity persistence |
| Spring JDBC | `JdbcTemplate` for schema-scoped nudge/escalation queries |
| Spring Kafka | Event publishing and consumption |
| Spring Security + OAuth2 Resource Server | JWT authentication and authorisation |
| Flyway | Database schema migrations |
| AWS SDK v2 S3 | Tenant logo upload to MinIO / S3 |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |
