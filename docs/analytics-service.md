# Analytics Service

**Port:** `8087` | **Module:** `backend/analytics-service`

## Overview

The Analytics Service is the **business intelligence layer** of JalSoochak V2. It:

- Consumes events from all other services via Kafka and populates a dedicated analytics data warehouse (star schema)
- Exposes read-only query APIs for dashboards at scheme, state, and national levels
- Keeps dimension tables (tenants, users, schemes, locations) synchronised from operational events
- Populates fact tables (meter readings, water quantity, escalations, scheme performance)
- Runs a nightly scheduler to mark schemes as inactive when no readings have been submitted for 30 or more days

The analytics database is a **separate PostgreSQL instance** to keep BI queries isolated from operational read/write traffic.

---

## Architecture

```
analytics-service
├── controller/
│   ├── AnalyticsTenantSchemeController           – Scheme and tenant-level analytics
│   ├── NationalDashboardController               – Cross-tenant aggregate queries
│   ├── AnalyticsRegularityAndReadingController   – Regularity and compliance metrics
│   ├── AnalyticsWaterQuantityOutageSubmissionController – Water quantity and outage data
│   ├── AnalyticsSchemeReportingController        – Scheme-level reporting
│   ├── AnalyticsWaterSupplyNationalController    – National water supply analytics
│   ├── AnalyticsStatusController                 – Scheme status analytics
│   └── DateDimensionController                   – Pre-populate date dimension table
├── service/
│   ├── DimensionSyncService                      – Upsert dimension tables from Kafka events
│   ├── FactPopulationService                     – Populate fact tables from reading events
│   ├── AnalyticsQueryService                     – Execute BI queries on the star schema
│   ├── NationalDashboardService                  – Cross-tenant aggregation logic
│   └── SchemeStatusSchedulerService              – Nightly: mark schemes inactive if idle ≥ 30 days
├── repository/                                   – JPA repositories for all dim_* and fact_* tables
├── entity/                                       – JPA entities for dim_* and fact_* tables
├── kafka/
│   └── AnalyticsKafkaConsumer                    – Multi-topic consumer; routes to sync services
└── config/
    ├── SecurityConfig                            – JWT resource server
    └── DataSourceConfig                          – Analytics DB datasource
```

---

## REST API

### Scheme and Tenant Analytics

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/tenants` | `SUPER_USER` | List all tenants in the data warehouse |
| `GET` | `/api/v1/analytics/schemes` | `SUPER_USER`, `STATE_ADMIN` | List schemes in the data warehouse |
| `GET` | `/api/v1/analytics/scheme-performance` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Scheme performance and compliance metrics |
| `GET` | `/api/v1/analytics/scheme-regularity/periodic` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Periodical compliance rates |

**Scheme Performance Response:**
```json
{
  "schemeId": 101,
  "schemeName": "Village Water Supply Scheme",
  "totalDays": 30,
  "daysWithReading": 28,
  "complianceRate": 93.3,
  "avgDailyQuantity": 12500,
  "lastReadingDate": "2024-01-15"
}
```

### Meter Reading and Water Quantity

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/meter-readings` | `STATE_ADMIN`, `SECTION_OFFICER` | Query meter readings from the data warehouse |
| `GET` | `/api/v1/analytics/water-quantity` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Aggregated water quantity and LPCD metrics |

**Common Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `tenantId` | Long | Filter by tenant |
| `schemeId` | Long | Filter by scheme |
| `fromDate` | LocalDate | Start date |
| `toDate` | LocalDate | End date |
| `groupBy` | String | Aggregation period: `day`, `week`, or `month` |
| `page` | Integer | Page number |
| `size` | Integer | Page size |

### Escalation Analytics

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/escalations` | `SUPER_USER`, `STATE_ADMIN`, `DISTRICT_OFFICER` | Escalation history and resolution tracking |

**Escalation Response:**
```json
{
  "escalationId": "<uuid>",
  "tenantId": 1,
  "schemeId": 101,
  "escalationLevel": "L1",
  "officerName": "Ramesh Kumar",
  "operatorCount": 3,
  "triggeredAt": "2024-01-15T09:00:00Z",
  "resolvedAt": null
}
```

### National Dashboard

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/national/dashboard` | `SUPER_USER` | Cross-tenant aggregate metrics for the national view |

