# Future Work

## 12. Future Work

Planned enhancements and known gaps beyond the current production scope.

### Security & RBAC

* **Complete `USER_TYPE_*` enforcement** — operator/officer roles are already extracted from the JWT by every service, the `user_type` Keycloak attribute is written when staff are provisioned or change role, and analytics' officer endpoints already require `USER_TYPE_SECTION_OFFICER` / `USER_TYPE_SUB_DIVISIONAL_OFFICER`; finishing the rollout needs a Keycloak protocol mapper for the `user_type` claim in every environment, backfilling the attribute for existing users, and enforcing it on the remaining services.
* **Shared `security-common` module** — extract the per-service `JwtAuthConverter` into one shared library to reduce duplication.

### Platform Capabilities

* **Native mobile application** as an alternative to the WhatsApp flow.
* **IoT reading channel** — the IoT channel code is defined and selectable per tenant, but a real-time sensor integration and its water-quantity calculator are still to come (BFM, ELM, and PDU calculators have shipped).
* **Live State-IT integration** — State IT systems push meter readings through the readings API, and a scheduled master-data sync (users, schemes, mappings) exists behind `STATE_SYNC_ENABLED`; it is off by default, and a few upstream questions are still open. Without it, master data arrives by bulk upload.

### Notifications & Localisation

* Expand per-tenant **message-template coverage** so every supported language has nudge and escalation templates (the resolver already falls back to English / a generic template).
* Broaden notification channels beyond WhatsApp, email, and SMS as deployments require.

### Operations & Observability

* Ship reference **Grafana dashboards** and **alerting** on the notification dead-letter topics (the local Loki/Prometheus/Grafana stack in `backend/logger` provisions only an empty "Microservices Overview" dashboard today).
* Formalise **distributed tracing** into a tracing backend (correlation IDs already flow through logs).

{% hint style="info" %}
This page is maintained alongside the codebase — items move out of "Future Work" and into the relevant section as they ship.
{% endhint %}
