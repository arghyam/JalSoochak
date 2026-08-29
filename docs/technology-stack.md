# Technology Stack

## 1. Backend

| Layer | Technology | Version |
|-------|-----------|---------|
| Runtime | Java (Eclipse Temurin) | 21 |
| Framework | Spring Boot | 3.2.5 |
| Build | Maven | 3.9+ |
| API (most services) | Spring Web MVC (REST) | — |
| API (message-service) | Spring WebFlux (reactive, non-blocking) | — |
| Security | Spring Security + OAuth2 Resource Server | — |
| Identity Provider | Keycloak (JWT validation) | 23+ |
| Persistence | Spring Data JPA + Hibernate | — |
| Database Migrations | Flyway | — |
| Messaging | Apache Kafka (KRaft mode, no Zookeeper) | 3.6+ |
| Service Discovery | Netflix Eureka (Spring Cloud) | — |
| Caching | Redis (Spring Cache) | 7 |
| Object Storage | AWS SDK v2 (S3 / MinIO-compatible) | — |
| PDF Generation | Apache PDFBox | — |
| Schema-bound Queries | Spring JDBC (`JdbcTemplate`) | — |
| CSV Parsing | OpenCSV | — |
| XLSX Parsing | Apache POI | — |
| API Documentation | springdoc-openapi (Swagger UI) | — |
| Lombok | Lombok | — |

---

## 2. Frontend

| Layer | Technology | Version |
|-------|-----------|---------|
| Runtime | Node.js | LTS |
| Framework | React | 18 |
| Language | TypeScript | — |
| Build Tool | Vite | — |
| UI Library | Chakra UI | v2 |
| State Management | Zustand | — |
| Server State | TanStack Query (React Query) | — |
| Routing | React Router | v6 |
| Internationalisation | i18next + react-i18next | — |
| HTTP Client | Axios | — |
| Charts | Recharts | — |

**Supported languages (15):** Hindi, Bengali, Telugu, Marathi, Tamil, Gujarati, Kannada, Malayalam, Odia, Punjabi, Assamese, Urdu, Maithili, Sanskrit, English.

---

## 3. Infrastructure

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Container Orchestration | Kubernetes | Deploy and scale all microservices |
| Operational Database | PostgreSQL 16 | Schema-per-tenant operational data |
| Analytics Database | PostgreSQL 16 (separate instance) | Star-schema data warehouse |
| Message Broker | Apache Kafka 3.6+ (KRaft) | Async event bus between services |
| Cache | Redis 7 | Session caching, token storage |
| Object Storage | MinIO (S3-compatible) | Escalation PDF storage, tenant assets |
| Identity Provider | Keycloak 23+ | Authentication, JWT issuance, RBAC |
| Service Registry | Netflix Eureka | Service discovery and load balancing |
| AI / OCR | FlowVision | Meter image reading extraction |
| WhatsApp | Glific (GraphQL API) | WhatsApp message delivery and flows |
| Email | SendGrid | Transactional email (invitations, password reset) |
| SMS | SMSCountry | SMS fallback delivery channel |

---

## 4. Testing

| Type | Framework | Use Case |
|------|-----------|---------|
| Unit tests | Mockito + JUnit 5 | Business logic in isolation; no Spring context |
| Integration tests (DB) | Testcontainers + `@SpringBootTest` | Real PostgreSQL container; JPA/SQL correctness |
| Integration tests (HTTP) | WireMock (Spring Cloud Contract) | Stub Glific, SendGrid, and other external APIs |
| Controller tests | MockMvc | REST endpoint routing and authorization |

---

## 5. Developer Tooling

| Tool | Purpose |
|------|---------|
| Docker + Docker Compose | Local infrastructure (PostgreSQL, Kafka, Redis, MinIO, Keycloak) |
| Maven Wrapper | Consistent Maven version across developer machines |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` per service |
| Spring Boot Actuator | Health checks (`/actuator/health`) and Prometheus metrics (`/actuator/prometheus`) |
