# Users & Tenancy

## 3. Users & Tenancy

### 3.1 Tenancy Model

Each **State** is a **tenant** (e.g. AP, TS, MH, MP). Every tenant has its own:

* Configuration — languages, water norms, escalation rules and thresholds, notification templates, cron schedules
* State-specific public dashboards
* User hierarchy, scheme data, and location hierarchies

A **country-level dashboard** aggregates data across all tenants.

**Implementation approach:**

* **Schema-per-tenant** isolation on a single PostgreSQL instance — each state gets a dedicated schema (`tenant_<stateCode>`), giving full data isolation at the database level (no reliance on an application-layer `tenant_id` filter).
* A shared **`common_schema`** holds cross-tenant metadata (tenant registry, admin users, master data).
* Tenant schemas are provisioned automatically by an idempotent database function when a new tenant is created — no redeployment required.

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
* Edit default configuration parameters (water norms, thresholds, Glific / webhook settings)
{% endstep %}

{% step %}
### State System Admin (State Admin)

Manages configuration for exactly one state. Actions:

* Set default languages and water norms (e.g. 55 / 70 / 90 litres per capita per day)
* Configure WhatsApp / Glific integration
* Set escalation thresholds and rules
* Upload schemes, operators, and location hierarchies; monitor data-sync issues
{% endstep %}

{% step %}
### Officers (District Officer / Section Officer)

Dashboard-access roles within a tenant. They consume scheme/compliance views and **receive escalation alerts** on WhatsApp — Section Officer for Level 1, District Officer for Level 2.
{% endstep %}

{% step %}
### Pump Operator

A field role that interacts only through the **WhatsApp (Glific) flow** — submits daily meter readings and receives nudge reminders. No web-dashboard access.
{% endstep %}
{% endstepper %}

Role and tenant context travel in the JWT as `tenant_state_code` and `user_type` claims, plus `SUPER_USER` / `STATE_ADMIN` realm roles. See [Technical Architecture](technical-architecture.md) for the security model.

### 3.4 User Provisioning Rules

Field-level users (Pump Operators, Section Officers, AEEs, EEs) are **not manually created** in the JalSoochak UI. Their data originates from **State IT systems**:

* A **one-time data dump** (bulk CSV/XLSX upload) during onboarding
* **API-based sync** for ongoing updates (phone numbers, reassignments)

Staff who need login access (State Admins, officers) are **invited by email** and activate their accounts via a secure token link; pump operators are onboarded directly into the WhatsApp flow. The ingestion/integration path keeps sync logs, error logs, and de-duplication logic.

### 3.5 Default Offerings & State Options

The core platform is loosely coupled from state-specific choices: JalSoochak ships sensible **defaults**, and each state may **override** them during onboarding.

**Table 1: JalSoochak Deployment Choices**

| Service Area | Default | Alternatives |
|---|---|---|
| Image Ingestion | WhatsApp with Glific | State-specific mobile app; WhatsApp via another BSP |
| Image Processor (meter OCR) | Home-grown FlowVision AI model | State-preferred AI model |
| Nudge / Notifications | WhatsApp with Glific | State-preferred channel / mobile app; email; SMS |
| Dashboards | Packaged with JalSoochak | State-customised hosted version; custom Analytics-API implementation |
| Deployment | Cloud-neutral on any hyperscaler (AWS / Azure / GCP) | Bare metal / on-prem |

Default values and options live in the tenant configuration store, applied at onboarding with State-Admin override. Overrides are recorded in tenant metadata and audited.
