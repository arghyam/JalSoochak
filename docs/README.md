# JalSoochak V2

**JalSoochak** ("water informer" in Hindi) is a multi-tenant water management platform built for rural drinking water service delivery monitoring under India's **Jal Jeevan Mission (JJM)**.

It tracks daily meter readings from field pump operators across Indian states, triggers automated WhatsApp notifications for missed readings, and provides analytics dashboards for programme officers at scheme, district, state, and national levels.

---

## Documentation Overview

| Section | Description |
|---------|-------------|
| [Introduction](introduction.md) | Purpose, scope, and DPG orientation |
| [Users & Tenancy](users-tenancy.md) | Multi-tenant model, user roles, and tenant lifecycle |
| [Functional Overview](functional-overview.md) | Core system modules and workflows |
| [Technical Architecture](technical-architecture.md) | Microservices design, communication patterns, and security |
| [Deployment Architecture](deployment-architecture.md) | Infrastructure, environments, and Kubernetes setup |
| [Technology Stack](technology-stack.md) | Languages, frameworks, and infrastructure components |
| [Architecture Decision Records](architecture-decision-records.md) | Key design decisions and rationale |
| [API Specifications](api-specifications.md) | REST API design and per-service endpoint reference |
| [Database Design](database-design.md) | Multi-tenant schema and analytics data warehouse |
| [NFRs](nfrs.md) | Non-functional requirements: security, reliability, performance |

### Backend Service Reference

| Service | Port | Description |
|---------|------|-------------|
| [Service Discovery](service-discovery.md) | 8761 | Netflix Eureka service registry |
| [Tenant Service](tenant-service.md) | 8081 | Tenant onboarding, configuration, nudge/escalation schedulers |
| [User Service](user-service.md) | 8082 | Authentication, user lifecycle, PII encryption |
| [Telemetry Service](telemetry-service.md) | 8989 | Meter reading ingestion, FlowVision AI |
| [Scheme Service](scheme-service.md) | 8287 | Water supply scheme management |
| [Message Service](message-service.md) | 8085 | WhatsApp, email, and SMS notification delivery |
| [Analytics Service](analytics-service.md) | 8087 | Data warehouse and BI query APIs |
| [Notification Flows](notification-flows.md) | — | Detailed WhatsApp notification pipeline |
