# Functional Overview

## 1. Core Functional Modules

### 1.1 Field Operations (Meter Reading Submission)

The primary workflow: pump operators submit daily flow meter readings via WhatsApp.

* Operator opens the JalSoochak WhatsApp flow (provided by Glific)
* Flow prompts the operator to take and submit a photo of the flow meter
* FlowVision AI extracts the numeric reading from the image with a confidence score
  * Confidence ≥ 85%: extracted value is shown to the operator for confirmation
  * Confidence < 85%: operator is prompted to enter the value manually
* Operator confirms or corrects the reading in the WhatsApp chat
* Reading is validated (monotonic check, outlier detection, daily duplicate check)
* Reading is saved and a `METER_READING_SUBMITTED` event is published to Kafka

Staff with dashboard access can also submit readings manually via the web interface for situations where WhatsApp is unavailable.

### 1.2 Nudge and Escalation Notification Pipeline

Automated daily cron jobs identify operators with missed readings and trigger WhatsApp notifications:

**Nudge (daily reminder):**

* Runs every morning (configurable per tenant; default 10:30 AM IST)
* Identifies all pump operators who have not submitted a reading today
* Sends a WhatsApp nudge via the Glific flow in the operator's preferred language

**Level 1 Escalation:**

* Runs shortly after the nudge (default 10:32 AM IST)
* Identifies operators who have missed ≥ L1 threshold consecutive days (default: 3)
* Sends a PDF report to the assigned Section Officer via WhatsApp

**Level 2 Escalation:**

* Same run as Level 1
* Identifies operators who have missed ≥ L2 threshold days (default: 7) or have never submitted
* Sends a PDF report to the assigned District Officer via WhatsApp

{% hint style="info" %}
All thresholds, cron schedules, and message templates are configurable per tenant through the tenant configuration API. No redeployment is required to change them.
{% endhint %}

### 1.3 Scheme Management

Water supply schemes (the physical infrastructure units — pumping stations, pipelines, storage tanks) are managed through:

* Bulk CSV upload of scheme data at onboarding
* LGD (Local Government Directory) location hierarchy mapping: State → District → Block → Panchayat → Village
* Departmental hierarchy mapping: State → Zone → Circle → Division → Sub-Division
* Filterable scheme list with work status and operational status tracking
* Scheme performance metrics (compliance rate, LPCD, last reading date)

### 1.4 User and Staff Management

* **Pump operators** are bulk-uploaded via CSV by the State Admin
* **Staff** (section officers, district officers) are invited by email and activate their accounts via a secure token link
* WhatsApp OTP login available for field staff who may not have email-based credentials
* Users can be deactivated without deletion; audit trail is preserved

### 1.5 Analytics and Dashboards

Multi-level reporting for programme monitoring:

* **Scheme-level dashboard:** compliance rate, daily quantity, LPCD, norm achievement
* **State-level dashboard:** aggregate compliance, top/bottom performing schemes, escalation history
* **National dashboard:** cross-state comparison, national compliance rate, state-by-state breakdown
* **Regularity metrics:** operator attendance, consecutive days missed, trend analysis

Key metrics computed:

| Metric | Formula |
|--------|---------|
| **Compliance Rate** | `(days_with_reading ÷ total_active_days) × 100` |
| **LPCD** | `daily_quantity ÷ FHTC count` |
| **Norm Achievement** | Whether LPCD meets the tenant's configured water norm |

### 1.6 Tenant Onboarding and Configuration

New state tenants are onboarded by a Super User and configured by the State Admin:

1. Super User creates the tenant — a dedicated PostgreSQL schema is provisioned automatically
2. State Admin uploads the location hierarchy (LGD and departmental) via CSV
3. State Admin bulk-uploads scheme data and pump operators via CSV
4. State Admin configures tenant settings: languages, water norms, escalation thresholds, notification templates
5. Tenant is marked ACTIVE and field operations begin

### 1.7 Identity and Access Management

* Keycloak manages authentication and issues JWTs for all API access
* Custom JWT claims carry the tenant's state code and user type
* Role-based access control enforced at every endpoint
* Refresh token rotation with database-backed revocation
* Password reset via email and WhatsApp OTP login for field staff

### 1.8 Multi-Language Notification Support

Notification messages (nudges and escalations) are delivered in each operator's preferred language. The platform supports 15 Indian languages:

Hindi, Bengali, Telugu, Marathi, Tamil, Gujarati, Kannada, Malayalam, Odia, Punjabi, Assamese, Urdu, Maithili, Sanskrit, and English.

Language preference is set per user and stored in the database. Message templates per language are configured in the tenant configuration store.

---

## 2. Core Workflows

### 2.1 Meter Reading Submission (WhatsApp)

```
1.  Operator opens the JalSoochak WhatsApp flow via Glific
2.  Flow prompts: "Take a photo of the flow meter"
3.  Operator submits meter photo
4.  Glific forwards the image URL to the telemetry-service webhook
5.  telemetry-service calls FlowVision AI → extracts reading + confidence score
      ├─ Confidence ≥ 85%: reading auto-accepted; operator confirms
      └─ Confidence < 85%: operator prompted to enter reading manually
6.  Operator confirms or overrides in the WhatsApp chat
7.  telemetry-service validates the reading:
      ├─ Must be ≥ previous reading
      ├─ Delta within the expected range
      └─ Only one reading per scheme per calendar day
8.  Reading persisted and METER_READING_SUBMITTED event published to Kafka
9.  analytics-service consumes the event and updates the data warehouse
```

### 2.2 New Tenant Onboarding

```
1. Super User creates tenant via API
2. tenant-service provisions a new PostgreSQL schema for the state
3. TENANT_CREATED event published → analytics-service syncs dimension tables
4. State Admin logs in and completes configuration:
     ├─ Upload LGD location hierarchy (CSV)
     ├─ Upload departmental hierarchy (CSV)
     ├─ Bulk upload water supply schemes (CSV)
     ├─ Bulk upload pump operators (CSV)
     ├─ Upload operator-to-scheme mappings (CSV)
     └─ Set languages, water norms, escalation thresholds
5. Tenant marked ACTIVE; field operations begin
```

### 2.3 Staff Invitation Flow

```
1. State Admin invites a staff member via the web dashboard
2. A secure invite token is generated (expires in 7 days)
3. SEND_INVITE_EMAIL event published → invitation email sent via SendGrid
4. Staff receives email with activation link
5. Staff sets password and activates their account
6. Keycloak account created; SEND_WELCOME_MESSAGE event published
7. Staff receives a WhatsApp welcome message via Glific
```