**National Dashboard Response:**
```json
{
  "totalTenants": 12,
  "totalSchemes": 4500,
  "activeSchemesToday": 3800,
  "totalReadingsToday": 3200,
  "nationalComplianceRate": 84.2,
  "byState": [
    { "stateCode": "mp", "schemes": 450, "complianceRate": 91.0 },
    { "stateCode": "up", "schemes": 1200, "complianceRate": 78.5 }
  ]
}
```

### Utility

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/analytics/date-dimension/populate` | `SUPER_USER` | Pre-populate `dim_date_table` for a given date range |

---

## Kafka Consumers

`AnalyticsKafkaConsumer` listens to **all service topics** and routes events to the appropriate dimension or fact service:

| Source Topic | Event Type | Action |
|-------------|-----------|--------|
| `tenant-service-topic` | `TENANT_CREATED` | Upsert `dim_tenant_table` |
| `tenant-service-topic` | `TENANT_UPDATED` | Update `dim_tenant_table` |
| `user-service-topic` | `USER_CREATED` | Upsert `dim_user_table` |
| `user-service-topic` | `USER_UPDATED` | Update `dim_user_table` |
| `scheme-service-topic` | `SCHEME_CREATED` | Upsert `dim_scheme_table` |
| `scheme-service-topic` | `SCHEME_UPDATED` | Update `dim_scheme_table` |
| `telemetry-service-topic` | `METER_READING_SUBMITTED` | Insert into `fact_meter_reading_table` and `fact_water_quantity_table` |
| `common-topic` | `ESCALATION` | Insert into `fact_escalation_table` |
| `anomaly-service-topic` | `ANOMALY_DETECTED` | Insert into `anomaly_table` |

{% hint style="info" %}
All upserts use composite unique keys (e.g. `tenant_id + external_id`) to handle Kafka message re-delivery safely. The consumer is **idempotent** by design.
{% endhint %}

---

## Data Warehouse Schema

### Dimension Tables

#### `dim_date_table`

Pre-populated date dimension for efficient time-based filtering:

| Column | Description |
|--------|-------------|
| `date_key` | Integer key in `YYYYMMDD` format |
| `full_date` | Calendar date |
| `day_of_week`, `day_name` | Weekday information |
| `week_of_year`, `month_number`, `month_name`, `quarter`, `year` | Calendar breakdowns |
| `is_weekend` | Boolean flag |

#### `dim_tenant_table`

| Column | Description |
|--------|-------------|
| `tenant_id` | Operational DB tenant ID (unique) |
| `state_code` | Two-letter state code |
| `title` | Tenant display name |
| `status` | Tenant status code |

#### `dim_scheme_table`

| Column | Description |
|--------|-------------|
| `scheme_id` | Operational DB scheme ID (unique) |
| `tenant_id` | Owning tenant |
| `state_scheme_id` | State-assigned scheme code |
| `scheme_name` | Scheme display name |
| `fhtc_count` | Functional Household Tap Connections |
| `latitude`, `longitude` | GPS location |
| `work_status`, `operating_status` | Status codes |
| `is_active` | `false` if no readings submitted in ≥ 30 days |

#### `dim_operator_attendance`

Daily tracking of whether each operator submitted a reading:

| Column | Description |
|--------|-------------|
| `user_id` | Operator |
| `scheme_id` | Assigned scheme |
| `reading_date` | Calendar date |
| `has_reading` | Whether a reading was submitted |
| `days_missed` | Running count of consecutive days without a reading |

### Fact Tables

#### `fact_meter_reading_table`

One row per accepted meter reading:

| Column | Description |
|--------|-------------|
| `date_key` | FK → `dim_date_table` |
| `scheme_key` | FK → `dim_scheme_table` |
| `user_key` | FK → `dim_user_table` |
| `tenant_key` | FK → `dim_tenant_table` |
| `extracted_reading` | AI-extracted value |
| `confirmed_reading` | Final operator-confirmed value |
| `confidence` | AI confidence score |
| `quantity` | Litres since last reading |
| `channel` | Submission channel |

#### `fact_water_quantity_table`

Daily LPCD calculations per scheme:

| Column | Description |
|--------|-------------|
| `date_key` | FK → `dim_date_table` |
| `scheme_key` | FK → `dim_scheme_table` |
| `daily_quantity` | Total litres supplied that day |
| `fhtc_count` | FHTC count at time of reading |
| `lpcd` | Litres per capita per day |
| `water_norm` | Expected LPCD from tenant config |
| `norm_achieved` | Whether the LPCD target was met |

#### `fact_escalation_table`

One row per escalation event:

| Column | Description |
|--------|-------------|
| `tenant_key` | FK → `dim_tenant_table` |
| `scheme_key` | FK → `dim_scheme_table` |
| `user_key` | Receiving officer |
| `escalation_level` | `L1` (Section Officer) or `L2` (District Officer) |
| `operator_count` | Number of operators in this escalation |
| `days_missed` | Maximum days missed among operators |
| `triggered_at` | When the escalation was dispatched |
| `resolved_at` | When the situation was resolved (null if ongoing) |

#### `fact_scheme_performance_table`

Aggregated compliance metrics over a period:

| Column | Description |
|--------|-------------|
| `scheme_key` | FK → `dim_scheme_table` |
| `period_start`, `period_end` | The analysis period |
| `total_days` | Days in the period |
| `days_with_reading` | Days where at least one reading was submitted |
| `compliance_rate` | `(days_with_reading ÷ total_days) × 100` |
| `avg_daily_quantity` | Average litres supplied per day |

---

## Key Metrics

| Metric | Formula | Purpose |
|--------|---------|---------|
| **Compliance Rate** | `(days_with_reading ÷ total_active_days) × 100` | Primary KPI for scheme and operator performance |
| **LPCD** | `daily_quantity ÷ FHTC count` | Measures adequacy of water supply per household tap |
| **Norm Achievement** | `LPCD ≥ water_norm` from tenant config | Whether the scheme meets the state's daily supply target |

---

## Scheduled Jobs

### SchemeStatusSchedulerService

**Default schedule:** 7 PM IST daily (configurable via `ANALYTICS_SCHEDULER_CRON`)

1. Queries `fact_meter_reading_table` for schemes with no reading in the last 30 days
2. Sets `is_active = false` in `dim_scheme_table` for those schemes
3. Inactive schemes are excluded from compliance rate denominators in dashboard queries

---

## Configuration

```yaml
server:
  port: 8087

spring:
  application:
    name: analytics-service
  datasource:
    # Analytics uses a SEPARATE PostgreSQL instance from the operational DB
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  jpa:
    properties:
      hibernate.default_schema: analytics_schema

analytics:
  scheduler:
    cron: ${ANALYTICS_SCHEDULER_CRON:0 0 19 * * *}
    zone: ${ANALYTICS_SCHEDULER_ZONE:Asia/Kolkata}

kafka:
  bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}
  # This service consumes from all service topics
  topics:
    consume:
      - tenant-service-topic
      - user-service-topic
      - scheme-service-topic
      - telemetry-service-topic
      - anomaly-service-topic
      - common-topic

eureka:
  client:
    enabled: ${EUREKA_ENABLED:true}
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
```

{% hint style="warning" %}
The analytics service connects to a **different PostgreSQL instance** than all other services. Ensure the correct `SPRING_DATASOURCE_URL` (pointing to the analytics DB on port 5433) is set in the analytics-service deployment environment.
{% endhint %}

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| Spring Web | REST controllers |
| Spring Data JPA | Star schema persistence |
| Spring Kafka | Multi-topic event consumption |
| Spring Security + OAuth2 Resource Server | JWT authentication |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |
