# Technology Stack

## 7. Technology Stack

### 7.1 Backend

* **Java 21** (Eclipse Temurin)
* **Spring Boot 3.2.5** — multi-module microservices (Spring Web MVC; reactive `WebClient` for outbound calls in the message service)
* **Spring Security + OAuth2 Resource Server** — Keycloak JWT validation and method-level authorization
* **Spring Data JPA / Hibernate** for persistence
* **Spring Cloud** — Netflix Eureka (discovery) and Spring Cloud Gateway (edge)
* **Maven** build; packaged as **Docker** images

### 7.2 Frontend

* **React 18 + TypeScript**, built with **Vite**
* **ECharts** for dashboards and visualisations
* **react-i18next** for multi-language support
* **Zustand** for state, **React Router** for routing, **Axios** for HTTP
* Served as static assets via CDN / Nginx on Kubernetes

### 7.3 Database & Messaging

* **PostgreSQL** — operational database (schema-per-tenant) and a separate analytics data warehouse
* **Redis** — caching and token storage
* **Apache Kafka** (KRaft mode, no ZooKeeper) — asynchronous event bus

### 7.4 Documentation & Repository

* **Mono-repo** with a directory per service under `backend/` plus the `frontend/` app
* **GitBook** documentation (this site), with OpenAPI / Swagger generated per service
* **External integrations:** Glific (WhatsApp), SendGrid (email), SMS provider, MinIO / S3 (object storage), FlowVision (meter-image AI)
