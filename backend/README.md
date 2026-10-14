# Water Management Platform

A production-ready multi-module Spring Boot microservices platform built with **Java 21**, **Spring Cloud 2023.x**, **Spring Kafka**, and **PostgreSQL**, with optional **Eureka** service discovery.

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
                    │   :8762, optional    │
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
│  Service  :8084  │ │     :8085        │ │     :8086        │
└──────────────────┘ └──────────────────┘ └──────────────────┘
                               │
                    ┌──────────┴───────────┐
                    │  Analytics Service   │
                    │  (DW Consumer) :8087 │
                    └──────────────────────┘

Services never call each other over HTTP: they share the database and talk through Kafka.
Eureka registration is optional (`EUREKA_ENABLED`); the gateway routes to configured service URIs.
Analytics service consumes events from all service topics and populates `analytics_schema`.

Infrastructure: PostgreSQL (shared_db) + Apache Kafka
```

---

## Module Descriptions

| Module                       | Port   | Description                                      |
|------------------------------|--------|--------------------------------------------------|
| `api-gateway`                | 8080   | Spring Cloud Gateway for unified API ingress     |
| `service-discovery`          | 8762   | Netflix Eureka Server (optional)                 |
| `tenant-service`             | 8081   | Manages tenant information                       |
| `user-service`               | 8082   | Manages user information                         |
| `anomaly-service`            | 8083   | Anomaly listing; syncs user/scheme dimensions    |
| `telemetry-service`          | 8084   | Meter readings: chatbot webhooks, OCR, partner API |
| `message-service`            | 8085   | Notifications (WhatsApp, email, SMS), delivery ledger |
| `scheme-service`             | 8086   | Manages water supply schemes                     |
| `analytics-service`          | 8087   | Consumes events; populates `analytics_schema`; dashboard APIs |

---

## Scheme Status Integer Mapping

`work_status`
- `1` = `Ongoing`
- `2` = `Completed`
- `3` = `Not Started`
- `4` = `Handed Over`

`operating_status`
- `0` = `Non-Operative`
- `1` = `Operative`
- `2` = `Partially Operative`

---

## Prerequisites

- **Java 21** (Eclipse Temurin recommended)
- **Maven 3.9+**
- **Docker & Docker Compose** (for Kafka and PostgreSQL)

---

## How to Start Eureka (Service Discovery)

Optional: services register only with `EUREKA_ENABLED=true` (anomaly and scheme have no switch and
always try), and their `EUREKA_URL` default points at `:8761`, so set it to the server's port.

```bash
cd backend/service-discovery
mvn clean package -DskipTests
java -jar target/*.jar
```

Eureka Dashboard: [http://localhost:8762](http://localhost:8762)

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

`common_schema` and tenant-schema migrations live in `backend/database/` (`V1`…`V65` at the time of
writing). `V1` creates `common_schema`; `V2` defines the `create_tenant_schema(schema_name)` PL/pgSQL
function, which later migrations replace as the tenant tables change and which also patch every
existing tenant schema. A `V<n>__*.sql.conf` beside a migration (e.g. `executeInTransaction=false`)
changes how Flyway runs it.

`analytics_schema` has its own migrations in `analytics-service/src/main/resources/db/migration`,
run by analytics-service on startup.

### How Flyway is wired into tenant-service

`tenant-service` runs the `backend/database/` migrations on startup. Its `pom.xml` copies
`V*.sql` and `V*.sql.conf` from `../database` into `classpath:db/migration` at build time, so the
directory stays the single source of truth. Configuration (`application.yml`):

```yaml
spring:
  flyway:
    enabled: true
    baseline-version: 0
    locations: classpath:db/migration
    baseline-on-migrate: true
    default-schema: common_schema
    schemas: common_schema
```

The history table is `common_schema.flyway_schema_history`. CI also builds the same files into a
standalone Flyway image, `tenant-service-db` (`backend/database/Dockerfile` + `migrate.sh`), for
running migrations as a separate step.

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

1. Create a new file in `backend/database/` following the Flyway naming convention: `V<version>__<description>.sql`, using the next unused version. A tenant-table change must update `create_tenant_schema()` and backfill existing tenant schemas (see V56 for the pattern).
2. Rebuild `tenant-service` (`mvn compile` or `mvn package`). The file is automatically copied to the classpath.
3. On next startup, Flyway applies only the new, unapplied migration.

### Cross-schema FK strategy

- **Within a schema**: formal `FOREIGN KEY` constraints are used.
- **Across schemas** (e.g. tenant schema tables referencing `common_schema`): FKs are **loosely coupled** — stored as plain `INTEGER` columns with no formal constraint. Referential integrity is enforced at the application level. This avoids tight cross-schema coupling and simplifies tenant provisioning.

---

## How to Run Services

### Option 1: Run All from Maven

```bash
# There is no parent POM: build each service on its own
cd backend
for s in api-gateway service-discovery tenant-service user-service anomaly-service \
         telemetry-service message-service scheme-service analytics-service; do
  (cd "$s" && mvn clean package -DskipTests)
done

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
# Tenant service endpoint through gateway (public list)
curl http://localhost:8080/tenant/api/v1/tenants

# Analytics endpoint through gateway (public dashboard read)
curl "http://localhost:8080/analytics/api/v1/analytics/schemes/dashboard?tenant_id=1"
```

Every endpoint, per service, is listed in [`API_ENDPOINTS.md`](API_ENDPOINTS.md); the gateway also
serves an aggregated Swagger UI at `/swagger-ui.html`.

### Actuator Health (All Services)

```
GET http://localhost:<port>/actuator/health
```

---

## Kafka Topics

| Service                    | Produces To                          | Consumes From    |
|----------------------------|--------------------------------------|------------------|
| `tenant-service`           | `tenant-service-topic`, `common-topic` | `common-topic` |
| `user-service`             | `user-service-topic`, `common-topic` | — |
| `anomaly-service`          | `anomaly-service-topic`              | `common-topic`, `telemetry-service-topic`, `user-service-topic` |
| `telemetry-service`        | `telemetry-service-topic`            | `common-topic`, `tenant-service-topic` |
| `message-service`          | `message-service-topic`, `account-email-dlt` | `common-topic`, `tenant-service-topic` |
| `scheme-service`           | `scheme-service-topic`               | `common-topic`   |
| `analytics-service`        | `analytics-service-topic`, `common-topic` | `tenant-service-topic`, `user-service-topic`, `scheme-service-topic`, `telemetry-service-topic`, `anomaly-service-topic`, `message-service-topic`, `common-topic` |

`common-topic` carries requests for message-service (nudges, escalations, reports, OTPs, emails) and
analytics' report KPIs. An event that fails all retries goes to `<topic>.DLT`.

---

## Future Enhancements

See [`gitbook/future-work.md`](../gitbook/future-work.md).

---

## Project Structure

```
backend/
├── README.md, API_ENDPOINTS.md, LOGGER.md
├── database/                 # Flyway migrations for common_schema + tenant schemas, and the
│                             # tenant-service-db image (Dockerfile, migrate.sh)
├── logger/                   # Local Loki / Prometheus / Grafana stack (docker compose)
├── api-gateway/              # :8080
├── service-discovery/        # :8762 (optional)
├── tenant-service/           # :8081, runs the backend/database migrations
├── user-service/             # :8082
├── anomaly-service/          # :8083
├── telemetry-service/        # :8084
├── message-service/          # :8085
├── scheme-service/           # :8086
└── analytics-service/        # :8087, runs its own analytics_schema migrations
```

Each service is a standalone Maven project (`pom.xml`, `Dockerfile`, `src/`) whose code lives under
`org.arghyam.jalsoochak.<service>`. CI builds images from the shared `build/maven/Dockerfile`.
