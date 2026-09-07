# Water Management Platform

A production-ready multi-module Spring Boot microservices platform built with **Java 21**, **Spring Cloud 2023.x**, **Eureka Service Discovery**, **Spring Kafka**, and **PostgreSQL**.

---

## Architecture Overview

```
                    ┌──────────────────────┐
                    │      API Gateway     │
                    │ Spring Cloud Gateway │
                    │        :8080         │
                    └──────────┬───────────┘
                               │
                    ┌──────────┴───────────┐
                    │   Service Discovery  │
                    │   (Eureka Server)    │
                    │        :8761         │
                    └──────────┬───────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                     │
┌─────────┴────────┐ ┌────────┴─────────┐ ┌────────┴─────────┐
│  Tenant Service  │ │   User Service   │ │ Anomaly Service  │
│     :8081        │ │     :8082        │ │     :8083        │
└──────────────────┘ └──────────────────┘ └──────────────────┘
          │                    │
┌─────────┴────────┐ ┌────────┴─────────┐ ┌──────────────────┐
│  Telemetry       │ │  Message Service │ │  Scheme Service  │
│  Service  :8989  │ │     :8085        │ │     :8086        │
└──────────────────┘ └──────────────────┘ └──────────────────┘
                               │
                    ┌──────────┴───────────┐
                    │  Analytics Service   │
                    │  (DW Consumer) :8087 │
                    └──────────────────────┘

All business services register with Eureka and communicate via Kafka topics.
Analytics service consumes events from all service topics and populates the DW schema.

Infrastructure: PostgreSQL (shared_db) + Apache Kafka
```

---

## Module Descriptions

| Module                       | Port   | Description                                      |
|------------------------------|--------|--------------------------------------------------|
| `api-gateway`                | 8080   | Spring Cloud Gateway for unified API ingress     |
| `service-discovery`          | 8761   | Netflix Eureka Server for service registration   |
| `tenant-service`             | 8081   | Manages tenant information                       |
| `user-service`               | 8082   | Manages user information                         |
| `anomaly-service`            | 8083   | Detects and manages anomalies (leaks, pressure)  |
| `telemetry-service`          | 8989   | Manages sensor and meter telemetry data          |
| `message-service`            | 8085   | Manages notifications (Email, WhatsApp, Webhook) |
| `scheme-service`             | 8086   | Manages water supply schemes                     |
| `analytics-service`          | 8087   | Consumes events; populates analytics DW (`dw` schema) |

---

## Scheme Status Integer Mapping

`work_status`
- `1` = `Ongoing`
- `2` = `Completed`
- `3` = `Not Started`
- `4` = `Handed Over`

`operating_status`
- `1` = `Operative`
- `2` = `Non-Operative`
- `3` = `Partially Operative`

---

## Prerequisites

- **Java 21** (Eclipse Temurin recommended)
- **Maven 3.9+**
- **Docker & Docker Compose** (for Kafka and PostgreSQL)

---

## How to Start Eureka (Service Discovery)

```bash
# From the project root
cd water-management-platform

# Build all modules
mvn clean package -DskipTests

# Start Eureka Server
java -jar service-discovery/target/*.jar
```

Eureka Dashboard: [http://localhost:8761](http://localhost:8761)

---

## Start Kafka with Docker

```bash
# Start Kafka using Docker (KRaft mode - no Zookeeper needed)
docker run -d \
  --name kafka \
  -p 9092:9092 \
  -e KAFKA_CFG_NODE_ID=0 \
  -e KAFKA_CFG_PROCESS_ROLES=controller,broker \
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@localhost:9093 \
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  bitnami/kafka:latest
```

Or use the classic Zookeeper-based approach:

```bash
# Start Zookeeper
docker run -d --name zookeeper -p 2181:2181 confluentinc/cp-zookeeper:latest \
  -e ZOOKEEPER_CLIENT_PORT=2181

# Start Kafka
docker run -d --name kafka -p 9092:9092 \
  --link zookeeper \
  -e KAFKA_BROKER_ID=1 \
  -e KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  confluentinc/cp-kafka:latest
```

---

<!-- ## Start PostgreSQL with Docker

```bash
docker run -d \
  --name postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=shared_db \
  -e POSTGRES_USER=YOUR_USERNAME \
  -e POSTGRES_PASSWORD=YOUR_PASSWORD \
  postgres:16-alpine
``` -->

> **Note:** Update `YOUR_USERNAME` and `YOUR_PASSWORD` in each service's `application.yml` with real credentials.

---

## Redis Setup (Local Docker and Cloud)

All services are configured to connect to Redis via environment variables:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}
```

### Option A: Local Redis with Docker

```bash
# Start Redis locally
docker run -d --name common-redis -p 6379:6379 redis:7-alpine

