# Future Work

This page tracks planned enhancements and known gaps in JalSoochak V2. Items are grouped by area. None of these block the current production scope; they represent the roadmap beyond it.

---

## 1. Security & RBAC

The RBAC rollout (gateway JWT validation, per-service resource servers, `SUPER_USER` / `STATE_ADMIN` enforcement) is complete in code. The remaining work concerns the finer-grained tenant-staff roles:

- **Complete `USER_TYPE_*` enforcement** — `OPERATOR`, `SECTION_OFFICER`, and `DISTRICT_OFFICER` are already extracted from the JWT by every service's `JwtAuthConverter`, but two steps remain before they can gate endpoints:
  1. **Keycloak protocol mapper** — add a `user_type` *User Attribute → Token Claim* mapper on `jalsoochak-client-id` (one-time Keycloak Admin step).
  2. **Write `user_type` to Keycloak** — in `AuthServiceImpl.activateAccount()`, mirror the existing `tenant_state_code` write so the attribute is set for tenant staff; backfill the attribute for existing Keycloak users via the Admin API.
- **Shared `security-common` module (Phase 5)** — extract the duplicated `JwtAuthConverter` (one identical copy per service) into a shared Maven module to reduce drift. Deferred to keep services independently deployable without a shared library dependency.
- **Endpoint-level analytics authorization** — analytics endpoints currently require authentication; add `@PreAuthorize` where finer control per role is needed.

> See `RBAC_IMPLEMENTATION.md` in the repository root for the full implementation reference and verification checklist.

---

## 2. Configuration Consistency

- **Eureka port alignment** — `service-discovery` listens on `server.port: 8762`, while client services default their `defaultZone` to `:8761`. Align these (and the documented Eureka port) so local startup registers cleanly without an `EUREKA_URL` override.
- **Externalise remaining hardcoded development credentials** in `application.yml` files to environment variables / secrets for all non-local deployments.

---

## 3. Platform Capabilities (Out of Current Scope)

These were intentionally excluded from the current release and are candidates for future iterations:

- **Native mobile application** — the current operator interface is the WhatsApp (Glific) flow; a companion app is not yet built.
- **Real-time IoT sensor integration** — automated submissions from connected flow sensors are modelled (channel `3` = IoT) but rely on mock adapters today.
- **Billing / payment processing** — not in scope for the monitoring platform.
- **Direct integration with state legacy systems** — currently handled via bulk CSV upload rather than live system-to-system integration.

---

## 4. Observability & Operations

- Ship reference **Grafana dashboards** for the recommended metrics (message throughput, daily submission counts, Kafka consumer lag, notification success/failure).
- Add **alerting** on the dead-letter topics (`welcome-message-dlt`, `account-email-dlt`) so delivery failures are caught proactively.
- Formalise **distributed tracing** (correlation IDs already flow through logs) into a tracing backend.

---

## 5. Notifications & Localisation

- Expand **message-template coverage** so every supported language has nudge and escalation templates configured per tenant (the resolution chain already falls back to English, then a generic template).
- Broaden notification channels beyond WhatsApp/email/SMS as deployments require.

---

{% hint style="info" %}
This list is maintained alongside the codebase. When an item ships, move it out of this page and into the relevant service or architecture doc.
{% endhint %}
