# API Specifications

## 1. API Design Principles

* **RESTful** — standard HTTP methods (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`) with JSON request/response bodies
* **Versioned** — all endpoints are prefixed with `/api/v1/`
* **JWT-authenticated** — every endpoint (except public ones) requires a `Bearer <token>` header; tokens are issued by Keycloak
* **Role-enforced** — each endpoint declares minimum required roles; the JWT's `realm_access.roles`, `tenant_state_code`, and `user_type` claims are used for authorization
* **Paginated** — list endpoints accept `page` (0-indexed) and `size` parameters
* **Auto-documented** — every service exposes an interactive Swagger UI at runtime

### 1.1 Swagger / OpenAPI

Each service generates live API documentation automatically:

```
Swagger UI:   http://<host>:<port>/swagger-ui/index.html
OpenAPI JSON: http://<host>:<port>/v3/api-docs
```

{% hint style="info" %}
Swagger documentation is auto-generated from the code and annotations. It updates automatically on every deployment and requires no manual maintenance.
{% endhint %}

### 1.2 Authentication Header

```
Authorization: Bearer <JWT access token>
```

Tokens are obtained via `/api/v1/auth/login` (email + password) or `/api/v1/auth/staff/otp/verify` (WhatsApp OTP).

---

## 2. Tenant Service APIs (`:8081`)

### Tenant Management

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/tenants` | `SUPER_USER` | Create a new tenant and provision its PostgreSQL schema |
| `GET` | `/api/v1/tenants` | Authenticated | List all tenants |
| `GET` | `/api/v1/tenants/summary` | Authenticated | Lightweight tenant summary list |
| `PUT` | `/api/v1/tenants/{tenantId}` | `SUPER_USER` | Update tenant details / status |
| `POST` | `/api/v1/tenants/{tenantId}/deactivate` | `SUPER_USER` | Deactivate a tenant |
| `POST` | `/api/v1/tenants/api-token` | `SUPER_USER` | Issue an API token (key hash stored on `tenant_master_table`) |
| `PUT` | `/api/v1/tenants/{tenantId}/logo` | `STATE_ADMIN` | Upload tenant logo |

### Tenant Configuration

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/tenants/{tenantId}/config` | `STATE_ADMIN` | Get all configuration key-value pairs |
| `PUT` | `/api/v1/tenants/{tenantId}/config` | `STATE_ADMIN` | Create or update configuration keys |

### Location Hierarchy

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/tenants/{tenantId}/location-hierarchy/{type}` | `STATE_ADMIN` | Get hierarchy (`lgd` or `department`) |
| `PUT` | `/api/v1/tenants/{tenantId}/location-hierarchy/{type}` | `STATE_ADMIN` | Upload hierarchy data via CSV |

---

## 3. User Service APIs (`:8082`)

### Authentication

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/v1/auth/login` | Public | Email + password login |
| `POST` | `/api/v1/auth/refresh` | Public | Refresh access token |
| `POST` | `/api/v1/auth/logout` | Bearer | Revoke refresh token |
| `POST` | `/api/v1/auth/staff/otp` | Public | Request WhatsApp OTP |
| `POST` | `/api/v1/auth/staff/otp/verify` | Public | Verify OTP and receive JWT |
| `POST` | `/api/v1/auth/invites/activate` | Public | Activate account via invite token |
| `POST` | `/api/v1/auth/forgot-password` | Public | Trigger password reset email |
| `POST` | `/api/v1/auth/reset-password` | Public | Set new password via reset token |

**Login response:**
```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<uuid>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### User Profile

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/v1/users/me` | Bearer | Get current user's profile |
| `PATCH` | `/api/v1/users/me` | Bearer | Update name or language preference |
| `PATCH` | `/api/v1/users/me/password` | Bearer | Change password |

### Staff Management

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/users/invitations` | `STATE_ADMIN` | Invite a new staff member |
| `POST` | `/api/v1/users/invitations/resend` | `STATE_ADMIN` | Resend invitation email |
| `GET` | `/api/v1/tenant/user/staff` | `STATE_ADMIN` | List all staff in the tenant |
| `PUT` | `/api/v1/tenant/user/staff/{id}/role` | `STATE_ADMIN` | Update a staff member's role |
| `POST` | `/api/v1/tenant/user/staff/{id}/deactivate` | `STATE_ADMIN` | Deactivate a staff member |
| `POST` | `/api/v1/tenant/user/staff/{id}/activate` | `STATE_ADMIN` | Reactivate a staff member |
| `GET` | `/api/v1/tenant/user/staff/counts/by-role` | `STATE_ADMIN` | Staff counts grouped by role |

### Bulk Upload

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/state-admin/pump-operators/upload` | `STATE_ADMIN` | Bulk upload pump operators from CSV |
| `POST` | `/api/v1/state-admin/user-scheme-mappings/upload` | `STATE_ADMIN` | Bulk upload operator-to-scheme mappings |

---

## 4. Telemetry Service APIs (`:8989`)

### Glific Flow Webhooks (WhatsApp)

Glific calls a dedicated public webhook at each step of the WhatsApp submission flow, all under `/api/v1/telemetry` (secured by Glific signature, not JWT). Representative endpoints:

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/v1/telemetry/intro` | Public (Glific) | Flow entry / contact resolution |
| `POST` | `/api/v1/telemetry/take-meter-reading` | Public (Glific) | Receive meter photo → FlowVision AI |
| `POST` | `/api/v1/telemetry/manual-reading` | Public (Glific) | Operator enters/corrects the reading |
| `POST` | `/api/v1/telemetry/meter-change` | Public (Glific) | Record a meter replacement |
| `POST` | `/api/v1/telemetry/issue-report` | Public (Glific) | Report a supply outage / issue |

> The full set of webhook paths (`/language/selection`, `/channel/selection`, `/scheme/selected`, `/closing`, …) maps 1:1 to the configured Glific flow steps. See `GlificWebhookController`.

### Staff Query

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/v1/telemetry` | Authenticated (JWT) | List meter readings with filters |

**Query parameters for `GET /api/v1/telemetry`:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `schemeId` | Long | Filter by scheme |
| `fromDate` | LocalDate | Start date (inclusive) |
| `toDate` | LocalDate | End date (inclusive) |
| `channel` | Integer | `1`=WhatsApp, `2`=Web, `3`=IoT |
| `page` | Integer | Page number (0-indexed) |
| `size` | Integer | Page size (default: 20) |

---

## 5. Scheme Service APIs (`:8086`)

### Scheme Queries

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/scheme/schemes` | `STATE_ADMIN`, `SECTION_OFFICER`, `DISTRICT_OFFICER` | Paginated scheme list with filters |
| `GET` | `/api/v1/scheme/schemes/counts` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Scheme counts grouped by status |
| `GET` | `/api/v1/scheme/schemes/{schemeId}` | `STATE_ADMIN`, `SECTION_OFFICER` | Single scheme details |
| `GET` | `/api/v1/scheme/schemes/mappings` | `STATE_ADMIN` | Scheme-to-location mapping list |

### Bulk Upload

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/scheme/schemes/upload` | `STATE_ADMIN` | Bulk upload schemes from CSV |
| `POST` | `/api/v1/scheme/schemes/mappings/upload` | `STATE_ADMIN` | Bulk upload scheme-to-location mappings |

---

## 6. Message Service APIs (`:8085`)

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/message/notifications` | `STATE_ADMIN` | List notification history for the tenant |
| `POST` | `/api/v1/message/notifications` | `STATE_ADMIN` | Manually trigger a notification |
| `POST` | `/api/v1/message/events` | `SUPER_USER` | Dispatch a Kafka event directly (admin use) |

---

## 7. Analytics Service APIs (`:8087`)

### Scheme and Tenant Analytics

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/tenants` | `SUPER_USER` | List all tenants in the data warehouse |
| `GET` | `/api/v1/analytics/schemes` | `SUPER_USER`, `STATE_ADMIN` | List schemes |
| `GET` | `/api/v1/analytics/scheme-performance` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Compliance and performance metrics |
| `GET` | `/api/v1/analytics/scheme-regularity/periodic` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Periodical compliance rates |

### Meter Readings and Water Quantity

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/meter-readings` | `STATE_ADMIN`, `SECTION_OFFICER` | Query meter readings |
| `GET` | `/api/v1/analytics/water-quantity` | `STATE_ADMIN`, `DISTRICT_OFFICER` | Aggregated LPCD metrics |
| `GET` | `/api/v1/analytics/escalations` | `SUPER_USER`, `STATE_ADMIN`, `DISTRICT_OFFICER` | Escalation history |

### National Dashboard

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/analytics/national/dashboard` | `SUPER_USER` | Cross-tenant aggregate metrics |

**Common query parameters for analytics endpoints:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `tenantId` | Long | Filter by tenant |
| `schemeId` | Long | Filter by scheme |
| `fromDate` | LocalDate | Start date |
| `toDate` | LocalDate | End date |
| `groupBy` | String | `day`, `week`, or `month` |
| `page` | Integer | Page number |
| `size` | Integer | Page size |
