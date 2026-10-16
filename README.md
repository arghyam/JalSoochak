# **JalSoochak**

### Strengthening Rural Drinking Water Service Delivery Through Digital Intelligence

JalSoochak is a configurable digital platform designed to enable **structured data capture, intelligent analysis, and actionable insights** for rural drinking water service delivery. It supports the transition from infrastructure creation to **sustained, reliable service monitoring** at scale.

---

## Background

With the scale achieved under the **Jal Jeevan Mission (JJM)**, the sector is now focused on ensuring **continuous functionality of water supply systems**. JalSoochak addresses key challenges such as:

* Fragmented data collection
* Limited visibility into scheme performance
* Delayed detection of service issues
* Lack of actionable insights for frontline institutions


## What JalSoochak Enables

* **Structured operational data capture** from field-level actors
* **Multi-channel ingestion** (WhatsApp, mobile, web, IoT)
* **Early warning signals** for service disruptions
* **Role-based dashboards** for decision-makers
* **Traceability and auditability** of all observations

## Core Capabilities

### Multi-Tenant

Supports multiple states and administrative units with clear data isolation and governance.

### Multi-Channel

Enables data capture via:

* Bulk Flow Meters (BFM)
* Electricity consumption
* Pump runtime
* Manual reporting
* IoT integrations

### Multi-Lingual

Designed for use across diverse linguistic contexts and supports 15 Indian Languages

### Multi-Deployment

Flexible deployment across cloud, on-premise, or hybrid environments.

### Modular & Extensible

Built as a modular system allowing replacement or extension of core services.

### AI-Enabled Insights

Supports anomaly detection, pattern recognition, and predictive signals.

## Architecture Overview

JalSoochak follows a **microservices-based architecture** with loosely coupled components:

* **Tenant Service**
* **User Service**
* **Telemetry Service**
* **Analytics Service**
* **Anomaly Service**
* **Scheme Service**
* **Message Service**

The platform is designed to be **event-driven**, with observations as the core data primitive.

---

## Key APIs

### Submit a Meter Reading

Field operators submit over a WhatsApp chatbot flow. State IT systems submit through the partner
ingestion API, authenticated by a per-tenant `X-Api-Key`:

```http
POST /api/v1/telemetry/readings
```

Supported reading channels:

* `BFM` — bulk flow meter
* `ELM` — electricity meter
* `PDU` — pump run duration
* `MAN` — manual
* `IOT` — IoT device

The full endpoint list is in [`backend/API_ENDPOINTS.md`](backend/API_ENDPOINTS.md).

---

## Data Model (Simplified)

* **Tenants** – States
* **Users** – Role-based access
* **Schemes** – RPWSS-linked entities
* **Observations** – Immutable field data
* **Alerts** – Derived system signals

---

## Getting Started

### Prerequisites

* Java 21 and Maven 3.9+
* PostgreSQL 16
* Apache Kafka (KRaft mode)
* Redis
* Docker

### Setup

```bash
git clone https://github.com/arghyam/Jalsoochak-V2.git
cd Jalsoochak-V2
```

### Configuration

Each service reads `src/main/resources/application.yml`, overridable by environment variables such
as `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`.

### Run

Each service under `backend/` is a separate Maven project:

```bash
cd backend/<service-name>
mvn spring-boot:run
```

See [`backend/README.md`](backend/README.md) for infrastructure setup and service ports.

---

## Configuration

JalSoochak is highly configurable:

* Tenant-specific settings
* Channel enablement
* Notification rules
* Data validation rules

---

## Security

* JWT-based authentication
* Role-based access control (RBAC)
* Tenant isolation
* Audit logging

---

## Integration

JalSoochak is designed to integrate with:

* Drinking Water DPIs
* Messaging platforms (WhatsApp)
* IoT devices
* External analytics tools

---

## Use Cases

* Monitoring scheme functionality
* Detecting service disruptions
* Supporting O&M decision-making
* Enabling state and national dashboards

---

## Contributing

We welcome contributions from the community.

Please:

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

---

## License

MIT

---

## About Arghyam

JalSoochak is developed by **Arghyam**, a public charitable trust working on water and sanitation in India, in partnership with government and ecosystem stakeholders.

---

## Contact

For queries or collaboration:

📧 [Info@arghyam.org]
🌐 [https://www.jalsoochak.in]

---


