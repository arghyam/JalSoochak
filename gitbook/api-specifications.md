# API Specifications

## 9. API Specifications

All APIs are RESTful, versioned under `/api/v1/`, and (except public endpoints) require a Keycloak-issued **`Authorization: Bearer <JWT>`** header. Clients reach services through the **API Gateway** by path prefix (`/user/**`, `/tenant/**`, `/scheme/**`, `/telemetry/**`, `/message/**`, `/analytics/**`, `/anomaly/**`); the gateway validates the JWT and forwards the request. Every service also publishes an interactive **Swagger UI** and OpenAPI document.

### 9.1 Authentication & User APIs

```json
// POST /api/v1/auth/login
{ "email": "admin@state.gov", "password": "••••••" }
// → { "accessToken": "<jwt>", "refreshToken": "<uuid>", "tokenType": "Bearer", "expiresIn": 900 }
```

* `POST /api/v1/auth/login` — email + password (public)
* `POST /api/v1/auth/refresh` — rotate refresh token for a new access token (public)
* `POST /api/v1/auth/staff/otp` and `/staff/otp/verify` — WhatsApp OTP login (public)
* `POST /api/v1/auth/invites/activate`, `/forgot-password`, `/reset-password` — account activation and password reset (public)
* `GET /api/v1/users/me`, `PATCH /api/v1/users/me` — current user profile
* `POST /api/v1/users/invitations` — invite staff *(State Admin)*
* `GET /api/v1/tenant/user/staff`, `POST /api/v1/tenant/user/staff/{id}/deactivate` — staff management *(State Admin)*
* `POST /api/v1/state-admin/pump-operators/upload`, `/user-scheme-mappings/upload` — bulk CSV upload *(State Admin)*

### 9.2 Tenant Admin & Configuration APIs

* `POST /api/v1/tenants` — create a tenant and provision its schema *(Super User)*
* `GET /api/v1/tenants`, `GET /api/v1/tenants/summary` — list tenants
* `PUT /api/v1/tenants/{id}`, `POST /api/v1/tenants/{id}/deactivate` — update / deactivate *(Super User)*
* `GET` / `PUT /api/v1/tenants/{id}/config` — per-tenant configuration key-values *(Super User / State Admin)*
* `GET` / `PUT /api/v1/tenants/{id}/location-hierarchy/{type}` — LGD / departmental hierarchy
* `GET` / `PUT /api/v1/system/config` — platform-wide defaults *(Super User)*

### 9.3 Scheme & Location APIs

* `GET /api/v1/scheme/schemes` — paginated, filterable scheme list (work status, operating status, district/block, search)
* `GET /api/v1/scheme/schemes/counts`, `/counts/by-status` — aggregate counts
* `PATCH /api/v1/scheme/schemes/{id}/status` — update scheme status
* `POST /api/v1/scheme/schemes/upload`, `/schemes/mappings/upload` — bulk CSV upload *(State Admin)*
* `GET /api/v1/public/schemes/{id}` — public scheme detail

### 9.4 Field Submission APIs (Glific Flow Webhooks)

The WhatsApp submission journey is a multi-step Glific flow; Glific calls a public webhook at each step, all under `/api/v1/telemetry` (secured by Glific signature, not JWT):

* `POST /api/v1/telemetry/intro` — flow entry / contact resolution
* `POST /api/v1/telemetry/take-meter-reading` — receive meter photo → FlowVision AI
* `POST /api/v1/telemetry/manual-reading` — operator enters / corrects the reading
* `POST /api/v1/telemetry/meter-change`, `/issue-report` — meter replacement / outage reporting
* `GET /api/v1/telemetry` — list meter readings for dashboards *(authenticated)*

```json
// Example reading event published after a successful submission
{
  "eventType": "METER_READING_SUBMITTED",
  "tenantId": 1, "schemeId": 101, "userId": 42,
  "confirmedReading": 1250, "confidence": 97.3,
  "readingDate": "2026-06-12", "channel": 1
}
```

### 9.5 Messaging APIs

* `GET /api/v1/message/notifications` — notification history *(State Admin)*
* `POST /api/v1/message/notifications` — trigger a notification *(State Admin)*

Nudge and escalation messages are normally produced by the tenant-service schedulers and delivered by the message service via Glific; these endpoints support manual/administrative use.

### 9.6 Analytics & Dashboard APIs

Read-only BI endpoints under `/api/v1/analytics/`, scoped by tenant via the JWT:

* `GET /api/v1/analytics/schemes/dashboard`, `/critical-schemes`, `/continuous-schemes`
* `GET /api/v1/analytics/scheme-regularity/periodic`, `/reading-submission-rate`
* `GET /api/v1/analytics/water-quantity/region-wise`, `/water-quantity/periodic`
* `GET /api/v1/analytics/escalations`, `/operator-attendance`, `/officer/dashboard`
* `GET /api/v1/analytics/national/dashboard` and `/national/dashboard/district` — country-level aggregates *(Super User)*

Common query parameters: `tenantId`, `schemeId`, `fromDate`, `toDate`, `groupBy` (`day` / `week` / `month`), `page`, `size`.

### 9.7 Anomaly APIs

* `GET /api/v1/anomalies` — list detected anomalies with filters *(authenticated)*
