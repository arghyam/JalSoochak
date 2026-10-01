# API Gateway

**Port:** `8080` | **Module:** `backend/api-gateway`

## Overview

The API Gateway is the **single public entry point** for the JalSoochak V2 backend. Every request from the React frontend (and any external client) goes through the gateway, which:

- Terminates the public HTTPS edge and validates the Keycloak-issued JWT
- Rejects unauthenticated requests (`401`) before they reach any downstream service
- Routes the request to the correct microservice based on a path prefix

It is built on **Spring Cloud Gateway** (reactive, WebFlux-based) and acts as an OAuth2 resource server.

{% hint style="info" %}
The gateway validates the JWT **signature and expiry** only. Fine-grained role checks (`@PreAuthorize`) are enforced again inside each downstream service, which re-validates the same token. Authorization is never delegated solely to the gateway.
{% endhint %}

---

## Routing

The gateway maps a public path prefix to each backend service. The service prefix is stripped before forwarding (so `/tenant/api/v1/tenants` reaches tenant-service as `/api/v1/tenants`):

| Public Path Prefix | Routed To | Default Target |
|--------------------|-----------|----------------|
| `/api/v1/pumpoperator/**` | user-service | `:8082` (public) |
| `/tenant/**` | tenant-service | `:8081` |
| `/user/**` | user-service | `:8082` |
| `/anomaly/**` | anomaly-service | `:8083` |
| `/telemetry/**` | telemetry-service | `:8989` |
| `/message/**` | message-service | `:8085` |
| `/scheme/**` | scheme-service | `:8086` |
| `/analytics/**` | analytics-service | `:8087` |

Target URIs are overridable per environment via env vars (`TENANT_SERVICE_URI`, `USER_SERVICE_URI`, `TELEMETRY_SERVICE_URI`, …). In clustered deployments these point at the in-cluster service addresses.

---

## Security

Built with `@EnableWebFluxSecurity` and a `SecurityWebFilterChain`. CSRF is disabled (stateless, token-based), and all exchanges require authentication except the public routes below.

**Public routes (no JWT required):**

```
/user/api/v1/auth/login
/user/api/v1/auth/refresh
/user/api/v1/auth/logout
/user/api/v1/auth/invite/info
/user/api/v1/auth/activate-account
/user/api/v1/auth/forgot-password
/user/api/v1/auth/reset-password
/user/api/v1/public/**
/api/v1/pumpoperator/**
/actuator/health
/actuator/info
/actuator/prometheus
```

All other routes require a valid, non-expired Keycloak JWT.

---

## Configuration

```yaml
server:
  port: 8080

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:https://jalsoochak.beehyv.com/keycloak/realms/jalsoochak-realm}
  cloud:
    gateway:
      routes:
        - id: tenant-service
          uri: ${TENANT_SERVICE_URI:http://localhost:8081}
          predicates:
            - Path=/tenant/**
          filters:
            - StripPrefix=1
        # ... user, anomaly, telemetry, message, scheme, analytics
```

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| Spring Cloud Gateway | Reactive routing and filtering |
| Spring Security + OAuth2 Resource Server | JWT validation at the edge |
| Netflix Eureka Client | Service registration / discovery |
| Spring Boot Actuator | Health, info, and Prometheus endpoints |

---

## Related

- [Service Discovery](service-discovery.md) — how services register and are discovered
- [Technical Architecture](technical-architecture.md) — the full request path and security model
- [Users & Tenancy](users-tenancy.md) — JWT claims and role model enforced downstream
