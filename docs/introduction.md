# Introduction

## 1. Purpose

**JalSoochak** ("water informer" in Hindi) is an open-source, multi-tenant water management platform built to support India's **Jal Jeevan Mission (JJM)** — the central government programme that aims to provide safe drinking water to every rural household through individual tap connections (Functional Household Tap Connections, or FHTCs).

The platform addresses a key operational gap: daily monitoring of actual water supply at the field level. Pump operators stationed at village-level water supply schemes are responsible for recording meter readings every day, but ensuring consistent data collection across thousands of remote locations is a significant challenge.

JalSoochak solves this through:

* **WhatsApp-driven field data collection** — pump operators submit daily flow meter readings by sending a photo through a WhatsApp flow, without needing a smartphone app or internet browser.
* **AI-assisted reading extraction** — FlowVision AI reads the meter value from the submitted photo, reducing manual entry errors.
* **Automated nudge and escalation notifications** — operators who miss a reading receive an automated WhatsApp reminder; persistent non-submission escalates to their section officer and district officer with a PDF report.
* **State and national analytics dashboards** — programme officers at every level can track compliance, water quantity, and scheme performance in real time.

---

## 2. Scope

### 2.1 What JalSoochak Covers

* Daily BFM (Bulk Flow Meter) reading submission via WhatsApp and web dashboard
* Automated nudge and escalation notification pipeline
* Multi-tenant onboarding and configuration for state-level deployments
* User management: pump operators, section officers, district officers, state admins
* Scheme and location hierarchy management (LGD and departmental)
* Analytics: compliance rates, LPCD (litres per capita per day), scheme performance, escalation history
* Multi-language support (15 Indian languages) for operator-facing notifications

### 2.2 Out of Scope (Current Release)

* Native mobile application
* Real-time IoT sensor integration (mock adapters provided)
* Billing or payment processing
* Integration with state government legacy systems (handled separately via bulk CSV upload)

---

## 3. DPG Orientation

JalSoochak V2 is designed as a **Digital Public Good (DPG)** — reusable by multiple states and, with adaptation, by other countries implementing similar rural water monitoring programmes.

### 3.1 Openness and Reusability

* Source code is open-source and hosted on GitHub
* RESTful APIs with OpenAPI (Swagger) documentation at every service
* No proprietary infrastructure dependencies — runs on any Kubernetes cluster, any S3-compatible object store, and any PostgreSQL-compatible database

### 3.2 Multi-Tenant Architecture

Each state operates as an isolated tenant with its own:

* PostgreSQL schema (`tenant_<stateCode>`) — complete data isolation at the database level
* Configuration (languages, water norms, escalation thresholds, cron schedules)
* User hierarchy (operators, section officers, district officers, state admin)
* Analytics data within the shared analytics data warehouse

New states can be onboarded without any code changes or redeployment.

### 3.3 Cloud Neutrality

* Kubernetes-based deployment (on-prem or any cloud provider)
* S3-compatible object storage (MinIO or AWS S3)
* Standard PostgreSQL — no proprietary database extensions required
* Kafka in KRaft mode — no Zookeeper dependency

### 3.4 Security and Privacy

* All PII (phone numbers, user names) is AES-256 encrypted at rest
* JWT-based authentication via Keycloak (standard OAuth2/OIDC)
* Role-based access control (RBAC) enforced at every API endpoint
* Phone numbers never appear in INFO/WARN/ERROR logs

---

## 4. Key Stakeholders

| Role | Description |
|------|-------------|
| **Pump Operator** | Field worker who submits daily meter readings via WhatsApp |
| **Section Officer** | Supervises pump operators in a section; receives Level 1 escalation alerts |
| **District Officer** | Oversees a district; receives Level 2 escalation alerts |
| **State Admin** | Manages configuration, staff, schemes, and analytics for a state tenant |
| **Super User** | Platform-level admin; creates and manages state tenants |
| **National Officer** | Views cross-state analytics on the national dashboard (read-only) |
