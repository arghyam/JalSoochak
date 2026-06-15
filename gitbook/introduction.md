# Introduction

## 1. Introduction

### 1.1 Purpose

**JalSoochak** is a **Digital Public Good (DPG)** for monitoring rural drinking-water service delivery under India's **Jal Jeevan Mission (JJM)**. It closes a key operational gap — the daily monitoring of actual water supply at the field level — by combining:

* A **microservices** backend (Java 21 + Spring Boot) with a clear service-per-domain split
* **WhatsApp-driven field data collection** via Glific, so pump operators submit daily meter readings without a smartphone app
* **AI-assisted reading extraction** (FlowVision) that reads the meter value from a submitted photo
* **Automated nudge and escalation** notifications for missed readings
* **Public analytics dashboards** for programme officers at scheme, district, state, and national levels

> **Terminology:** role labels vary by state (e.g. Section Officer, AEE, EE). This documentation uses platform-level role names; states map their own designations during onboarding.

### 1.2 Scope

* **WhatsApp interactions** via Glific for operator data submission and notifications
* **Public dashboards** and a BI/analytics layer for compliance, water quantity, and scheme performance
* **Configuration services** for per-tenant (per-state) customisation
* **Field-operations tracking** — schemes, operators, readings, anomalies, escalations
* **Identity & access management** via Keycloak (OAuth2 / OIDC)

The platform is deployed as a **multi-tenant** system, with each state onboarded as an isolated tenant. New states can be onboarded without code changes or redeployment.

---

## 2. DPG Orientation

### 2.1 Openness & Reusability

* Open-source codebase hosted on a public repository
* RESTful APIs documented with OpenAPI (Swagger) at every service
* No proprietary infrastructure dependencies — reusable by any state and adaptable to other rural water-monitoring programmes

### 2.2 Cloud-neutrality

* Runs on any **Kubernetes** cluster — on-premises or any cloud provider
* **PostgreSQL** for operational and analytics data (no proprietary extensions)
* **Apache Kafka** in KRaft mode (no ZooKeeper) for asynchronous events
* **S3-compatible** object storage (MinIO or any cloud equivalent) for images and reports

### 2.3 Accessibility

* Operator-facing flow works over WhatsApp, requiring no app install or browser
* Multi-language support for notifications across Indian languages
* Dashboards designed against accessibility guidelines (WCAG 2.1 AA, GIGW 3.0)

### 2.4 Data Security & Privacy

* All PII (phone numbers, names) is **AES-256 encrypted at rest**, with HMAC hashes for lookup without decryption
* **JWT-based authentication** via Keycloak; role-based access control at every endpoint
* Phone numbers are never written to `INFO`/`WARN`/`ERROR` logs
* Tenant data is isolated at the database-schema level, preventing cross-tenant access
