# Scheme Service

**Port:** `8287` | **Module:** `backend/scheme-service`

## Overview

The Scheme Service manages **water supply schemes** — the physical infrastructure units (pumping stations, pipelines, storage tanks) that deliver water to villages under the Jal Jeevan Mission.

Each scheme is a uniquely identifiable unit tracked by:

- Physical attributes (FHTC count, planned FHTC, household count, GPS coordinates)
- Operational status (work completion status, functional status)
- LGD location mappings (which villages, blocks, and districts the scheme serves)
- Department mappings (which administrative units oversee the scheme)

The service supports bulk CSV uploads for initial data loading and provides paginated, filterable list APIs for the dashboard.

---

## Architecture

```
scheme-service
├── controller/
│   ├── SchemeController             – List, filter, and count schemes
│   └── SchemeUploadController       – Bulk CSV upload for schemes and mappings
├── service/
│   ├── SchemeService                – Scheme CRUD and paginated queries
│   ├── SchemeMappingService         – LGD and department mapping management
│   └── BulkUploadService            – CSV parsing with idempotent upsert by state_scheme_id
├── repository/
│   ├── SchemeRepository             – JPA for scheme_master_table
│   ├── SchemeLgdMappingRepository   – JPA for scheme_lgd_mapping_table
│   └── SchemeDeptMappingRepository  – JPA for scheme_department_mapping_table
├── entity/
│   ├── Scheme                       – scheme_master_table entity
│   ├── SchemeLgdMapping             – scheme_lgd_mapping_table entity
│   └── SchemeDeptMapping            – scheme_department_mapping_table entity
├── kafka/
│   ├── KafkaProducer                – Publishes to scheme-service-topic
│   └── KafkaConsumer                – Consumes from common-topic
└── config/
    ├── SecurityConfig               – JWT resource server
    └── JwtAuthConverter             – Role and claim extraction
```

---

## REST API

### Scheme Queries

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/scheme/schemes` | `STATE_ADMIN`, `SECTION_OFFICER`, `DISTRICT_OFFICER` | Paginated scheme list with filters |
| `GET` | `/api/v1/scheme/schemes/counts` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Scheme counts grouped by status |
| `GET` | `/api/v1/scheme/schemes/{schemeId}` | `STATE_ADMIN`, `SECTION_OFFICER` | Single scheme details |
| `GET` | `/api/v1/scheme/schemes/mappings` | `STATE_ADMIN` | Scheme-to-location mapping list |

**Query Parameters (`GET /api/v1/scheme/schemes`):**

| Parameter | Type | Description |
|-----------|------|-------------|
| `workStatus` | Integer | Filter by work completion status |
| `operatingStatus` | Integer | Filter by operational status |
| `districtLgdCode` | String | Filter by LGD district code |
| `blockLgdCode` | String | Filter by LGD block code |
| `search` | String | Partial match on scheme name |
| `page` | Integer | Page number (0-indexed) |
| `size` | Integer | Page size (default: 20) |
| `sortBy` | String | Sort field (default: `schemeName`) |
| `sortDir` | String | `asc` or `desc` |

**Count Response Example:**
```json
{
  "total": 450,
  "byWorkStatus": {
    "COMPLETED": 380,
    "IN_PROGRESS": 50,
    "PLANNED": 20
  },
  "byOperatingStatus": {
    "FUNCTIONAL": 340,
    "PARTIALLY_FUNCTIONAL": 60,
    "NON_FUNCTIONAL": 50
  }
}
```

### Bulk Upload

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/scheme/schemes/upload` | `STATE_ADMIN` | Bulk upload schemes from CSV |
| `POST` | `/api/v1/scheme/schemes/mappings/upload` | `STATE_ADMIN` | Bulk upload scheme-to-location mappings from CSV |

**Scheme CSV Format:**
```
state_scheme_id,centre_scheme_id,scheme_name,fhtc_count,planned_fhtc,house_hold_count,latitude,longitude,channel,work_status,operating_status
JJM-STATE-001,JJM-CENTRE-001,Village Water Supply Scheme,250,280,310,24.1234,81.5678,1,2,1
```

