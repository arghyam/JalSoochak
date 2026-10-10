# Deployment Architecture

## 1. Core Principles

* **Cloud-neutral** — runs on any Kubernetes cluster (on-prem, AWS, GCP, Azure) with no proprietary vendor dependencies
* **Infrastructure as Code** — all Kubernetes manifests, ConfigMaps, and Secrets managed declaratively
* **Stateless services** — all backend microservices are stateless and scale horizontally; state lives in PostgreSQL, Kafka, and Redis
* **Environment parity** — development, staging, and production environments share the same infrastructure topology

---

## 2. Infrastructure Components

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Kubernetes | Any K8s cluster | Container orchestration for all microservices |
| PostgreSQL | v16 | Operational database (schema-per-tenant) |
| PostgreSQL | v16 (second instance) | Analytics data warehouse (separate instance) |
| Apache Kafka | 3.6+ (KRaft mode) | Async event bus between microservices |
| Redis | v7 | Session caching and token storage |
| Keycloak | v23+ | Identity provider; JWT issuance and validation |
| MinIO | Latest | S3-compatible object storage for escalation PDFs and tenant assets |
| Netflix Eureka | Spring Cloud | Service registry and discovery |

### 2.1 Minimum Version Requirements

| Component | Minimum Version | Notes |
|-----------|----------------|-------|
| Java | 21 (Eclipse Temurin) | All backend services |
| PostgreSQL | 16 | Two separate instances required |
| Apache Kafka | 3.6+ | KRaft mode only — no Zookeeper |
| Redis | 7 | Single node sufficient for non-HA deployments |
| Keycloak | 23+ | Custom attribute mappers must be configured |
| MinIO | Latest | Or any S3-compatible store |

---

## 3. Network Security Model

Public-facing entry points are limited to three:

* **API Gateway** — terminates HTTPS, validates JWTs, routes to internal services
* **React Frontend** — served as static assets via a CDN or Nginx
* **Glific flow webhooks** — `/api/v1/telemetry/*` on `telemetry-service` (called by Glific at each step of the WhatsApp flow; rate-limited at ingress)

All internal services and databases run within a **private network** and are not directly reachable from outside the cluster.

```
Internet
    │
    ├── API Gateway (public, HTTPS)
    ├── Frontend (public, HTTPS/CDN)
    └── Webhook endpoint (public, rate-limited)

Private Network
    ├── tenant-service
    ├── user-service
    ├── telemetry-service
    ├── scheme-service
    ├── message-service
    ├── analytics-service
    ├── service-discovery (Eureka)
    ├── PostgreSQL (operational)
    ├── PostgreSQL (analytics)
    ├── Kafka cluster
    ├── Redis
    ├── MinIO
    └── Keycloak
```

---

## 4. Service Startup Order

Services have a strict startup dependency chain:

```
1. PostgreSQL (operational + analytics instances)
2. Apache Kafka (KRaft cluster)
3. Redis
4. Keycloak
5. service-discovery (:8761)    ← wait until accepting connections
6. tenant-service (:8081)       ← wait until Flyway migrations complete
7. All remaining services       ← any order
```

{% hint style="warning" %}
`tenant-service` must complete all Flyway migrations before other services start. Other services query tables created by those migrations. Starting them in parallel risks startup failures.
{% endhint %}

---

## 5. Kubernetes Deployment Notes

* Deploy each service as a separate `Deployment`
* Use `ConfigMap` for non-sensitive `application.yml` overrides
* Use `Secret` for all credentials (database passwords, API keys, PII encryption keys, Keycloak secrets)
* Use `StatefulSet` for PostgreSQL, Kafka brokers, and Redis
* Configure `readinessProbe` pointing to `/actuator/health` for all services
* Set `EUREKA_URL` environment variable to point to the in-cluster Eureka service

### 5.1 Keycloak Setup Requirements

Before starting any backend service, configure Keycloak:

1. Create realm: `jalsoochak-realm`
2. Create client: `jalsoochak-client` (standard flow + bearer-only)
3. Add custom user attribute mappers to the client:
   * Attribute `tenant_state_code` → JWT claim `tenant_state_code`
   * Attribute `user_type` → JWT claim `user_type`
4. Create realm roles: `SUPER_USER`, `STATE_ADMIN`
5. Generate and securely store the client secret

---

## 6. Health Checks and Observability

All services expose Spring Boot Actuator endpoints:

```
GET http://<host>:<port>/actuator/health     ← readiness/liveness probe
GET http://<host>:<port>/actuator/info
GET http://<host>:<port>/actuator/prometheus ← Prometheus metrics scraping
```

