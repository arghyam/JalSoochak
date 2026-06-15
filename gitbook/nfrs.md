# NFRs

## 11. Non-functional Requirements

### Performance

* Handle the daily morning submission/nudge burst of up to **X** messages per minute per tenant
* Dashboard API responses under **3 seconds** at normal load, using indexed queries, cursor-based streaming for large operator sets, and a pre-populated date dimension

{% hint style="info" %}
The exact value for **X** (messages per minute per tenant) must be defined as part of capacity planning.
{% endhint %}

### Scalability

* **Horizontal scaling** on Kubernetes — services are stateless and run multiple replicas behind the registry
* **Kafka partition sizing** set per projected data volume; analytics consumers run in a dedicated consumer group, independent of notification consumers
* **Database isolation** — schema-per-tenant prevents one noisy tenant from degrading others; analytics runs on a separate instance

### Reliability

* **Dead-letter topics** capture failed notification deliveries for review and replay, with deterministic retry IDs for idempotent reprocessing
* **Idempotent** bulk uploads and analytics upserts handle re-delivery and re-runs safely
* **Graceful degradation** — a dry-run mode lets staging verify routing without sending real messages; services can run standalone without the registry

### Security

* **HTTPS / TLS** for all external traffic
* **JWT authentication** (Keycloak) on all non-public APIs, with role-based access control
* **Tenant-scoped** dashboards and data — cross-tenant access is impossible at the schema level
* **PII encryption at rest** (AES-256) with HMAC lookup hashes; phone numbers never logged above DEBUG

### Observability

* **Structured logging** with correlation IDs for distributed tracing
* **Prometheus + Grafana** metric dashboards for message throughput, daily submissions, Kafka consumer lag, and delivery success/failure
* **Alerting** on dead-letter topics for notification failures
