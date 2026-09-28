# Technology Stack

## 7. Technology Stack

### 7.1 Backend

* **Java 21** (Eclipse Temurin)
* **Spring Boot 3.2.5** — multi-module microservices (Spring Web MVC; reactive `WebClient` for outbound calls in the message service)
* **Spring Security + OAuth2 Resource Server** — Keycloak JWT validation and method-level authorization
* **Spring Data JPA / Hibernate** for persistence, with **Flyway** migrations
* **Spring Cloud 2023.0.1** — Netflix Eureka (discovery) and Spring Cloud Gateway (edge)
* **Spring Kafka** for event publishing and consumption
* **Apache PDFBox** (report PDFs), **Apache POI** (Excel uploads and reports), **AWS SDK S3** client (object storage)
* **Maven** build; packaged as **Docker** images

### 7.2 Frontend

* **React 18 + TypeScript**, built with **Vite**
* **ECharts** for dashboards and visualisations
* **react-i18next** for multi-language support
* **Zustand** for state, **React Router** for routing, **Axios** for HTTP
* Served as static assets via CDN / Nginx on Kubernetes

### 7.3 Database & Messaging

* **PostgreSQL** — operational database (schema-per-tenant) and a separate analytics data warehouse (with **PostGIS**)
* **Redis** — caching and API-gateway rate limiting
* **Apache Kafka** (KRaft mode, no ZooKeeper) — asynchronous event bus

### 7.4 Documentation & Repository

* **Mono-repo** with a directory per service under `backend/` (the frontend app is not part of this repository)
* **GitBook** documentation (this site), with OpenAPI / Swagger generated per service
* **External integrations:** Glific (WhatsApp), SendGrid or SMTP (email), SMS provider, MinIO / S3 (object storage), FlowVision (meter-image AI)
