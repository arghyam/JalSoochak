# Functional Overview

## 4. Functional Overview

### 4.1 Core Functional Modules

{% stepper %}
{% step %}
### Field Operations

Manages schemes, pumps, operators, and daily meter readings. Operators submit a flow-meter photo over WhatsApp; an **AI OCR provider** (configurable per tenant) extracts the reading and the operator confirms or corrects it. Readings are validated (monotonic, outlier, daily-duplicate checks, plus an optional location check against the scheme's coordinates) before being persisted; implausible daily-supply values can be quarantined instead of published. Scheme performance metrics (compliance, LPCD, last-reading date) are derived from them.
{% endstep %}

{% step %}
### Messaging & Nudge Orchestration

State-configured messaging over **WhatsApp**. Daily schedulers identify operators who missed a reading and dispatch **nudge** reminders; persistent non-submission triggers **escalation** notifications (with a PDF report) to the responsible officers. Officers also receive a scheduled **Daily Water Service Situation Report** (Section Officers) and a **Weekly** report (Section and Sub-Divisional Officers), delivered over WhatsApp as a PDF document or a link. Message text is resolved per operator language, with a fallback chain. Account emails and SMS login OTPs are sent through the tenant's own email/SMS provider or the platform default.
{% endstep %}

{% step %}
### Dashboards & Analytics

Multi-level, hierarchical dashboards (national → state → district → scheme) with colour-coded status indicators. Metrics include compliance rate, daily water quantity, LPCD, norm achievement, operator regularity, and escalation history — served from a dedicated analytics data warehouse.
{% endstep %}

{% step %}
### Configuration & State Administration

Per-tenant customisation: languages, reading channels, water norms, escalation thresholds, nudge/escalation/report schedules, notification templates, and email/SMS provider settings — all configurable without code changes or redeployment. Super Users manage tenants; State Admins manage their state's configuration and staff.
{% endstep %}

{% step %}
### Identity & Access Management

**Keycloak**-based authentication issuing JWTs. Email + password login for Super Users and State Admins, WhatsApp or SMS OTP login for field staff, refresh-token rotation with database-backed revocation, and role-based access control enforced at every endpoint.
{% endstep %}

{% step %}
### Anomaly Detection

Rule-based flagging of implausible readings (unreadable or duplicate images, readings below the previous value, implausible supply), repeated manual overrides, location mismatches, low/no/over supply, and missed submissions, designed to be ML-ready. Detected anomalies feed the dashboards and the officer console.
{% endstep %}

{% step %}
### Integration

Onboarding of users, schemes, and their mappings from **State IT systems** via bulk CSV/spreadsheet uploads, with row-level validation, de-duplication, and downloadable reports. State IT systems can also push meter readings directly through an API-key-authenticated readings API that accepts multiple payload formats.
{% endstep %}
{% endstepper %}
