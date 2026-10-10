# Non-Functional Requirements

## 1. Security

### 1.1 Authentication and Authorisation

* All API endpoints (except designated public ones) require a valid JWT issued by Keycloak
* Role-based access control (RBAC) is enforced at every endpoint using Spring Security
* JWT claims carry tenant identity (`tenant_state_code`) and user role (`user_type`), ensuring cross-tenant data access is impossible at the framework level
* Refresh tokens are stored hashed (SHA-256) in the database and support explicit revocation

### 1.2 PII Protection

* Phone numbers and user names are classified as PII and stored AES-256 (CBC) encrypted at rest
* HMAC-SHA256 hashes are stored alongside encrypted fields to support equality lookups without decryption
* Phone numbers must never appear in `INFO`, `WARN`, or `ERROR` log lines — only at `DEBUG` level
* Test phone numbers use the `91XXXXXXXXXX` format with non-real numbers

### 1.3 SQL Injection Prevention

* All tenant-schema-scoped queries in `NudgeRepository` and `EscalationRepository` validate tenant schema names against a strict allowlist (alphanumeric + underscore only) before interpolating them into SQL
* JPA/Hibernate parameterised queries are used for all other database access

### 1.4 Secrets Management

* No credentials are committed to version control
* All secrets (database passwords, API keys, encryption keys) are injected via environment variables
* In Kubernetes deployments, secrets are stored in `Secret` objects, not `ConfigMaps`

{% hint style="danger" %}
PII encryption keys (`PII_ENCRYPTION_KEY`, `PII_HMAC_KEY`) must be rotated according to your organisation's key management policy. Losing these keys means encrypted data cannot be recovered.
{% endhint %}

---

## 2. Reliability

### 2.1 Dead-Letter Queues

Failed Kafka message deliveries are routed to dead-letter topics for manual review and replay, preventing a single bad record from blocking the entire notification pipeline:

| Dead-Letter Topic | Event Types | Common Cause |
|------------------|-------------|-------------|
| `welcome-message-dlt` | `SEND_WELCOME_MESSAGE` | Missing WhatsApp connection ID |
| `account-email-dlt` | All `SEND_*_EMAIL` events | SendGrid API failure, blank email address |

Dead-letter records carry deterministic UUID v3 `retryId` values for idempotent downstream reprocessing.

### 2.2 Idempotency

* Analytics dimension upserts use composite unique keys (e.g. `tenant_id + external_id`) to handle Kafka re-delivery safely
* Bulk CSV uploads for schemes and operators use the `state_scheme_id` as the idempotent key — re-uploading updates existing records rather than creating duplicates
* Escalation correlation IDs are deterministic UUID v3 values derived from scheme, user, and streak data — enabling idempotent deduplication downstream

### 2.3 Kafka Consumer Resilience

* Kafka consumers apply retry and back-off policies before routing to dead-letter topics
* Glific API calls implement exponential back-off with jitter on 429 (rate-limit) responses: 5 s → 10 s → 20 s with ±1 s jitter
* A minimum interval (`glific.request-interval-ms`, default 500 ms) is enforced between consecutive Glific API calls

### 2.4 Graceful Degradation

* The `NOTIFICATIONS_DRY_RUN=true` flag allows staging environments to verify event routing and language resolution without sending real WhatsApp messages or emails
* Services can be started with `EUREKA_ENABLED=false` for isolated debugging without requiring the full service registry

---

## 3. Scalability

### 3.1 Horizontal Scaling

* All backend microservices are stateless — sessions are managed via JWTs and database-backed tokens, not in-process memory
* Services can scale horizontally by running multiple pods behind the Eureka registry
* Spring Cloud LoadBalancer distributes requests across multiple instances of the same service

### 3.2 Database Isolation

* The analytics database runs on a separate PostgreSQL instance (port 5433), ensuring heavy BI queries do not compete with operational read/write traffic
* Schema-per-tenant isolation means a noisy tenant cannot degrade database performance for other tenants

### 3.3 Kafka Partitioning

* Kafka topics are partitioned to support parallel consumption; partition count should be set based on projected data volume during capacity planning
* Analytics consumers are in a dedicated consumer group, independent of notification consumers

### 3.4 Streaming for Large Datasets

* Nudge and escalation queries use server-side database cursors (fetch size 500) to avoid materialising large result sets into heap — critical when processing thousands of operators per tenant

---

## 4. Performance

* Dashboard API responses targeting < 3 seconds under normal load
* Nudge queries use indexed columns (`reading_date`, `scheme_id`, `user_id`) and cursor-based streaming to process large operator sets efficiently
* `dim_date_table` in the analytics warehouse is pre-populated rather than computed on-the-fly, enabling fast time-based aggregations
* Redis is used for session caching to reduce database round-trips on token validation

---

## 5. Observability

### 5.1 Health Checks

All services expose Spring Boot Actuator endpoints used as Kubernetes readiness and liveness probes:

```
GET /actuator/health
GET /actuator/info
GET /actuator/prometheus
```

### 5.2 Metrics

* Prometheus metrics are scraped from `/actuator/prometheus` on all services
* Recommended Grafana dashboards: message throughput, daily submission counts, Kafka consumer lag, notification delivery success/failure rates

### 5.3 Logging

* Structured JSON logging with correlation IDs for distributed tracing across services
* Log level `INFO` for normal operations, `DEBUG` for PII-containing fields
* Configure alerting on the `welcome-message-dlt` and `account-email-dlt` Kafka topics for delivery failures

### 5.4 API Documentation

* Auto-generated Swagger UI at `/swagger-ui/index.html` per service
* OpenAPI JSON available at `/v3/api-docs` per service
* Documentation updates automatically on every deployment — no manual maintenance required

---

## 6. Data Integrity

### 6.1 Reading Validation

Every meter reading passes through `ReadingValidationService` before being persisted:

| Check | Rule |
|-------|------|
| **Monotonic** | `confirmedReading ≥ previousReading` — meters cannot run backwards (except after a replacement) |
| **Outlier** | Daily delta must not exceed N× the scheme's average daily consumption |
| **Duplicate** | Only one reading per scheme per calendar day is accepted |
| **Meter replacement** | Resets the baseline; the monotonic check is skipped for that submission |

### 6.2 Migration Safety

* Flyway migrations are version-controlled in `backend/database/`
* `tenant-service` is the sole runner of all migrations — no other service executes Flyway
* The analytics service uses idempotent upserts to handle duplicate Kafka events without data corruption

---

## 7. Privacy and Compliance

* User consent and data governance requirements are met through PII encryption at rest and access control
* All API connections use HTTPS (TLS in transit)
* Phone numbers are never stored in plain text, log files, or event payloads at INFO/WARN/ERROR level
* Tenant data is fully isolated at the database schema level — a State Admin for tenant A cannot access tenant B's data
