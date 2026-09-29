# API Specifications

## 9. API Specifications

All APIs are RESTful and versioned under `/api/v1/`. Clients reach services through the **API Gateway**, either on the flat path (`/api/v1/...`) or by service prefix (`/user/**`, `/tenant/**`, `/scheme/**`, `/telemetry/**`, `/message/**`, `/analytics/**`, `/anomaly/**`), which the gateway strips; the gateway validates a Keycloak-issued (`jalsoochak-realm`) **`Authorization: Bearer <JWT>`** on every path the owning service does not publish anonymously, and forwards the request. Responses are wrapped as `{ "status", "message", "data" }`. Every service except anomaly-service publishes an OpenAPI document at `/v3/api-docs`; the gateway aggregates them into one interactive **Swagger UI** at `/swagger-ui.html`.

> **Two families of traffic do not go through the gateway and are not JWT-authenticated.** Ingress
> routes them straight to telemetry-service, so the gateway's filter chain never sees them:
>
> * **Chatbot flow webhooks** under `/api/v1/telemetry/*` — authenticated by the `X-Webhook-Token`
>   shared secret (§9.4).
> * **Partner meter-reading ingestion** (`POST`/`PUT /api/v1/telemetry/readings`,
>   `/readings/formats/{format}`, `/readings/reset-latest`,
>   `PATCH /api/v1/telemetry/schemes/{id}/yesterday-final-reading`) — authenticated by the
>   per-tenant `X-Api-Key`, which a State Admin issues with `POST /api/v1/tenants/api-token`.
>
> Both prefixes are publicly reachable. Do not assume a path under `/api/v1/` is behind the gateway.

### 9.1 Authentication & User APIs

```json
// POST /api/v1/auth/login
{ "email": "admin@state.gov", "password": "••••••" }
// → { "status": 200, "message": "Login successful",
//     "data": { "access_token": "<jwt>", "token_type": "Bearer", "expires_in": 900,
//               "tenant_code": "MP", "user_role": "STATE_ADMIN", "name": "..." } }
// The refresh token is not in the body: it is set as an HttpOnly `refresh_token` cookie.
```

* `POST /api/v1/auth/login` — email + password (public)
* `POST /api/v1/auth/refresh`, `/logout` — exchange the `refresh_token` cookie for a new access token, or revoke it (public)
* `POST /api/v1/auth/staff/otp` and `/staff/otp/verify` — phone-number OTP login for staff (non-admin) users, delivered on WhatsApp by default (public)
* `GET /api/v1/auth/invites`, `POST /api/v1/auth/invites/activate`, `/forgot-password`, `/reset-password` — invite lookup, account activation and password reset (public)
* `GET /api/v1/users/me`, `PATCH /api/v1/users/me`, `PATCH /api/v1/users/me/password` — current user profile
* `POST /api/v1/users/invitations` — invite an admin user *(Super User / State Admin)*
* `GET /api/v1/users/super-users`, `/state-admins`, `/{id}`; `PATCH /api/v1/users/{id}`; `POST /{id}/deactivate`, `/{id}/activate`, `/{id}/invitations` — admin-user management
* `GET /api/v1/tenant/user/staff`, `/staff/counts/by-role`, `POST /api/v1/tenant/user/staff/{id}/deactivate`, `/activate` — staff management *(Super User / State Admin)*; `PUT /staff/{id}/role`, `POST /staff/reports` *(State Admin)*
* `POST /api/v1/state-admin/pump-operators/upload`, `/user-scheme-mappings/upload` — bulk CSV upload *(State Admin)*
* `GET /api/v1/pumpoperator/**` — pump-operator detail, readings and reading compliance; only `/pump-operators/by-uuid/{uuid}`, `/pump-operators/by-scheme` and `/by-scheme/reading-compliance` are public (village dashboard)

### 9.2 Tenant Admin & Configuration APIs

* `POST /api/v1/tenants` — create a tenant and provision its schema *(Super User)*
* `GET /api/v1/tenants` — paginated tenant list, filterable by status (public); `GET /api/v1/tenants/summary` — counts by status *(Super User)*
* `PUT /api/v1/tenants/{id}`, `POST /api/v1/tenants/{id}/deactivate` — update / deactivate *(Super User)*
* `GET` / `PUT /api/v1/tenants/{id}/config`, `GET /config/status` — per-tenant configuration key-values and their completeness *(Super User / own-tenant State Admin)*; `GET /config/public` — the public subset (public)
* `GET` / `PUT /api/v1/tenants/{id}/location-hierarchy/{type}`, `GET /locations/{type}` — LGD / departmental hierarchy and child locations (reads public; update *Super User / State Admin*)
* `GET` / `PUT /api/v1/tenants/{id}/logo` — tenant branding (read public)
* `PUT` / `GET` / `DELETE /api/v1/tenants/{id}/messaging-providers`, `/{channel}/secrets` — per-tenant email / SMS provider settings and credentials *(Super User / State Admin)*
* `POST /api/v1/tenants/api-token` — issue the tenant's State-IT `X-Api-Key` *(State Admin)*
* `GET` / `PUT /api/v1/system/config` — platform-wide defaults *(Super User)*; `GET /api/v1/system/channels` *(Super User / State Admin)*
* `POST /api/v1/system/messaging-secrets/rewrap`, `/tenants/{id}/rotate` — secret key management *(Super User)*