# Verify connectivity
docker exec common-redis redis-cli ping
# Expected: PONG
```

Start each service with Redis env vars (example):

```bash
cd tenant-service
REDIS_HOST=localhost REDIS_PORT=6379 REDIS_DATABASE=0 mvn spring-boot:run
```

### Option B: Cloud-hosted Redis

Set the Redis connection env vars in your deployment (VM/container/Kubernetes):

```bash
export REDIS_HOST=<your-redis-host>
export REDIS_PORT=<your-redis-port>
export REDIS_PASSWORD=<your-redis-password>
export REDIS_DATABASE=0
```

If your Redis provider requires TLS, add:

```bash
export SPRING_DATA_REDIS_SSL_ENABLED=true
```

If ACL username is required by your provider, also set:

```bash
export SPRING_DATA_REDIS_USERNAME=<your-redis-username>
```

### Key Namespace Convention

Each service writes to its own dedicated Redis namespace:

- `<service-name>:meta:service`
- `<service-name>:meta:lastHeartbeat`

Example tenant cache keys:

- `tenant-service:tenants:index`
- `tenant-service:tenants:<STATE_CODE>:profile` (example: `tenant-service:tenants:TR:profile`)

### Quick Verification

```bash
# List service namespace keys
docker exec common-redis redis-cli KEYS "*:meta:*"

# Verify tenant-service keys
docker exec common-redis redis-cli KEYS "tenant-service:*"
docker exec common-redis redis-cli HGETALL "tenant-service:tenants:TR:profile"
```

---

## Database Schema & Flyway Migrations

The platform uses a **two-layer schema** strategy managed by [Flyway](https://flywaydb.org/):

| Layer | Schema | Purpose |
|-------|--------|---------|
| **Common** | `common_schema` | Shared lookup tables (tenants, admin users, user types, channels, etc.) |
| **Per-tenant** | `tenant_<state_code>` (e.g. `tenant_mp`) | Tenant-specific operational tables (users, schemes, readings, etc.) |
| **Data Warehouse** | `analytics_schema` | Analytics star-schema (dimensions + facts) managed by `analytics-service` |

### Migration files

All SQL migration scripts live in a single directory:

```
backend/
├── database/
│   ├── V1__create_common_schema_tables.sql      # Creates common_schema and shared tables
│   └── V2__create_tenant_schema_function.sql    # Creates the create_tenant_schema() PL/pgSQL function
```

| Script | What it does |
|--------|-------------|
| **V1** | Creates `common_schema` and its tables: `tenant_admin_user_master_table`, `user_type_master_table`, `tenant_master_table`, `tenant_config_master_table`, `channel_master_table`, plus indexes. |
| **V2** | **Only defines** the `create_tenant_schema(schema_name)` PL/pgSQL function. It does **not** call it. The function is invoked at runtime by `tenant-service` when a new tenant is created. Once called, it provisions a full set of tenant-specific tables (user, scheme, location, department, readings, notifications, anomalies, etc.) inside the given schema. |

### How Flyway is wired into tenant-service

`tenant-service` is the service responsible for running Flyway migrations on startup. The setup uses three pieces:

**1. Maven resource mapping** (`tenant-service/pom.xml`):

```xml
<resources>
    <resource>
        <directory>src/main/resources</directory>
    </resource>
    <resource>
        <directory>../database</directory>
        <targetPath>db/migration</targetPath>
        <includes>
            <include>V*.sql</include>
        </includes>
    </resource>
