# Users & Tenancy

## 3. Users & Tenancy

### 3.1 Tenancy Model

Each **State** is a **tenant** (e.g. AP, TS, MH, MP). Every tenant has its own:

* Configuration — languages, water norms, escalation rules and thresholds, notification templates, cron schedules
* State-specific public dashboards
* User hierarchy, scheme data, and location hierarchies

A **country-level dashboard** aggregates data across all tenants (unavailable in single-tenant mode).

**Implementation approach:**

* **Schema-per-tenant** isolation on a single PostgreSQL instance — each state gets a dedicated schema (`tenant_<stateCode>`), giving full data isolation at the database level (no reliance on an application-layer `tenant_id` filter).
* A shared **`common_schema`** holds cross-tenant metadata (tenant registry, admin users, master data).
* Tenant schemas are provisioned automatically by an idempotent database function when a new tenant is created — no redeployment required.
* Each tenant carries a lifecycle status in `common_schema.tenant_master_table`: `REGISTERED` (7, seeded but not yet provisioned) → `ONBOARDED` (1) → `CONFIGURED` (2, set automatically once all mandatory config keys are present) → `ACTIVE` (3), plus `DEGRADED` (5), `SUSPENDED` (4), `INACTIVE` (0, via deactivate) and `ARCHIVED` (6). Staff can log in only to `ACTIVE` or `DEGRADED` tenants.
* **Single-tenant mode** (`SINGLE_TENANT_MODE=true`) caps a deployment at one tenant: creating a second is refused, and tenant-, user- and scheme-service refuse to start if more than one tenant is `ACTIVE`.

### 3.2 Business User Roles (Domain hierarchy)

These are **data-model dimensions** used for filtering and drill-down on dashboards — not necessarily direct login roles:

* **Central level:** Central Political, Central Bureaucratic
* **State level:** State Political, State Bureaucratic
* **State department levels:** Zone → Circle → Division → Sub-Division
* **State administrative levels:** District → Block → Gram Panchayat → Village → Pump Operator

### 3.3 System User Roles (Access & Configuration)

These are the actual **login / authorization roles** enforced via the Keycloak JWT.

{% stepper %}
{% step %}
### Super User (Platform Admin)

Manages all states / tenants. Actions:

* Add, edit, and deactivate states (tenants)
* Assign State System Admins
* Edit default configuration parameters (water norms, thresholds, WhatsApp / webhook settings)
{% endstep %}

{% step %}
### State System Admin (State Admin)

Manages configuration for exactly one state. Actions:

* Set default languages and water norms (e.g. 55 / 70 / 90 litres per capita per day)
* Configure the WhatsApp integration
* Set escalation thresholds and rules
* Upload schemes, operators, and location hierarchies; monitor data-sync issues
{% endstep %}

{% step %}
### Officers (District / Sub-Divisional / Section Officer)

Dashboard-access roles within a tenant. They consume scheme/compliance views and **receive escalation alerts** on WhatsApp — by default Section Officer for Level 1, District Officer for Level 2 (the officer type for each level is configurable per tenant). Section and Sub-Divisional Officers also get the officer-console analytics views and daily / weekly situation reports on WhatsApp.
{% endstep %}

{% step %}
### Pump Operator

A field role that interacts only through the **WhatsApp chatbot flow** — submits daily meter readings and receives nudge reminders. No web-dashboard access.
{% endstep %}
{% endstepper %}

Role and tenant context travel in the JWT (single Keycloak realm, `jalsoochak-realm`) as `tenant_state_code` and `user_type` claims, plus `SUPER_USER` / `STATE_ADMIN` realm roles. Services map these to `TENANT_<code>` and `USER_TYPE_<type>` authorities, so officer-only endpoints check `user_type` (e.g. `SECTION_OFFICER`, `SUB_DIVISIONAL_OFFICER`). In single-tenant mode a third role, `SUPER_STATE_ADMIN`, is expanded to both `SUPER_USER` and `STATE_ADMIN`; outside that mode it grants nothing extra. See [Technical Architecture](technical-architecture.md) for the security model.

### 3.4 User Provisioning Rules

Field-level users (Pump Operators, Section Officers, AEEs, EEs) are **not manually created** in the JalSoochak UI. Their data originates from **State IT systems**:

* A **one-time data dump** (bulk CSV/XLSX upload) during onboarding
* **API-based sync** for ongoing updates (phone numbers, reassignments)

Admin users (Super Users, State Admins) are **invited by email** and activate their accounts via a secure token link; a State Admin can invite only State Admins for their own tenant. Officers are not invited — they sign in with their registered phone number and a one-time password (sent on WhatsApp by default), and their Keycloak account is created on first login. Pump operators are onboarded directly into the WhatsApp flow. The ingestion/integration path validates each row, reports failures in a downloadable report, and applies de-duplication logic.

### 3.5 Default Offerings & State Options

The core platform is loosely coupled from state-specific choices: JalSoochak ships sensible **defaults**, and each state may **override** them during onboarding.

**Table 1: JalSoochak Deployment Choices**

| Service Area | Default | Alternatives |
|---|---|---|
| Image Ingestion | WhatsApp via the default provider | State-specific mobile app; WhatsApp via another BSP |
| Image Processor (meter OCR) | Home-grown AI model | State-preferred AI model |
| Nudge / Notifications | WhatsApp via the default provider | State-preferred channel / mobile app; email; SMS |
| Dashboards | Packaged with JalSoochak | State-customised hosted version; custom Analytics-API implementation |
| Deployment | Cloud-neutral on any hyperscaler (AWS / Azure / GCP) | Bare metal / on-prem |

The default providers are listed under *External integrations* in [Technology Stack](technology-stack.md).
Default values and options live in the tenant configuration store, applied at onboarding with State-Admin override. Overrides are recorded in tenant metadata and audited.