### 9.3 Scheme & Location APIs

* `GET /api/v1/scheme/schemes` — paginated, filterable scheme list (work status, operating status, scheme name, state scheme ID)
* `GET /api/v1/scheme/schemes/mappings` — paginated scheme-to-location mappings (village LGD code, sub-division)
* `GET /api/v1/scheme/schemes/counts/by-status` — aggregate counts
* `GET /api/v1/scheme/schemes/yesterday-final-readings` — each scheme's previous-day closing reading
* `GET /api/v1/scheme/schemes/{id}/statuses`, `PATCH /api/v1/scheme/schemes/{id}/status` — read / update a scheme's work and operating status *(update: State Admin)*
* `POST /api/v1/scheme/schemes/upload`, `/schemes/mappings/upload` — bulk CSV upload; `GET /schemes/download`, `/schemes/mappings/download` — matching report downloads *(State Admin)*
* `GET /api/v1/public/schemes/{id}` — scheme detail by `tenantCode`; despite the prefix it requires a JWT for that tenant

### 9.4 Field Submission APIs (Chatbot Flow Webhooks)

The WhatsApp submission journey is a multi-step chatbot flow; the WhatsApp provider calls a webhook at
each step, all under `/api/v1/telemetry`. There are **26** such endpoints, every one a `POST`.

**Authentication.** Each request must carry the shared secret header:

```
X-Webhook-Token: <token>
```

`WebhookAuthFilter` in telemetry-service compares the SHA-256 of the supplied token against
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
* `POST /api/v1/telemetry/take-meter-reading` — receive meter photo → OCR provider
* `POST /api/v1/telemetry/readings/whatsapp` — async image submission, returns a job ack
* `POST /api/v1/telemetry/manual-reading`, `/location`, `/update-previous-reading` — enter, geotag or
  correct a reading
* `POST /api/v1/telemetry/meter-change`, `/meter/meter-change`, `/meter/meter-change/submit` — meter
  replacement
* `POST /api/v1/telemetry/issue-report`, `/issue-report/submit`, `/issue-report/telemetry`,
  `/issue-report/telemetry/submit`, `/meter/issue-report` — outage and telemetry issue reporting
* `POST /api/v1/telemetry/others`, `/others/submitted` — free-text fallback
* `POST /api/v1/telemetry/trigger-welcome-message` — operator onboarding message

> Adding a webhook to a `@WebhookRoute` controller without registering it in `WebhookRoutes`
> fails the build (`WebhookRouteCoverageTest`), because it would ship unauthenticated. A new endpoint
> also needs the header added to its chatbot flow node.

```json
// Example reading event published after a successful submission
{
  "eventType": "METER_READING_RECORDED",
  "tenantId": 1, "schemeId": 101, "userId": 42,
  "confirmedReading": 1250, "confidence": 97,
  "readingDate": "2026-06-12", "channel": 1
}
```

### 9.5 Messaging APIs

* `POST /api/v1/message/notifications` — trigger a notification *(authenticated)*
* `POST /api/v1/message/events` — publish a raw event to `message-service-topic` *(authenticated)*
* `POST /api/v1/message/trigger-welcome-message` — send the welcome message to a phone number; called by the chatbot flow (public)

Nudge and escalation messages are normally produced by the tenant-service schedulers and delivered by the message service via the WhatsApp provider; these endpoints support manual/administrative use.

### 9.6 Analytics & Dashboard APIs

Read-only BI endpoints under `/api/v1/analytics/`. The endpoints the public dashboard renders are anonymous `GET`s scoped by the `tenant_id` parameter; every other endpoint needs a JWT, and the officer-console reads are restricted to Section and Sub-Divisional Officers (`user_type` claim):

* `GET /api/v1/analytics/schemes/dashboard`, `/critical-schemes`, `/continuous-schemes` (public)
* `GET /api/v1/analytics/scheme-regularity/average`, `/scheme-regularity/periodic`, `/reading-submission-rate`, `/submission-status` (public)
* `GET /api/v1/analytics/water-quantity/region-wise`, `/water-quantity/periodic`, `/water-supply/average-per-region`, `/outage-reasons` (public)
* `GET /api/v1/analytics/operator-attendance` — day-wise operator attendance (authenticated)
* `GET /api/v1/analytics/escalations`, `/anomalies`, `/officer/dashboard`, and the `/user` variants (`/critical-schemes/user`, `/continuous-schemes/user`, `/submission-status/user`, `/non-submission-reasons/user`, `/outage-reasons/user`) — officer-scoped reads *(Section / Sub-Divisional Officer)*
* `GET /api/v1/analytics/national/dashboard` (public) and `/national/dashboard/district` — country-level aggregates; refused with `403` in single-tenant mode

Common query parameters: `tenant_id`, `start_date`, `end_date`, `lgd_id` / `department_id` (and `parent_lgd_id` / `parent_department_id`), `scale` (`day` / `week` / `month` / `quarter` / `year`), `page`, `limit`.

### 9.7 Anomaly APIs

* `GET /api/v1/anomalies` — list detected anomalies *(authenticated)*; takes no filters — the filterable, officer-facing view is `GET /api/v1/analytics/anomalies`