</resources>
```

This copies `V*.sql` files from `backend/database/` into `classpath:db/migration` at build time, keeping a **single source of truth** for migration scripts.

**2. Flyway dependency** (`tenant-service/pom.xml`):

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

**3. Flyway configuration** (`tenant-service/src/main/resources/application.yml`):

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### What happens on startup

1. **Flyway runs first** (before JPA/Hibernate). It creates a `flyway_schema_history` table in the `public` schema to track applied migrations.
2. **V1** executes — creates `common_schema` and all shared tables.
3. **V2** executes — **registers** the `create_tenant_schema()` function. No tenant schema is created at this point.
4. **JPA** then initialises with `ddl-auto: none`, using `common_schema` as its default schema.

### Provisioning a new tenant schema (runtime, not migration)

Tenant schemas are **not** created during Flyway migration. They are created **at runtime** when a new tenant is onboarded via the tenant-service API. The flow is:

1. A request to create a new tenant hits `tenant-service`.
2. A row is inserted into `common_schema.tenant_master_table`.
3. `TenantSchemaRepository` calls the function registered by V2:

```sql
SELECT create_tenant_schema('tenant_mp');   -- e.g. Madhya Pradesh
```

4. The function creates the schema `tenant_mp` and provisions all tenant-specific tables and indexes inside it.

Each tenant gets its own isolated schema. The function is idempotent — calling it again for an existing tenant is a no-op.

### Adding a new migration

1. Create a new file in `backend/database/` following the Flyway naming convention: `V<version>__<description>.sql` (e.g. `V3__add_audit_log_table.sql`).
2. Rebuild `tenant-service` (`mvn compile` or `mvn package`). The file is automatically copied to the classpath.
3. On next startup, Flyway applies only the new, unapplied migration.

### Cross-schema FK strategy

- **Within a schema**: formal `FOREIGN KEY` constraints are used.
- **Across schemas** (e.g. tenant schema tables referencing `common_schema`): FKs are **loosely coupled** — stored as plain `INTEGER` columns with no formal constraint. Referential integrity is enforced at the application level. This avoids tight cross-schema coupling and simplifies tenant provisioning.

---

## How to Run Services

### Option 1: Run All from Maven

```bash
cd backend
mvn clean package -DskipTests

