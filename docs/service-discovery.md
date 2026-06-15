# Service Discovery

**Port:** `8761` | **Module:** `backend/service-discovery`

{% hint style="warning" %}
**Known configuration mismatch:** the committed `service-discovery/application.yml` sets `server.port: 8762`, while every client service defaults its Eureka `defaultZone` to `:8761`. In deployments this is reconciled via the `EUREKA_URL` env var; for clean local startup, align the two (set the server back to `8761` or point clients at `8762`). This page uses `8761` to match the client defaults. See [Future Work](future-work.md#2-configuration-consistency).
{% endhint %}

## Overview

The Service Discovery module is a **Netflix Eureka Server** — the service registry for the JalSoochak V2 backend. All microservices register themselves with Eureka on startup and discover each other by logical name rather than hardcoded hostnames or port numbers.

This enables:

- **Location transparency** — services call each other by name (e.g. `http://user-service/api/...`) without knowing their actual host or port
- **Load balancing** — Spring Cloud LoadBalancer distributes requests across multiple instances of the same service
- **Health monitoring** — Eureka tracks service heartbeats and automatically removes unhealthy instances from the registry
- **Zero-config inter-service discovery** — no DNS configuration or reverse proxy needed for local development or simple deployments

---

## Registered Services

All JalSoochak V2 backend services register with this Eureka instance on startup:

| Service Name | Port | Eureka Registry Name |
|-------------|------|---------------------|
| tenant-service | 8081 | `TENANT-SERVICE` |
| user-service | 8082 | `USER-SERVICE` |
| telemetry-service | 8989 | `TELEMETRY-SERVICE` |
| scheme-service | 8086 | `SCHEME-SERVICE` |
| message-service | 8085 | `MESSAGE-SERVICE` |
| analytics-service | 8087 | `ANALYTICS-SERVICE` |
| anomaly-service | 8083 | `ANOMALY-SERVICE` |

---

## Startup Order

{% hint style="warning" %}
Service Discovery **must be fully started and healthy** before any other service is launched. All services depend on it for registration and discovery.
{% endhint %}

Recommended startup sequence:

```
1. PostgreSQL (operational and analytics instances)
2. Apache Kafka (KRaft cluster)
3. Redis
4. service-discovery        ← wait until port 8761 is accepting connections
5. tenant-service           ← wait until Flyway migrations complete
6. All remaining services   ← any order
```

---

## Dashboard

The Eureka web dashboard is available at:

```
http://<host>:8761
```

The dashboard shows:
- All registered service instances with their status (`UP` / `DOWN` / `OUT_OF_SERVICE`)
- Last heartbeat timestamp per instance
- Instance metadata (host, port, health check URL)

---

## Health Check

```
GET http://<host>:8761/actuator/health
```

---

## Running a Service Without Eureka

To run any service in standalone mode (useful for debugging a single service in isolation), set the following environment variable:

```bash
EUREKA_ENABLED=false
```

The service will start without attempting to register or discover other services via Eureka.

---

## Configuration

**Service Discovery (`application.yml`):**

```yaml
server:
  port: 8761

spring:
  application:
    name: service-discovery

eureka:
  instance:
    hostname: localhost
  client:
    register-with-eureka: false   # Server does not register itself
    fetch-registry: false          # Server does not fetch from itself
    service-url:
      defaultZone: http://localhost:8761/eureka/
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
```

**Client configuration on all other services:**

```yaml
eureka:
  client:
    enabled: ${EUREKA_ENABLED:true}
    fetch-registry: true
    register-with-eureka: true
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
```

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `spring-cloud-starter-netflix-eureka-server` | Eureka server implementation |
| Spring Boot Actuator | Health and info endpoints |
