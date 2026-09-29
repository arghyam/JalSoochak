# NFRs

## 11. Non-functional Requirements

### Performance

* Handle the daily morning submission/nudge burst of up to **X** messages per minute per tenant
* Dashboard API responses under **3 seconds** at normal load, using indexed queries, cursor-based streaming for large operator sets, a pre-populated date dimension, and Redis-cached dashboard responses warmed nightly (windows that include today refresh on a short TTL)

{% hint style="info" %}
The exact value for **X** (messages per minute per tenant) must be defined as part of capacity planning.
{% endhint %}

### Scalability

* **Horizontal scaling** on Kubernetes — services are stateless and run multiple replicas behind the registry
* **Kafka partition sizing** set per projected data volume; analytics consumers run in a dedicated consumer group, independent of notification consumers
* **Database isolation** — schema-per-tenant prevents one noisy tenant from degrading others; analytics runs on a separate instance

### Reliability

* **Dead-letter topics** capture failed notification deliveries (after bounded exponential-backoff retries) for review and replay, with deterministic retry IDs for idempotent reprocessing
* **Idempotent** bulk uploads and analytics upserts handle re-delivery and re-runs safely
* **Graceful degradation** — a dry-run mode lets staging verify routing without sending real messages; services can run standalone without the registry

### Security

* **HTTPS / TLS** for all external traffic
* **JWT authentication** (Keycloak) on all non-public APIs, with role-based access control
* **Rate limiting** (Redis-backed, at the API gateway) with stricter limits on the OTP and login endpoints; off by default, enabled with `RATE_LIMIT_ENABLED`
* **Tenant-scoped** dashboards and data — cross-tenant access is impossible at the schema level
* **PII encryption at rest** (AES-256) with HMAC lookup hashes; phone numbers never logged above DEBUG

### Observability

* **Structured logging** with correlation IDs for distributed tracing
* **Metrics endpoint** — every service exposes `/actuator/prometheus`; a local Loki/Promtail/Prometheus/Grafana stack ships in `backend/logger`
* **Prometheus + Grafana** metric dashboards for message throughput, daily submissions, Kafka consumer lag, and delivery success/failure
* **Alerting** on dead-letter topics for notification failures