# Start each service in a separate terminal
java -jar api-gateway/target/*.jar
java -jar service-discovery/target/*.jar
java -jar tenant-service/target/*.jar
java -jar user-service/target/*.jar
java -jar anomaly-service/target/*.jar
java -jar telemetry-service/target/*.jar
java -jar message-service/target/*.jar
java -jar scheme-service/target/*.jar
java -jar analytics-service/target/*.jar
```

### Option 2: Run Individual Service via Maven

```bash
cd tenant-service
mvn spring-boot:run
```

### Option 3: Docker

```bash
# Build the image
cd tenant-service
docker build -t tenant-service .

# Run the container
docker run -p 8081:8081 tenant-service
```

---

## API Endpoints

### API Gateway (`:8080`)

Use the gateway as the single entrypoint. Prefix-based routes are configured as:

- `/tenant/**` -> `tenant-service`
- `/user/**` -> `user-service`
- `/anomaly/**` -> `anomaly-service`
- `/telemetry/**` -> `telemetry-service`
- `/message/**` -> `message-service`
- `/scheme/**` -> `scheme-service`
- `/analytics/**` -> `analytics-service`

For service discovery mode, enable Eureka and set route URIs to load-balanced names (example: `TENANT_SERVICE_URI=lb://tenant-service`).

Examples:

```bash
# Tenant service endpoint through gateway
curl http://localhost:8080/tenant/api/v1/tenants

# User service endpoint through gateway
curl http://localhost:8080/user/api/users

# Analytics endpoint through gateway
curl "http://localhost:8080/analytics/api/v1/analytics/tenants"
```

### Tenant Service (`:8081`)

| Method | Endpoint          | Description            |
|--------|-------------------|------------------------|
| GET    | `/api/tenants`    | Get all tenants        |
| POST   | `/api/publish`    | Publish Kafka message  |

**Sample Response** — `GET /api/tenants`:
```json
[
  { "id": 1, "name": "Tenant A" },
  { "id": 2, "name": "Tenant B" }
]
```

### User Service (`:8082`)

| Method | Endpoint          | Description            |
|--------|-------------------|------------------------|
| GET    | `/api/users`      | Get all users          |
| POST   | `/api/publish`    | Publish Kafka message  |

**Sample Response** — `GET /api/users`:
```json
[
  { "id": 1, "name": "John Doe" },
  { "id": 2, "name": "Jane Doe" }
]
```

### Anomaly Service (`:8083`)

| Method | Endpoint             | Description            |
|--------|----------------------|------------------------|
| GET    | `/api/v1/anomalies`  | Get all anomalies      |
| POST   | `/api/v1/publish`    | Publish Kafka message  |

**Sample Response** — `GET /api/v1/anomalies`:
```json
[
  { "id": 1, "type": "Leak Detection" },
  { "id": 2, "type": "Pressure Drop" }
]
```

### Telemetry Service (`:8989`)

| Method | Endpoint             | Description              |
|--------|----------------------|--------------------------|
| GET    | `/api/v1/telemetry`  | Get all telemetry data   |
| POST   | `/api/v1/publish`    | Publish Kafka message    |

**Sample Response** — `GET /api/v1/telemetry`:
```json
[
  { "id": 1, "meterId": "METER-001", "readingValue": 150.5 },
  { "id": 2, "meterId": "METER-002", "readingValue": 230.8 }
]
```

### Message Service (`:8085`)

| Method | Endpoint                         | Description                        |
|--------|----------------------------------|------------------------------------|
| GET    | `/api/v1/message/notifications`  | Get all notifications              |
| POST   | `/api/v1/message/notifications`  | Send notification via channel      |
| POST   | `/api/v1/message/events`         | Dispatch a Kafka event             |

### Scheme Service (`:8086`)

| Method | Endpoint          | Description              |
|--------|-------------------|--------------------------|
| GET    | `/api/schemes`    | Get all schemes          |
| POST   | `/api/publish`    | Publish Kafka message    |

**Sample Response** — `GET /api/schemes`:
```json
[
  { "id": 1, "schemeName": "Rural Water Supply", "schemeCode": "RWS-001" },
  { "id": 2, "schemeName": "Urban Pipeline Network", "schemeCode": "UPN-002" }
]
```

### Analytics Service (`:8087`)

| Method | Endpoint                                  | Description                              |
|--------|-------------------------------------------|------------------------------------------|
| GET    | `/api/v1/analytics/tenants`               | List all tenants in the DW               |
| GET    | `/api/v1/analytics/schemes`               | List schemes (optional `?tenantId=`)     |
| GET    | `/api/v1/analytics/meter-readings`        | Query meter readings (filters: tenantId, schemeId, startDate, endDate) |
| GET    | `/api/v1/analytics/water-quantity`        | Query water quantity data                |
| GET    | `/api/v1/analytics/escalations`           | Query escalations (filters: tenantId, schemeId, resolutionStatus) |
| GET    | `/api/v1/analytics/scheme-performance`    | Query scheme performance data            |
| POST   | `/api/v1/analytics/date-dimension/populate` | Pre-populate dim_date for a date range |

### Kafka Publishing (All Services)

```bash
curl -X POST http://localhost:8080/tenant/api/publish \
  -H "Content-Type: text/plain" \
  -d "Hello from tenant-service"
```

Each service publishes to its own topic (`<service-name>-topic`) and listens on `common-topic`.

### Actuator Health (All Services)

```
GET http://localhost:<port>/actuator/health
```

---

## Kafka Topics

| Service                    | Produces To                          | Consumes From    |
|----------------------------|--------------------------------------|------------------|
| `tenant-service`           | `tenant-service-topic`               | `common-topic`   |
| `user-service`             | `user-service-topic`                 | `common-topic`   |
| `anomaly-service`          | `anomaly-service-topic`              | `common-topic`   |
| `telemetry-service`        | `telemetry-service-topic`            | `common-topic`   |
| `message-service`          | `message-service-topic`              | `common-topic`   |
| `scheme-service`           | `scheme-service-topic`               | `common-topic`   |
| `analytics-service`        | `analytics-service-topic`            | `tenant-service-topic`, `user-service-topic`, `scheme-service-topic`, `telemetry-service-topic`, `anomaly-service-topic`, `common-topic` |

---

## Future Enhancements

- **Database Separation**: Currently all services point to a single `shared_db`. In production, each service should have its own dedicated database to follow the **Database-per-Service** pattern.
- **Config Server**: Externalize configuration using Spring Cloud Config Server.
- **Circuit Breaker**: Add Resilience4j for fault tolerance and circuit-breaking patterns.
- **Distributed Tracing**: Integrate Micrometer Tracing with Zipkin/Jaeger.
- **Security**: Add Spring Security with OAuth2/JWT for API protection.
- **Docker Compose**: Create a `docker-compose.yml` for full-stack orchestration.

---

## Project Structure

```
water-management-platform/
├── pom.xml                          # Parent POM with BOM management
├── README.md
│
├── database/                        # Flyway SQL migrations (single source of truth)
│   ├── V1__create_common_schema_tables.sql
│   └── V2__create_tenant_schema_function.sql
│
├── api-gateway/                     # Port 8080 - unified API ingress
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/org/arghyam/jalsoochak/apigateway/ApiGatewayApplication.java
│       └── resources/application.yml
│
├── service-discovery/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/example/discovery/
│       │   └── ServiceDiscoveryApplication.java
│       └── resources/application.yml
│
├── tenant-service/                  # Port 8081 — runs Flyway migrations on startup
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/example/tenant/
│       │   ├── TenantServiceApplication.java
│       │   ├── controller/ApiController.java
│       │   ├── service/BusinessService.java
│       │   ├── repository/SampleRepository.java
│       │   ├── entity/SampleEntity.java
│       │   ├── dto/SampleDTO.java
│       │   ├── kafka/
│       │   │   ├── KafkaConfig.java
│       │   │   ├── KafkaProducer.java
│       │   │   └── KafkaConsumer.java
│       │   └── config/DataSourceConfig.java
│       └── resources/application.yml
│
├── user-service/                    # Port 8082
├── anomaly-service/                 # Port 8083
├── telemetry-service/               # Port 8989
├── message-service/                 # Port 8085
├── scheme-service/                  # Port 8086
│
└── analytics-service/               # Port 8087 — DW event consumer
    ├── pom.xml
    ├── Dockerfile
    └── src/main/
        ├── java/com/example/analytics/
        │   ├── AnalyticsServiceApplication.java
        │   ├── controller/AnalyticsController.java
        │   ├── service/{DimensionService,FactService,DateDimensionService}.java
        │   ├── service/serviceImpl/
        │   ├── entity/{DimDate,DimTenant,DimUser,...,FactMeterReading,...}.java
        │   ├── repository/
        │   ├── dto/event/{TenantEvent,UserEvent,SchemeEvent,...}.java
        │   ├── kafka/
        │   │   ├── KafkaConfig.java
        │   │   ├── KafkaProducer.java
        │   │   └── AnalyticsKafkaConsumer.java
        │   ├── config/OpenApiConfig.java
        │   └── exception/GlobalExceptionHandler.java
        └── resources/
            ├── application.yml
            └── db/migration/V1__create_dw_schema.sql
```