### 6.1 Recommended Monitoring Stack

* **Prometheus + Grafana** — scrape `/actuator/prometheus` from all services; build dashboards for message throughput, daily submission counts, and Kafka consumer lag
* **EFK / ELK stack** — structured JSON logging from all services with correlation IDs for distributed tracing

---

## 7. Environment Variables Reference

All services accept environment variable overrides for all configuration values. Key variables:

#### Database

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/jalsoochak_db
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<password>
```

#### Kafka

```bash
SPRING_KAFKA_BOOTSTRAP_SERVERS=<broker1>:9092,<broker2>:9092,<broker3>:9092
```

#### Redis

```bash
REDIS_HOST=<host>
REDIS_PORT=6379
```

#### Keycloak

```bash
KEYCLOAK_ISSUER_URI=https://<host>/realms/jalsoochak-realm
KEYCLOAK_AUTH_SERVER_URL=https://<host>
KEYCLOAK_CLIENT_SECRET=<secret>
KEYCLOAK_ADMIN_USERNAME=<admin>
KEYCLOAK_ADMIN_PASSWORD=<password>
```

#### PII Encryption

```bash
# Both must be base64-encoded 256-bit keys
PII_ENCRYPTION_KEY=<base64-aes-256-key>
PII_HMAC_KEY=<base64-hmac-256-key>
```

#### Service Discovery

```bash
EUREKA_ENABLED=true
EUREKA_URL=http://<eureka-host>:8761/eureka/
```

#### WhatsApp (Glific)

```bash
GLIFIC_API_URL=<graphql-endpoint>
GLIFIC_USERNAME=<login-phone>
GLIFIC_PASSWORD=<password>
GLIFIC_FLOW_NUDGE_ID=<nudge-flow-id>
GLIFIC_FLOW_WELCOME_ID=<welcome-flow-id>
GLIFIC_ESCALATION_TEMPLATE_ID=<template-id>
GLIFIC_LOGIN_OTP_TEMPLATE_ID=<template-id>
```

#### Email (SendGrid)

```bash
SENDGRID_API_KEY=<key>
SENDGRID_TEMPLATE_DEFAULT_INVITATION=<template-id>
SENDGRID_TEMPLATE_PASSWORD_RESET=<template-id>
```

#### Object Storage (MinIO / S3)

```bash
MINIO_ENDPOINT=<endpoint>
MINIO_ACCESS_KEY=<key>
MINIO_SECRET_KEY=<secret>
MINIO_BUCKET=<bucket-name>
MINIO_BASE_URL=<public-base-url>
```

#### Scheduling

```bash
NUDGE_CRON=0 30 10 * * *          # Default: 10:30 AM IST
ESCALATION_CRON=0 32 10 * * *     # Default: 10:32 AM IST
ANALYTICS_SCHEDULER_CRON=0 0 19 * * *   # Default: 7 PM IST
ANALYTICS_SCHEDULER_ZONE=Asia/Kolkata
```

#### Dry Run (Staging)

```bash
# Set to true to log notification events without sending real messages
NOTIFICATIONS_DRY_RUN=false
```

{% hint style="danger" %}
Never commit real credentials to version control. Use Kubernetes Secrets, HashiCorp Vault, or your deployment platform's secret store for all values marked with angle brackets.
{% endhint %}

---

## 8. Development Setup

### 8.1 Prerequisites

* Java 21 (Eclipse Temurin recommended)
* Maven 3.9+
* Docker and Docker Compose
* Node.js LTS (for frontend)

{% hint style="warning" %}
If your system Java is newer than 21 (e.g. JDK 23+), set `JAVA_HOME` explicitly to a JDK 21 installation before building. Newer JDKs cause Lombok annotation processing failures during the Maven build.
{% endhint %}

### 8.2 Backend

```bash
# 1. Start infrastructure services
docker compose up -d postgres redis kafka minio keycloak

# 2. Build a service (skip tests for faster iteration)
cd backend/<service-name>
mvn clean package -DskipTests

# 3. Start service-discovery first
cd backend/service-discovery && mvn spring-boot:run

# 4. Start tenant-service and wait for migrations
cd backend/tenant-service && mvn spring-boot:run

# 5. Start remaining services (any order)
cd backend/user-service && mvn spring-boot:run
# ... etc
```

### 8.3 Frontend

```bash
cd frontend
npm install
npm run dev     # Development server at http://localhost:5173
npm run build   # Production build → dist/
```

### 8.4 Running Tests

```bash
# All tests for a service (requires Docker for Testcontainers)
cd backend/<service-name>
mvn test

# Single test class
mvn test -Dtest=ClassName
```
