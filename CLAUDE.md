# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JalSoochak V2 is a water management platform built as a multi-module Spring Boot microservices system using Java 21. The backend serves multi-tenant water utility organizations across Indian states.

## Prerequisites

- Java 21 (Eclipse Temurin)
- Maven 3.9+
- Docker & Docker Compose
- PostgreSQL 16
- Apache Kafka (KRaft mode preferred)

## Common Commands

### Build

> **Important:** If your system Maven uses a JDK newer than 21 (e.g. Homebrew OpenJDK 25),
> Lombok annotation processing will fail with `TypeTag :: UNKNOWN`. Always point Maven at Java 21
> by setting `JAVA_HOME` to your local JDK 21 path and adding it to your shell profile.

```bash
# Build a single service (skip tests)
cd backend/<service-name>
mvn clean package -DskipTests

# Run a service locally
mvn spring-boot:run

# Run via JAR
java -jar target/<service-name>-*.jar
```

### Tests

```bash
# Run tests for a service
mvn test -pl backend/<service-name>

# Run a single test class
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName
```

### Infrastructure Setup

```bash
# Start PostgreSQL
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_DB=jalsoochak_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=password@1123 \
  postgres:16-alpine

# Start Kafka (KRaft mode)
# Follow backend/README.md for Kafka setup steps
```

### Service Startup Order

Services must start in this order:
1. PostgreSQL (database)
2. Kafka broker
3. `service-discovery` (Eureka, port 8761) — all other services register here
4. Remaining services (any order): `tenant-service` (8081), `user-service` (8082), `anomaly-service` (8083), `telemetry-service` (8084), `message-service` (8085), `scheme-service` (8086)

## Architecture

### Multi-Tenancy Model

The system uses **schema-per-tenant** multi-tenancy in PostgreSQL:
- `common_schema` — shared tenant metadata, admin users, and LGD (Local Government Directory) location types
- `tenant_<state_code>` — dynamically created per tenant (e.g., `tenant_mp`, `tenant_up`) via a PL/pgSQL function in `database/V2__create_tenant_schema_function.sql`

Flyway migrations run automatically on service startup. Migration files live in `backend/database/`.

### Single Tenant Mode (`SINGLE_TENANT_MODE`)

Defaults to `false`. Bound as `app.single-tenant-mode` in tenant/user/scheme, but
`analytics.single-tenant-mode` in analytics — same env var, different prefix.

When on: user/scheme `JwtAuthConverter` expands `SUPER_STATE_ADMIN` → `SUPER_USER` +
`STATE_ADMIN`; `createTenant` refuses a second tenant; analytics national endpoints return 403.

**Startup invariant:** tenant/user/scheme each refuse to boot if more than one tenant is ACTIVE
(`status = 3`), or if the tenant table is unreadable — see `SingleTenantModeStartupValidator` in
each `config/` package. All three check independently, since login and the role expansion live
outside tenant-service. DEGRADED tenants are loginable but only WARN. A `@SpringBootTest` with the
flag on needs ≤1 ACTIVE tenant or `@MockBean` on the validator.

### Service Communication

- **Synchronous:** HTTP/REST via Netflix Eureka service discovery
- **Asynchronous:** Apache Kafka — each service produces to its own topic (`<service-name>-topic`) and consumes from `common-topic`

### Standard Package Layout (per service)

```text
src/main/java/com/example/<service>/
├── config/        # Spring configuration beans
├── controller/    # REST endpoints
├── service/       # Business logic
├── repository/    # Spring Data JPA repositories
├── entity/        # JPA entities
├── dto/           # Request/response DTOs
├── exception/     # Custom exceptions
└── kafka/         # Kafka producers and consumers
```

### Message Service

The `message-service` (port 8085) is distinct from others: it uses **Spring WebFlux** (non-blocking) to call external notification APIs. It supports three channels configured in `application.yml`:
- Webhook (generic)
- Email via SendGrid
- WhatsApp via Gliffic API

### Configuration

All services use `src/main/resources/application.yml`. Key environment variables that override defaults:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `EUREKA_ENABLED` — set to `false` to run a service standalone
- `EUREKA_URL` — defaults to `http://localhost:8761/eureka/`

Note: Several `application.yml` files contain hardcoded credentials (development defaults). Use environment variables for any non-local deployment.

## Notification Pipeline (nudge-escalation)

Cron jobs in `tenant-service` publish Kafka events; `message-service` consumes and delivers via WhatsApp.

