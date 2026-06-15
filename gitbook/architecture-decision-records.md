# Architecture Decision Records

## 8. Architecture Decision Records

{% stepper %}
{% step %}
### ADR-001: Microservices in a Mono-repo

**Decision:** All microservices live in a single Git repository.
**Rationale:** Easier code sharing, consistent tooling, simpler onboarding and CI/CD.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-002: Cloud-neutral Kubernetes Deployment

**Decision:** Generic Kubernetes manifests and Helm charts, no provider-specific managed services.
**Rationale:** Avoid vendor lock-in; support on-prem and any cloud.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-003: Schema-per-tenant on PostgreSQL

**Decision:** Each state tenant gets a dedicated PostgreSQL schema (`tenant_<stateCode>`) within a shared instance, alongside a common schema for cross-tenant metadata.
**Rationale:** Full data isolation at the database level — no risk of a missing `WHERE tenant_id` filter — while keeping operational overhead low. Cross-tenant aggregation is handled by a separate analytics warehouse rather than cross-schema joins.
**Status:** Accepted. *(Supersedes the earlier shared-schema / `tenant_id` approach.)*
{% endstep %}

{% step %}
### ADR-004: Glific as the WhatsApp Integration Layer

**Decision:** Integrate WhatsApp through Glific rather than the WhatsApp Business API directly.
**Rationale:** Glific handles template registration, compliance, contact opt-in, and interactive flows; the team focuses on domain logic. Glific is itself open-source and DPG-aligned.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-005: Apache Kafka (KRaft) for Asynchronous Events

**Decision:** Use Kafka in KRaft mode as the async event bus between services.
**Rationale:** Decouples producers from consumers, supports at-least-once delivery and replay, and enables dead-letter handling for failed notifications. KRaft removes the ZooKeeper dependency.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-006: Keycloak for Identity & JWT-based RBAC

**Decision:** Use Keycloak as the identity provider; every service is an OAuth2 resource server.
**Rationale:** Standard OAuth2/OIDC, custom JWT claims (`tenant_state_code`, `user_type`) so services derive tenant and role without a DB call, and self-hosting keeps data in the deployment environment.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-007: Separate PostgreSQL Instance for Analytics

**Decision:** Run the analytics star-schema warehouse on a dedicated PostgreSQL instance, fed by Kafka.
**Rationale:** Isolate heavy BI scans from operational read/write traffic; tune each database independently; expose read-only analytics safely.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-008: Non-blocking Client for Outbound Notifications

**Decision:** Use a reactive, non-blocking HTTP client for the message service's outbound calls to Glific / SendGrid.
**Rationale:** A small thread pool can handle large bursts of concurrent outbound requests during the morning nudge window, with built-in retry/back-off for provider rate limits.
**Status:** Accepted.
{% endstep %}

{% step %}
### ADR-009: PII Encryption at Rest with Lookup Hashes

**Decision:** Store phone numbers and names AES-256 encrypted, with HMAC-SHA256 hash columns for equality lookups; never log raw phone numbers above DEBUG.
**Rationale:** Protects PII at rest while still allowing indexed lookups without decryption.
**Status:** Accepted.
{% endstep %}
{% endstepper %}

<details>

<summary>Java vs Node (backend language choice)</summary>

Java + Spring Boot was chosen for the backend over Node.js for its mature, batteries-included ecosystem around multi-module services, JPA/transactions, Kafka, and OAuth2 resource-server support — matching the team's existing capacity and the long-lived, data-heavy nature of the platform.

</details>
