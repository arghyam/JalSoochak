# Anomaly Service

**Port:** `8083` | **Module:** `backend/anomaly-service`

## Overview

The Anomaly Service detects and tracks **anomalies in submitted meter readings and supply data** — for example, missed submissions, implausible jumps in consumption, or prolonged outages. It consumes reading and submission events from Kafka, evaluates them against anomaly rules, persists detected anomalies, and exposes a query API used by dashboards and the escalation pipeline.

The anomalies surfaced here feed the analytics dashboards and the nudge / escalation notification flow (see [Anomaly Flows](anomaly-flows.md) and [Escalation System](escalation-system.md)).

---

## Architecture

```
anomaly-service
├── ApiController                 – REST query endpoints under /api/v1
├── AnomalyIngestService          – Evaluate incoming events and record anomalies
├── BusinessService(Impl)         – Anomaly evaluation rules
├── AnalyticsDimensionSyncService – Keep local dimension data in sync from events
├── AnomalyRepository             – JPA for the anomaly table
├── Anomaly / AnomalyEvent        – Entity and Kafka event payload
├── KafkaConsumer / KafkaProducer – Consume input events; publish anomaly-service-topic
└── config/
    ├── SecurityConfig            – JWT resource server (all routes authenticated)
    ├── JwtAuthConverter          – Extracts ROLE_*, TENANT_*, USER_TYPE_* from JWT
    ├── KafkaConfig               – Consumer/producer setup
    └── DataSourceConfig          – Datasource configuration
```

---

## REST API

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/v1/anomalies` | Authenticated (JWT) | List detected anomalies with filters |
| `POST` | `/api/v1/publish` | Authenticated (JWT) | Publish an anomaly event to Kafka (internal / admin use) |

All routes other than `/actuator/health`, `/actuator/info`, and `/error` require a valid JWT.

---

## Kafka

| Direction | Topic | Notes |
|-----------|-------|-------|
| Consumes | `common-topic` (consumer group `anomaly-service-group`) | Reading / submission events used for anomaly evaluation |
| Produces | `anomaly-service-topic` | `AnomalyEvent` records consumed by `analytics-service` |

---

## Configuration

```yaml
server:
  port: 8083

spring:
  application:
    name: anomaly-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  kafka:
    consumer:
      group-id: ${SPRING_KAFKA_CONSUMER_GROUP_ID:anomaly-service-group}

eureka:
  client:
    enabled: ${EUREKA_ENABLED:true}
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
```

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| Spring Web | REST controllers |
| Spring Data JPA | Anomaly persistence |
| Spring Kafka | Event consumption and `AnomalyEvent` publishing |
| Spring Security + OAuth2 Resource Server | JWT authentication |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |

---

## Related

- [Anomaly Flows](anomaly-flows.md) — how anomalies are detected and surfaced
- [Escalation System](escalation-system.md) — how anomalies drive officer escalations
- [Analytics Service](analytics-service.md) — consumes `anomaly-service-topic`
