# API Specifications

## 9. API Specifications

All APIs are RESTful and versioned under `/api/v1/`. Clients reach services through the **API Gateway** by path prefix (`/user/**`, `/tenant/**`, `/scheme/**`, `/telemetry/**`, `/message/**`, `/analytics/**`, `/anomaly/**`); the gateway validates a Keycloak-issued **`Authorization: Bearer <JWT>`** and forwards the request. Every service also publishes an interactive **Swagger UI** and OpenAPI document.

> **Two families of traffic do not go through the gateway and are not JWT-authenticated.** Ingress
> routes them straight to telemetry-service, so the gateway's filter chain never sees them:
>
> * **Glific flow webhooks** under `/api/v1/telemetry/*` — authenticated by the `X-Webhook-Token`
>   shared secret (§9.4).
> * **Partner meter-reading ingestion** (`POST`/`PUT /api/v1/telemetry/readings`,
>   `/readings/formats/{format}`, `PATCH /api/v1/telemetry/schemes/{id}/yesterday-final-reading`) —
>   authenticated by the per-tenant `X-Api-Key`.
>
> Both prefixes are publicly reachable. Do not assume a path under `/api/v1/` is behind the gateway.

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

The WhatsApp submission journey is a multi-step Glific flow; Glific calls a webhook at each step, all
under `/api/v1/telemetry`. There are **26** such endpoints, every one a `POST`.

**Authentication.** Each request must carry the shared secret header:

```
X-Webhook-Token: <token>
```

`GlificWebhookAuthFilter` in telemetry-service compares the SHA-256 of the supplied token against
`telemetry.webhook.auth.token-hashes` and returns
`401 {"success":false,"message":"Unauthorized"}` when it does not match. The match is a closed
allowlist of exactly these 26 routes — *not* a prefix rule on `/api/v1/telemetry/**`, because that
prefix is shared with the `X-Api-Key` ingestion endpoints, which use a different credential.

Set `TELEMETRY_WEBHOOK_AUTH_MODE=AUDIT` to log outcomes without rejecting (the kill switch), or
`OFF` for local development. `ENFORCE` is the default and refuses to start with no token configured.

The endpoints:

* `POST /api/v1/telemetry/intro`, `/closing` — flow entry / contact resolution, closing message
* `POST /api/v1/telemetry/language/selection`, `/selected/language` — language prompt and choice
* `POST /api/v1/telemetry/channel/selection`, `/selected/channel` — channel prompt and choice
* `POST /api/v1/telemetry/schemes`, `/scheme/selected` — scheme list and choice
* `POST /api/v1/telemetry/item/selection`, `/selected/item` — item prompt and choice
* `POST /api/v1/telemetry/take-meter-reading` — receive meter photo → FlowVision AI
* `POST /api/v1/telemetry/readings/glific` — async image submission, returns a job ack
* `POST /api/v1/telemetry/manual-reading`, `/location`, `/update-previous-reading` — enter, geotag or
  correct a reading
* `POST /api/v1/telemetry/meter-change`, `/meter/meter-change`, `/meter/meter-change/submit` — meter
  replacement
* `POST /api/v1/telemetry/issue-report`, `/issue-report/submit`, `/issue-report/telemetry`,
  `/issue-report/telemetry/submit`, `/meter/issue-report` — outage and telemetry issue reporting
* `POST /api/v1/telemetry/others`, `/others/submitted` — free-text fallback
* `POST /api/v1/telemetry/trigger-welcome-message` — operator onboarding message

> Adding a webhook to `GlificWebhookController` without registering it in `GlificWebhookRoutes` fails
> the build (`GlificWebhookRouteCoverageTest`), because it would ship unauthenticated. A new endpoint
> also needs the header added to its Glific flow node.

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
