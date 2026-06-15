# Future Work

## 12. Future Work

Planned enhancements and known gaps beyond the current production scope.

### Security & RBAC

* **Complete `USER_TYPE_*` enforcement** — operator/officer roles are already extracted from the JWT by every service; finishing the rollout needs a Keycloak protocol mapper for the `user_type` claim, writing the attribute at account activation, and backfilling it for existing users.
* **Shared `security-common` module** — extract the per-service `JwtAuthConverter` into one shared library to reduce duplication.

### Platform Capabilities

* **Native mobile application** as an alternative to the WhatsApp flow.
* **Real-time IoT sensor integration** — replace the mock adapters for IoT, electricity-consumption, and pump-runtime channels with live integrations.
* **Live State-IT integration** — move beyond bulk CSV dumps to system-to-system sync.

### Notifications & Localisation

* Expand per-tenant **message-template coverage** so every supported language has nudge and escalation templates (the resolver already falls back to English / a generic template).
* Broaden notification channels beyond WhatsApp, email, and SMS as deployments require.

### Operations & Observability

* Ship reference **Grafana dashboards** and **alerting** on the notification dead-letter topics.
* Formalise **distributed tracing** into a tracing backend (correlation IDs already flow through logs).

{% hint style="info" %}
This page is maintained alongside the codebase — items move out of "Future Work" and into the relevant section as they ship.
{% endhint %}
