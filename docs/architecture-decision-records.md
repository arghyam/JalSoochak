# Architecture Decision Records

This document records the key architectural decisions made during the design of JalSoochak V2, along with the context and rationale behind each decision.

---

## ADR-001: Schema-Per-Tenant Multi-Tenancy

**Status:** Accepted

**Context:**
JalSoochak is deployed for multiple Indian states. Each state needs full data isolation — a State Admin in Madhya Pradesh must not be able to see Uttar Pradesh's data. We evaluated three approaches:
* Separate database instance per tenant
* Shared database with `tenant_id` columns in all tables (row-level isolation)
* Shared database with one PostgreSQL schema per tenant (schema-level isolation)

**Decision:**
We chose **schema-per-tenant** using PostgreSQL's native schema feature. Each state gets a dedicated schema (e.g. `tenant_mp`, `tenant_up`) within the same database instance.

**Rationale:**
* Complete data isolation at the database level with no application-level filtering required
* Eliminates the risk of forgetting a `WHERE tenant_id = ?` clause in a query
* Simpler backup/restore per tenant if needed
* Lower operational overhead than separate instances — one database to manage
* PostgreSQL schema provisioning is fast and the provisioning function is idempotent

**Trade-offs:**
* Schema-scoped raw SQL queries (in `NudgeRepository`, `EscalationRepository`) require schema name injection with a strict allowlist validation to prevent SQL injection
* Cross-tenant analytics queries require combining data from multiple schemas — addressed by the separate analytics data warehouse which aggregates via Kafka events

---

## ADR-002: Apache Kafka for Asynchronous Events

**Status:** Accepted

**Context:**
Several workflows require decoupled communication between services:
* Meter readings submitted to `telemetry-service` need to update the analytics data warehouse
* Nudge and escalation triggers from `tenant-service` need to reach `message-service`
* User lifecycle events need to sync dimension tables in the analytics warehouse

Synchronous HTTP calls would create tight coupling, increase latency, and cause cascading failures if a downstream service is unavailable.

**Decision:**
Use **Apache Kafka in KRaft mode** (no Zookeeper) as the async event bus for all inter-service events.

**Rationale:**
* Decouples producers from consumers — `telemetry-service` does not need to know or care that `analytics-service` exists
* Provides at-least-once delivery with consumer group offsets for replay
* Dead-letter topics enable graceful handling of failed deliveries without blocking the main pipeline
* KRaft mode eliminates Zookeeper as a dependency, simplifying the operational footprint
* Topic-per-service pattern (e.g. `telemetry-service-topic`) gives clear ownership and enables fine-grained retention policies

**Trade-offs:**
* Eventual consistency between the operational database and the analytics warehouse — dashboard data may lag a few seconds behind operational data
* Requires additional infrastructure (Kafka cluster) compared to synchronous HTTP

---

## ADR-003: Keycloak for Identity and Access Management

**Status:** Accepted

**Context:**
The platform needs authentication for web staff (email + password), WhatsApp OTP authentication for field operators, JWT-based API authorization across multiple microservices, and multi-tenant role management.

**Decision:**
Use **Keycloak** as the identity provider for all authentication and JWT issuance.

**Rationale:**
* Battle-tested, open-source IAM — no vendor lock-in
* Supports custom JWT claims (`tenant_state_code`, `user_type`) via attribute mappers — enabling services to derive tenant and role from the token without a database call
* OAuth2/OIDC standard — all services act as resource servers using the standard Spring Security OAuth2 library
* Supports realm roles (`SUPER_USER`, `STATE_ADMIN`) alongside custom user attributes
* Self-hosted — data does not leave the deployment environment

**Trade-offs:**
* Keycloak adds operational complexity — it is another stateful service to manage and back up
* Custom attribute mappers must be configured correctly at setup time; misconfiguration can break authorization across all services

---

## ADR-004: Spring WebFlux for Message Service

**Status:** Accepted

**Context:**
The `message-service` makes outbound HTTP calls to three external APIs — Glific (WhatsApp), SendGrid (email), and SMSCountry (SMS) — for every notification event consumed from Kafka. Under peak load (morning nudge window), thousands of events may arrive in a short window. Blocking I/O would require a large thread pool.

**Decision:**
Use **Spring WebFlux (Project Reactor)** with non-blocking `WebClient` for all outbound HTTP calls in `message-service`.

**Rationale:**
* Non-blocking I/O allows a small number of threads to handle a large number of concurrent outbound requests
* `WebClient` supports automatic retry and back-off — important for Glific's rate-limiting (429) responses
* Reactive Mono/Flux composition makes it straightforward to pipeline: generate PDF → upload to MinIO → register media with Glific → send message
* Isolated to one service — other services continue to use standard Spring MVC without the reactive complexity

**Trade-offs:**
* Reactive code (Mono/Flux operators) is harder to read and debug than imperative code
* Developers unfamiliar with Project Reactor face a learning curve
* Error propagation and context logging require explicit handling in the reactive pipeline

---

## ADR-005: Separate PostgreSQL Instance for Analytics

**Status:** Accepted

**Context:**
Analytics queries — compliance rates, LPCD calculations, cross-state aggregations — involve large table scans and aggregations. Running these on the same PostgreSQL instance as the operational database risks degrading response times for field operations (meter reading submission, nudge queries).

**Decision:**
Run the analytics data warehouse on a **separate PostgreSQL instance** (port 5433) with a dedicated star schema.

**Rationale:**
* Complete I/O and query resource isolation — heavy BI scans cannot impact operational reads/writes
* The analytics schema can be tuned independently (connection pool sizes, work_mem, vacuum settings)
* Separation makes it safe to expose read-only analytics API access to a wider range of stakeholders without the risk of accidental writes to operational data
* Kafka-driven sync (via `analytics-service`) provides natural eventual consistency — the analytics warehouse is updated as events flow through

**Trade-offs:**
* Two PostgreSQL instances to operate and back up
* Analytics data lags operational data by the Kafka consumption latency (typically seconds)
* Cross-database joins are not possible — analytics queries can only use data that has been synced via Kafka events

---

## ADR-006: Glific for WhatsApp Integration

**Status:** Accepted

**Context:**
WhatsApp is the primary interface for field pump operators. Building a direct WhatsApp Business API integration requires compliance certification, ongoing meta-approval management, and handling message threading, template registration, and opt-in compliance.

**Decision:**
Use **Glific** as the WhatsApp integration layer, communicating via its GraphQL API.

**Rationale:**
* Glific handles WhatsApp template registration, compliance, and contact management
* Glific flows allow interactive multi-step conversations (photo submission, confirmation) without requiring JalSoochak to manage conversation state
* The `startContactFlow` GraphQL mutation allows triggering an interactive flow on demand (nudge)
* Glific manages operator opt-in and contact registration — JalSoochak only needs to store the resulting `contactId`
* Glific is itself an open-source platform aligned with DPG principles

**Trade-offs:**
* JalSoochak is dependent on Glific's availability for all operator communication
* Glific rate limits require client-side throttling (`glific.request-interval-ms`) and exponential back-off
* Session tokens require auto-refresh on 401 responses — handled by `GlificAuthService`

---

## ADR-007: Testcontainers for Integration Tests

**Status:** Accepted

**Context:**
Integration tests for repositories and services that interact with PostgreSQL need a real database. Mocking the database has caused past incidents where tests passed but production migrations revealed schema incompatibilities.

**Decision:**
Use **Testcontainers** to spin up a real PostgreSQL container for all integration tests. Never mock the database.

**Rationale:**
* Tests run against a real PostgreSQL engine — schema migrations, constraints, and query behavior are validated exactly as in production
* Testcontainers containers are ephemeral — each test run starts clean
* `@DynamicPropertySource` injects the container's connection URL, replacing the production datasource configuration
* `spring.flyway.enabled=false` in test `application.properties` — tests use a minimal `test-schema.sql` script rather than the full Flyway migration chain, keeping tests fast while still testing against a real database

**Trade-offs:**
* Tests require Docker to be running — not suitable for environments without Docker (addressed by CI/CD Docker-in-Docker)
* Container startup adds a few seconds to test run time compared to in-memory databases
* Test schema SQL must be maintained alongside Flyway migrations to stay in sync with schema changes
