# Functional Overview

## 4. Functional Overview

### 4.1 Core Functional Modules

{% stepper %}
{% step %}
### Field Operations

Manages schemes, pumps, operators, and daily meter readings. Operators submit a flow-meter photo over WhatsApp; **FlowVision AI** extracts the reading and the operator confirms or corrects it. Readings are validated (monotonic, outlier, daily-duplicate checks) before being persisted, and scheme performance metrics (compliance, LPCD, last-reading date) are derived from them.
{% endstep %}

{% step %}
### Messaging & Nudge Orchestration

State-configured messaging integrated with **Glific**. Daily schedulers identify operators who missed a reading and dispatch **nudge** reminders; persistent non-submission triggers **escalation** notifications (with a PDF report) to the responsible officers. Message text is resolved per operator language, with a fallback chain.
{% endstep %}

{% step %}
### Dashboards & Analytics

Multi-level, hierarchical dashboards (national → state → district → scheme) with colour-coded status indicators. Metrics include compliance rate, daily water quantity, LPCD, norm achievement, operator regularity, and escalation history — served from a dedicated analytics data warehouse.
{% endstep %}

{% step %}
### Configuration & State Administration

Per-tenant customisation: languages, water norms, escalation thresholds, cron schedules, and notification templates — all configurable without code changes or redeployment. Super Users manage tenants; State Admins manage their state's configuration and staff.
{% endstep %}

{% step %}
### Identity & Access Management

**Keycloak**-based authentication issuing JWTs. Email + password login for staff, WhatsApp OTP login for field staff, refresh-token rotation with database-backed revocation, and role-based access control enforced at every endpoint.
{% endstep %}

{% step %}
### Anomaly Detection

Rule-based flagging of implausible readings, missed submissions, and prolonged outages, designed to be ML-ready. Detected anomalies feed the dashboards and the escalation pipeline.
{% endstep %}

{% step %}
### Integration

Onboarding and ongoing synchronisation of users, schemes, and location hierarchies from **State IT systems** via bulk dumps and API-based sync, with sync/error logs and de-duplication.
{% endstep %}
{% endstepper %}