### Flow
- 8 AM cron (`NudgeSchedulerService`): operators with no upload today → `NUDGE` Kafka event
- 9 AM cron (`EscalationSchedulerService`): operators with missed days ≥ threshold → `ESCALATION` Kafka event
- `message-service` (`NotificationEventRouter`): routes events → WhatsApp via Glific GraphQL HSM API

### Language resolution
Message text is fetched from `common_schema.tenant_config_master_table` using the pattern from
`telemetry-service/GlificWebhookService`:
- `user_table.language_id` (int) → `language_N` config key → language name → normalized key
- Template keys: `nudge_message_{langKey}`, `escalation_message_{langKey}` (fallback: `_english` → generic)
- Add per-tenant rows in `tenant_config_master_table` with these keys before running.

### MinIO + Glific
- Escalation PDFs are generated locally (PDFBox), uploaded to MinIO, then the MinIO URL is registered
  with Glific via `createMessageMedia` to get a `mediaId`
- Glific template for nudge: uses `sendHsmMessage`; body `{{1}}` = operator name, `{{2}}` = date
- Glific template for escalation (two-step):
  1. `createMessageMedia(url, source_url)` → `mediaId`
  2. `createAndSendMessage(templateId, mediaId, receiverId, parameters=[bodyText])` — the document
     header attachment is provided via `mediaId`; the body parameter is the localized text
- Required env vars: `GLIFIC_API_URL`, `GLIFIC_API_KEY`, `GLIFIC_NUDGE_TEMPLATE_ID`,
  `GLIFIC_ESCALATION_TEMPLATE_ID`, `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`,
  `MINIO_BUCKET`, `MINIO_BASE_URL`

### Daily report delivery mode (DOCUMENT | LINK)

`NOTIFICATIONS_DAILY_REPORT_DELIVERY_MODE` — both paths in
`GlificWhatsAppService.sendDailyReportHsm`. `minio.base-url` must be public and anonymously
readable either way.

- `DOCUMENT` (default) — two-step media send. Meta fetches `minio.base-url` itself, which the
  India-only firewall in front of production MinIO blocks.
- `LINK` — one `sendHsmMessage` with a "View Report" button, no media step. `parameters =
[officerName, reportDate (dd-MM-yyyy), urlSuffix]` — the URL suffix must come **last**. The
  button's prefix is frozen at Meta approval and must equal `minio.base-url` + `/`, so each
  environment needs its own approved template (`GLIFIC_DAILY_REPORT_SO_LINK_TEMPLATE_ID`); set
  `DAILY_REPORT_LINK_BUTTON_BASE_URL` to have that checked at startup.

### Privacy rule
Phone numbers are PII — log them only at `DEBUG` level. Never include raw phone numbers in
`INFO`/`WARN`/`ERROR` log statements.

## Testing Approach

**Always follow Test-Driven Development (TDD)** for all new code in this repository.

### Rules
1. **Write tests before or alongside implementation** — no production code ships without a corresponding test.
2. **Integration tests use Testcontainers** — use `org.testcontainers:postgresql` for real database assertions;
   never mock the database in integration tests.
3. **Unit tests use Mockito only** — no Spring context for pure business-logic tests
   (`@ExtendWith(MockitoExtension.class)`); fast and side-effect-free.
4. **External HTTP APIs use WireMock** — use `spring-cloud-contract-wiremock` to stub Glific and any other
   HTTP dependencies; never call real external services in tests.
5. **Disable infrastructure in test application.properties** — set `spring.flyway.enabled=false`,
   `eureka.client.enabled=false`, `spring.kafka.admin.fail-fast=false` and override datasource via
   `@DynamicPropertySource` from the Testcontainer.
6. **Suppress `@PostConstruct` side effects** — use `@MockBean` for beans that perform network calls on
   startup (e.g. `GlificAuthService`) in tests that don't exercise those beans.
7. **Privacy in tests** — test phone numbers must use the `91XXXXXXXXXX` format but must not be real numbers;
   do not log them at INFO level even in test helpers.

### Test structure per service
```text
src/test/
├── java/com/example/<service>/
│   ├── repository/    # @SpringBootTest + Testcontainers (JdbcTemplate/SQL queries)
│   ├── service/       # Mockito unit tests + Testcontainers where real DB needed
│   └── channel/       # Mockito unit tests + WireMock for HTTP clients
└── resources/
    ├── application.properties   # overrides that disable external dependencies
    └── sql/test-schema.sql      # minimal schema for Testcontainer init script
```

### Running tests
```bash
# Run all tests for a service (requires Docker for Testcontainers)
cd backend/<service-name>
mvn test

# Run a single test class
mvn test -Dtest=NudgeRepositoryIntegrationTest
```