**Mapping CSV Format:**
```
state_scheme_id,lgd_district_code,lgd_block_code,lgd_panchayat_code,lgd_village_code
JJM-STATE-001,483,4831,483101,48310001
```

{% hint style="info" %}
Bulk uploads are **idempotent**. The `state_scheme_id` is used as the unique key — re-uploading an existing scheme updates it rather than creating a duplicate. This makes it safe to re-run uploads after corrections.
{% endhint %}

---

## Data Models

### Scheme (`scheme_master_table` in tenant schema)

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | UUID | Public identifier |
| `state_scheme_id` | VARCHAR | State-assigned unique scheme code |
| `centre_scheme_id` | VARCHAR | Central government JJM scheme identifier |
| `scheme_name` | VARCHAR | Human-readable scheme name |
| `fhtc_count` | INTEGER | Functional Household Tap Connections |
| `planned_fhtc` | INTEGER | Target FHTC count |
| `house_hold_count` | INTEGER | Total households in the scheme's service area |
| `latitude` | DECIMAL | GPS latitude of the scheme |
| `longitude` | DECIMAL | GPS longitude of the scheme |
| `work_status` | INTEGER | Construction/work completion status (see below) |
| `operating_status` | INTEGER | Functional/operational status (see below) |

### Work Status Codes

| Code | Status | Description |
|------|--------|-------------|
| `0` | `PLANNED` | Approved; construction not yet started |
| `1` | `IN_PROGRESS` | Under construction |
| `2` | `COMPLETED` | Construction complete |
| `3` | `COMMISSIONED` | Tested and handed over for operations |

### Operating Status Codes

| Code | Status | Description |
|------|--------|-------------|
| `0` | `NOT_STARTED` | No operations commenced yet |
| `1` | `FUNCTIONAL` | Operating normally |
| `2` | `PARTIALLY_FUNCTIONAL` | Intermittent operations |
| `3` | `NON_FUNCTIONAL` | Not currently operational |
| `4` | `UNDER_MAINTENANCE` | Temporarily shut down for maintenance |

### Scheme LGD Mapping (`scheme_lgd_mapping_table`)

Links a scheme to its LGD administrative locations:

| Column | Description |
|--------|-------------|
| `scheme_id` | Reference to `scheme_master_table` |
| `lgd_location_id` | Reference to `lgd_location_master_table` |
| `mapping_level` | `1`=District, `2`=Block, `3`=Panchayat, `4`=Village |

### Scheme Department Mapping (`scheme_department_mapping_table`)

Links a scheme to its departmental administrative hierarchy:

| Column | Description |
|--------|-------------|
| `scheme_id` | Reference to `scheme_master_table` |
| `dept_location_id` | Reference to `department_location_master_table` |
| `mapping_level` | `1`=Zone, `2`=Circle, `3`=Division, `4`=Sub-Division |

---

## Kafka Events

### Published

**Topic: `scheme-service-topic`**

| Event Type | Trigger | Key Payload Fields |
|------------|---------|-------------------|
| `SCHEME_CREATED` | New scheme persisted | `eventType`, `schemeId`, `stateSchemeId`, `tenantId`, `fhtcCount`, `operatingStatus`, `workStatus` |
| `SCHEME_UPDATED` | Scheme attributes changed | `eventType`, `schemeId`, `tenantId`, `operatingStatus`, `workStatus` |
| `SCHEME_BULK_UPLOADED` | Bulk upload completed | `eventType`, `tenantId`, `uploadedCount`, `updatedCount`, `errorCount` |

### Consumed

**Topic: `common-topic`**

Events are logged; no active processing is performed by this service.

---

## Configuration

```yaml
server:
  port: 8287

spring:
  application:
    name: scheme-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}

kafka:
  bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}

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
| Spring Data JPA | Entity persistence |
| Spring Kafka | Event publishing |
| Spring Security + OAuth2 Resource Server | JWT authentication |
| OpenCSV | CSV file parsing |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |
