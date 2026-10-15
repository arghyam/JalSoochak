# JalSoochak V2 — API Endpoints Reference

All endpoints follow the pattern `/api/v1/<resource>/...`.
When accessed through the API gateway, prefix each path with the service slug (e.g. `/tenant/api/v1/...`).

---

## Pagination

Paginated endpoints take `page` (zero-indexed, default `0`) and `size` (default `20`; named `limit`
on `/api/v1/tenant/user/staff`).

On the **user-service** endpoints — `/api/v1/pumpoperator/...` and `/api/v1/tenant/user/staff` —
both are validated at the controller: `page` must be `>= 0` and `size` must be between `1` and
`100`. Out-of-range values are rejected with `400 Bad Request` and a `fieldErrors` entry naming the
offending parameter — they are never silently clamped.

Exception: `/api/v1/pumpoperator/pump-operators/by-scheme` only paginates when `page` or `size` is
supplied. When either is, the same bounds apply.

Endpoints on the other services do not share this contract. Scheme-service's `/api/v1/scheme/schemes`
family, for one, takes `page`/`limit` unvalidated at the controller and clamps them in the service
layer (`page` to `>= 0`, `limit` to `1..100`), so an out-of-range value there returns `200` with the
clamped page rather than `400`.

---

## Required Environment Variables

Set the following environment variables before running message-service (the full list, with
defaults, is in its `application.yml`):

- `WHATSAPP_API_URL`
- `WHATSAPP_USERNAME`
- `WHATSAPP_PASSWORD`
- `WHATSAPP_NUDGE_FLOW_ID`
- `WHATSAPP_NUDGE_TEMPLATE_ID`
- `WHATSAPP_ESCALATION_TEMPLATE_ID`
- `STORAGE_ENDPOINT`
- `STORAGE_ACCESS_KEY`
- `STORAGE_SECRET_KEY`
- `STORAGE_BUCKET`
- `STORAGE_PUBLIC_BASE_URL`

---

## Auth & Users (`user-service` · port 8082)

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/login` | Login with email + password |
| POST | `/api/v1/auth/refresh` | Refresh access token |
| POST | `/api/v1/auth/logout` | Logout |
| GET | `/api/v1/auth/invites` | Get invite token metadata |
| POST | `/api/v1/auth/invites/activate` | Activate account from invite |
| POST | `/api/v1/auth/forgot-password` | Trigger forgot-password email |
| POST | `/api/v1/auth/reset-password` | Reset password with token |
| POST | `/api/v1/auth/staff/otp` | Request WhatsApp OTP (staff login) |
| POST | `/api/v1/auth/staff/otp/verify` | Verify WhatsApp OTP |

### Users

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/users/invitations` | Invite a new user |
| GET | `/api/v1/users/me` | Get current user profile |
| PATCH | `/api/v1/users/me` | Update current user profile |
| PATCH | `/api/v1/users/me/password` | Change own password |
| GET | `/api/v1/users/super-users` | List all super users |
| GET | `/api/v1/users/state-admins` | List all state admins |
| GET | `/api/v1/users/{id}` | Get user by ID |
| PATCH | `/api/v1/users/{id}` | Update user by ID |
| POST | `/api/v1/users/{id}/deactivate` | Deactivate user |
| POST | `/api/v1/users/{id}/activate` | Activate user |
| POST | `/api/v1/users/{id}/invitations` | Resend invite email |

### Tenant Staff

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/tenant/user/staff` | List tenant staff |
| PUT | `/api/v1/tenant/user/staff/{id}/role` | Update staff role |
| GET | `/api/v1/tenant/user/staff/counts/by-role` | Staff counts grouped by role |
| POST | `/api/v1/tenant/user/staff/{id}/deactivate` | Deactivate a staff member |
| POST | `/api/v1/tenant/user/staff/{id}/activate` | Reactivate a staff member |
| POST | `/api/v1/tenant/user/staff/reports` | Staff export (same filters as `GET /staff`), cached per data version |
| POST | `/api/v1/tenant/user/welcome` | Send welcome message to staff |

### Pump Operators

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/pumpoperator/pump-operators/{id}?tenantCode={tenantCode}&schemeId={schemeId?}&startDate={yyyy-MM-dd}&endDate={yyyy-MM-dd}` | Get pump operator by ID, optionally scoped by scheme and date range |
| GET | `/api/v1/pumpoperator/pump-operators/{id}/reading-compliance` | Reading compliance for one operator |
| GET | `/api/v1/pumpoperator/pump-operators/{id}/details-with-compliance` | Operator details + compliance |
| GET | `/api/v1/pumpoperator/pump-operators/reading-compliance` | Reading compliance for all operators |
| GET | `/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance?schemeId={schemeId}&pumpOperatorId={pumpOperatorId}&startDate={yyyy-MM-dd}&endDate={yyyy-MM-dd}` | Compliance for a pump operator within a scheme, optionally filtered by date range |
| GET | `/api/v1/pumpoperator/pump-operators/by-scheme` | Operators grouped by scheme |
| GET | `/api/v1/pumpoperator/pump-operators/by-uuid/{uuid}` | Operator detail by UUID — the anonymous village dashboard's route |
| GET | `/api/v1/pumpoperator/pump-operators/{pumpOperatorId}/readings` | One operator's readings |
| GET | `/api/v1/pumpoperator/person/{personId}/schemes` | Schemes assigned to an officer |
| GET | `/api/v1/pumpoperator/person/{personId}/schemes/count` | Count of schemes assigned to an officer |
| GET | `/api/v1/pumpoperator/person/{personId}/pump-operators` | Operators on an officer's schemes |
| GET | `/api/v1/pumpoperator/schemes/{schemeId}/details` | Scheme details, scoped to the caller |
| GET | `/api/v1/pumpoperator/schemes/{schemeId}/reading-submissions` | A scheme's reading submissions, scoped to the caller |
| POST | `/api/v1/state-admin/pump-operators/upload` | Bulk upload pump operators (CSV) |
| POST | `/api/v1/state-admin/user-scheme-mappings/upload` | Bulk upload user-scheme mappings (CSV/XLSX) |

---

## Tenants (`tenant-service` · port 8081)

### Tenant Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/tenants` | Create tenant |
| GET | `/api/v1/tenants` | List all tenants |
| GET | `/api/v1/tenants/summary` | Tenant summary list |
| PUT | `/api/v1/tenants/{tenantId}` | Update tenant |
| POST | `/api/v1/tenants/{tenantId}/deactivate` | Deactivate tenant |
| GET | `/api/v1/tenants/{tenantId}/config` | Get tenant config |
| GET | `/api/v1/tenants/{tenantId}/config/public` | Get public tenant config |
| GET | `/api/v1/tenants/{tenantId}/config/status` | Get tenant config status |
| PUT | `/api/v1/tenants/{tenantId}/config` | Update tenant config |
| PUT | `/api/v1/tenants/{tenantId}/logo` | Upload tenant logo |
| GET | `/api/v1/tenants/{tenantId}/logo` | Get tenant logo |
| GET | `/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}` | Get location hierarchy |
| GET | `/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints` | Location hierarchy edit constraints |
| PUT | `/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}` | Update location hierarchy |
| GET | `/api/v1/tenants/{tenantId}/locations/{hierarchyType}` | Get child locations |
| POST | `/api/v1/tenants/api-token` | Issue the tenant's partner-ingestion `X-Api-Key` (STATE_ADMIN) |
| PUT | `/api/v1/tenants/{tenantId}/messaging-providers` | Set email / SMS provider settings |
| GET | `/api/v1/tenants/{tenantId}/messaging-providers` | Provider settings and secret status |
| DELETE | `/api/v1/tenants/{tenantId}/messaging-providers/{channel}` | Delete a channel's provider settings |
| PUT | `/api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets` | Set a channel's provider secrets |
| GET | `/api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets` | A channel's secret status (never the values) |
| DELETE | `/api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets` | Delete a channel's provider secrets |

### System Config

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/system/config` | Get system config |
| GET | `/api/v1/system/channels` | Get available channels |
| PUT | `/api/v1/system/config` | Update system config |
| POST | `/api/v1/system/messaging-secrets/rewrap` | Re-wrap every tenant's data key under the current master key (SUPER_USER) |
| POST | `/api/v1/system/messaging-secrets/tenants/{tenantId}/rotate` | Rotate one tenant's data key (SUPER_USER) |

---

## Schemes (`scheme-service` · port 8086)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/public/schemes/{schemeId}?tenantCode={tenantCode}` | Get scheme by ID. Authenticated despite the `public` path — `tenantCode` must match the caller's own tenant. No other query parameters are read |
| GET | `/api/v1/scheme/schemes` | List all schemes; `workStatus` / `operatingStatus` accept repeated or comma-separated values |
| GET | `/api/v1/scheme/schemes/mappings` | List scheme mappings; same multi-valued status filters |
| GET | `/api/v1/scheme/schemes/counts/by-status` | Total schemes plus the work-status and operating-status breakdowns |
| GET | `/api/v1/scheme/schemes/yesterday-final-readings` | Each scheme's previous-day closing reading |
| GET | `/api/v1/scheme/schemes/{schemeId}/statuses` | A scheme's work and operating status |
| PATCH | `/api/v1/scheme/schemes/{schemeId}/status?tenantCode={tenantCode}` | Update scheme work/operating status (one or both) |
| POST | `/api/v1/scheme/schemes/upload` | Bulk upload schemes (CSV) |
| POST | `/api/v1/scheme/schemes/mappings/upload` | Bulk upload scheme mappings (CSV) |
| GET | `/api/v1/scheme/schemes/download` | Download schemes (STATE_ADMIN) |
| GET | `/api/v1/scheme/schemes/mappings/download` | Download scheme mappings (STATE_ADMIN) |
| POST | `/api/v1/scheme/schemes/analytics-resync` | Re-publish the `X-Tenant-Code` tenant's schemes to analytics (STATE_ADMIN / SUPER_STATE_ADMIN) |

### State master-data sync (`scheme-service`)

SUPER_USER / STATE_ADMIN / SUPER_STATE_ADMIN with access to that tenant. Off unless `STATE_SYNC_ENABLED=true` — the two
POSTs then answer 409. See `docs/jjm-brain-sync-plan.md`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/scheme/state-sync/config?tenantCode={tenantCode}` | Effective flag, mode (`DRY_RUN`/`APPLY`) and crons. Always 200: `"configured": false` (and no crons) when `STATE_SYNC_TENANT_CODE` is unset or names another tenant, the case where the other state-sync endpoints answer 404 |
| POST | `/api/v1/scheme/state-sync/runs?tenantCode={tenantCode}&kind={FULL\|DELTA}` | Start a run in the background → 202 `{runId}`; 409 while another run holds the lock |
| POST | `/api/v1/scheme/state-sync/schemes/refresh?tenantCode={tenantCode}&ref={SCH-code\|IMIS id}` | Re-pull and reconcile one scheme now; returns the run's counts and issues |
| GET | `/api/v1/scheme/state-sync/runs?tenantCode={tenantCode}&limit={n}` | Run history, newest first |
| GET | `/api/v1/scheme/state-sync/issues?tenantCode={tenantCode}&runId={id?}&category={c?}&limit={n}&offset={n}` | What a run declined to write (conflicts, unmatched villages, spared archives …) |
| GET | `/api/v1/scheme/state-sync/unmatched-schemes?tenantCode={tenantCode}&format={json\|csv}` | Our schemes the last successful full run did not find upstream: `NOT_IN_JJM` (never matched) and `NO_LONGER_LISTED` (code missing from the full list), with name, JJM code, IMIS / SMT ids and last reading date. `csv` downloads a file to review with the JJM team |

### Scheme Status Integer Mapping

`work_status`
- `1` = `Ongoing`
- `2` = `Completed`
- `3` = `Not Started`
- `4` = `Handed Over`

`operating_status`
- `0` = `Non-Operative`
- `1` = `Operative`
- `2` = `Partially Operative`

---

## Analytics (`analytics-service` · port 8087)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/water-quantity/region-wise` | Water quantity by region |
| GET | `/api/v1/analytics/water-quantity/periodic` | Periodic water quantity |
| GET | `/api/v1/analytics/outage-reasons` | Outage reasons summary |
| GET | `/api/v1/analytics/outage-reasons/periodic` | Periodic outage reasons |
| GET | `/api/v1/analytics/outage-reasons/user` | Outage reasons by user |
| GET | `/api/v1/analytics/non-submission-reasons` | Non-submission reasons |
| GET | `/api/v1/analytics/non-submission-reasons/user` | Non-submission reasons by user |
| GET | `/api/v1/analytics/submission-status/user` | Submission status by user |
| GET | `/api/v1/analytics/submission-status` | Submission status summary |
| GET | `/api/v1/analytics/water-supply/average-per-region` | Average water supply per region |
| GET | `/api/v1/analytics/national/dashboard` | National dashboard data |
| GET | `/api/v1/analytics/national/dashboard/district` | National dashboard at district level |
| GET | `/api/v1/analytics/national/dashboard/boundary` | State boundaries (GeoJSON) for the national map |
| GET | `/api/v1/analytics/national/dashboard/boundary/district` | National outline + district boundaries (GeoJSON) |
| GET | `/api/v1/analytics/scheme-regularity/periodic/national` | National periodic scheme regularity |
| GET | `/api/v1/analytics/schemes/status-count` | Scheme status counts |
| GET | `/api/v1/analytics/schemes/dashboard` | Scheme dashboard |
| GET | `/api/v1/analytics/schemes/dashboard/download` | Scheme dashboard as CSV |
| GET | `/api/v1/analytics/critical-schemes` | Critical schemes (no supply in the last N days) for an area |
| GET | `/api/v1/analytics/critical-schemes/user` | Critical schemes mapped to the caller |
| GET | `/api/v1/analytics/continuous-schemes` | Schemes that reported in the date range, for an area |
| GET | `/api/v1/analytics/continuous-schemes/user` | Continuous schemes mapped to a user |
| GET | `/api/v1/analytics/schemes/region-report` | Scheme region report |
| GET | `/api/v1/analytics/escalations` | Escalation analytics |
| GET | `/api/v1/analytics/scheme-performance` | Scheme performance metrics |
| GET | `/api/v1/analytics/tenants` | Tenant analytics |
| GET | `/api/v1/analytics/tenant_data` | Tenant data |
| GET | `/api/v1/analytics/tenant_boundaries` | Boundary GeoJSON for a parent area and its children |
| GET | `/api/v1/analytics/tenant_performance_score` | Average performance score for a boundary |
| GET | `/api/v1/analytics/schemes` | Analytics schemes list |
| GET | `/api/v1/analytics/meter-readings` | Meter readings analytics |
| GET | `/api/v1/analytics/scheme-regularity/average` | Average scheme regularity |
| GET | `/api/v1/analytics/scheme-regularity/periodic` | Periodic scheme regularity |
| GET | `/api/v1/analytics/reading-submission-rate` | Reading submission rate |
| GET | `/api/v1/analytics/submission-activity/hourly` | Submissions by hour of day |
| GET | `/api/v1/analytics/officer/dashboard` | An officer's escalation, anomaly, scheme and water totals |
| GET | `/api/v1/analytics/operator-attendance` | A pump operator's day-wise attendance |
| GET | `/api/v1/analytics/anomalies` | Paginated anomalies on a user's schemes |
| GET | `/api/v1/analytics/anomalies/statuses` | Anomaly statuses list |
| GET | `/api/v1/analytics/escalations/statuses` | Escalation statuses list |

---

## Telemetry (`telemetry-service` · port 8084)

Not behind the gateway's JWT check; each family has its own credential. The 27 chatbot webhooks
(`X-Webhook-Token`) are listed in `WebhookRoutes`, and include
`POST /api/v1/telemetry/reject-latest-reading`, which resets the operator's reading from today after
they reject the value read from their photo.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/telemetry/readings` | Submit a reading (`X-Api-Key`) |
| PUT | `/api/v1/telemetry/readings` | Correct a submitted reading (`X-Api-Key`) |
| POST | `/api/v1/telemetry/readings/formats/{format}` | Submit a reading in a registered partner format (`X-Api-Key`) |
| POST | `/api/v1/telemetry/readings/reset-latest` | Reset the operator's latest reading, whatever its date (`X-Api-Key`) |
| PATCH | `/api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading` | Correct a scheme's previous-day closing reading (`X-Api-Key`) |
| POST | `/api/v1/telemetry/internal/readings/republish` | Re-send stored ELM/PDU readings to analytics (`X-Internal-Token` + `X-Tenant-Code`; see below) |
| POST | `/api/v1/telemetry/internal/readings/quarantine/release` | Assess quarantined readings again and release the ones that now pass (`X-Internal-Token` + `X-Tenant-Code`; see below) |

---

## Message (`message-service` · port 8085)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/message/notifications` | Send a notification (specify channel in body) |
| POST | `/api/v1/message/events` | Dispatch a Kafka event |
| POST | `/api/v1/message/trigger-welcome-message` | Send the welcome message (called by the chatbot flow; public) |
| POST | `/api/v1/message/delivery-receipts/{providerId}` | Provider delivery-status push. Public; each provider's adapter checks its signature or shared secret → 200, 400, 401, or 404 for an unknown provider |

---

## Anomaly (`anomaly-service` · port 8083)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/anomalies` | List anomaly records |
| POST | `/api/v1/publish` | Dispatch a Kafka event |

---

## All Endpoint Changes — Migration Reference

The tables below list every endpoint that changed across all services. If your frontend or any integration hardcodes a URL from the **Old** column, update it to the **New** column.

---

### Auth service — invite and OTP endpoints

| Old | New | Notes |
|-----|-----|-------|
| `GET /api/v1/auth/invite/info` | `GET /api/v1/auth/invites` | Renamed to use RESTful resource naming |
| `POST /api/v1/auth/activate-account` | `POST /api/v1/auth/invites/activate` | Moved under `/invites` resource |
| `POST /api/v1/auth/staff/request-otp` | `POST /api/v1/auth/staff/otp` | Simplified endpoint path |
| `POST /api/v1/auth/staff/verify-otp` | `POST /api/v1/auth/staff/otp/verify` | Moved under `/otp` resource |

---

### User service — invite and user action endpoints

| Old | New | Notes |
|-----|-----|-------|
| `POST /api/v1/users/invite` | `POST /api/v1/users/invitations` | Renamed to use RESTful resource naming |
| `PUT /api/v1/users/{id}/deactivate` | `POST /api/v1/users/{id}/deactivate` | Changed HTTP method from PUT to POST (action verb) |
| `PUT /api/v1/users/{id}/activate` | `POST /api/v1/users/{id}/activate` | Changed HTTP method from PUT to POST (action verb) |
| `POST /api/v1/users/{id}/reinvite` | `POST /api/v1/users/{id}/invitations` | Renamed to use RESTful resource naming |

---

### Tenant service — deactivate and public config endpoints

| Old | New | Notes |
|-----|-----|-------|
| `PUT /api/v1/tenants/{tenantId}/deactivate` | `POST /api/v1/tenants/{tenantId}/deactivate` | Changed HTTP method from PUT to POST (action verb) |
| `GET /api/v1/tenants/{tenantId}/public-config` | `GET /api/v1/tenants/{tenantId}/config/public` | Moved under `/config` resource for consistency |
| `GET /api/v1/tenants/{tenantId}/locations/{hierarchyType}/children/{parentId}` | `GET /api/v1/tenants/{tenantId}/locations/{hierarchyType}` | Simplified path; use `?parentId=` query parameter instead |

---

### Telemetry service — webhook base URL (affects all 27 chatbot webhook endpoints)

> ## Telemetry · Webhook (`telemetry-service` · port 8084)
> These endpoints are called by the **WhatsApp chatbot platform**, not by the frontend.
>
> **All 27 require the `X-Webhook-Token: <token>` header.** Ingress exposes them publicly, bypassing
> the API gateway, so this shared secret is the only thing in front of them. Requests without a valid
> token get `401 {"success":false,"message":"Unauthorized"}`. Configure via
> `TELEMETRY_WEBHOOK_AUTH_TOKEN_HASHES` (comma-separated SHA-256 hex of the accepted tokens);
> `TELEMETRY_WEBHOOK_AUTH_MODE=AUDIT` is the kill switch, `OFF` is for local development.
>
> Note this is a **different credential** from the `X-Api-Key` used by the partner ingestion
> endpoints (`/readings`, `/readings/formats/{format}`, `/schemes/{id}/yesterday-final-reading`),
> which share the same `/api/v1/telemetry` prefix.

### Partner ingestion — the optional `channel` parameter

`POST /api/v1/telemetry/readings` and `POST /api/v1/telemetry/readings/formats/{format}` accept an
optional `channel` on the request body: `BFM`, `ELM`, `PDU`, `IOT` or `MAN`, case-insensitive, blank
treated as absent. It names the equipment the reading came from and overrides the submitting
operator's stored channel preference; omitted, the preference decides and falls back to `BFM`, which
is what every caller did before the field existed.

An unsupported value returns `400` with `errorCode: CHANNEL_NOT_SUPPORTED`. `PUT /readings` and
`PATCH /schemes/{id}/yesterday-final-reading` do not take it — a correction keeps the channel
recorded when the reading was first submitted.

### Partner ingestion — the optional `reading_unit` parameter

`POST /api/v1/telemetry/readings`, `POST /api/v1/telemetry/readings/formats/{format}` and
`PUT /api/v1/telemetry/readings` accept an optional `reading_unit` (`PUT` also accepts `readingUnit`):
the unit `confirmed_reading` is given in. The value is converted to the channel's standard unit before
it is stored, except a kVAh value (below). Omitted or blank, `confirmed_reading` is taken to be in the
standard unit, which is what every caller sent before the field existed.

| Channel | Accepted `reading_unit` | Also accepted | Standard unit |
|---------|-------------------------|---------------|---------------|
| BFM | `m3`, `kL`, `L` | `m³` for `m3`; `litre`, `liter` for `L` | `m3` |
| ELM | `kW.h`, `kV.A.h` | `kWh` for `kW.h`; `kVAh` for `kV.A.h` | `kW.h` |
| PDU | `min`, `h` | `hr` for `h` | `min` |
| IOT, MAN | none | none | none |

Only these spellings are accepted. An alternative spelling is stored as the unit it stands for, so
`kWh` is stored as `kW.h` and `kVAh` as `kV.A.h`. Case and surrounding whitespace are ignored, but other
spellings, such as plurals like `litres` or `hrs`, are rejected rather than guessed at. The channel is
the one the reading is recorded under: on `POST`, the declared `channel` or else the operator's
stored preference; on `PUT`, the channel of the reading being corrected.
`PATCH /schemes/{id}/yesterday-final-reading` does not take a unit; its value is always in `m3`.

**kVAh readings.** An electricity meter may show apparent energy (kVAh) instead of, or as well as,
active energy (kWh). Each is its own running total, or register, so a kVAh value is stored in kVAh as
the meter shows it, and analytics turns the day's kVAh increase into kWh with the scheme's pumps' power
factor. A reading is compared only with earlier readings on the same register. The day a scheme moves
from one register to the other is treated like a new meter's first day: its water is what another
channel measured that day, or else 0.

**Corrections.** A `PUT` with no `reading_unit` is in the unit the corrected reading is stored in: kVAh
for a kVAh reading, and the channel's standard unit for every other, so a BFM reading sent in `L` is
corrected in `m3`. A declared `reading_unit` wins, except that a reading read from a photo can't be
moved to the other register: its OCR value stays in the unit the photo was read in.

**Photos.** A photo sent without `confirmed_reading` is stored in the unit OCR read it in: `m3` for
BFM, and `kW.h` or `kV.A.h` for ELM, whichever the meter shows. A `reading_unit` sent with it may only
be one of those units, and on ELM it must be the one the photo shows.

**Responses.** `meterReading` and `lastConfirmedReading` are in the unit the reading is stored in, so
kVAh for a kVAh reading; the response does not name it. `GET /api/v1/analytics/meter-readings` returns
each reading's `submittedUnit`, the unit it was sent in (null when none was recorded); its
`confirmedReading` and `extractedReading` are in kVAh when that is `kV.A.h`, and in the channel's
standard unit otherwise.

Both errors below return `400`:

- `READING_UNIT_NOT_SUPPORTED` — `reading_unit` is not one of the channel's units (any value, for IOT
  and MAN), or a photo sent without `confirmed_reading` names a unit OCR doesn't read (`L` or `kL`), or
  one other than the unit the photo shows. The last is found only after the photo is read. On `PUT`, also
  a unit on the other register from a reading read from a photo.
- `IMAGE_NOT_SUPPORTED_FOR_CHANNEL` — `POST` only. A photo (`reading_url`) sent without
  `confirmed_reading`, on a channel that can't read photos: PDU, IOT and MAN, and ELM when no OCR
  provider is configured for the tenant's electric meters. A photo sent with `confirmed_reading` is
  accepted on every channel; the photo is kept and not read.

### Partner ingestion — PDU limits

A PDU reading is how long the pumps ran, and a day has 1,440 minutes. On `POST /readings`,
`POST /readings/formats/{format}` and `PUT /readings`, and on WhatsApp, a PDU value is refused when:

- it is longer than 1,440 minutes on its own, or
- the scheme's PDU readings on that day would add up to more than 1,440 minutes with it. A `PUT`
  replaces the corrected reading's old value, so only the scheme's other readings that day count.

Both return `400` with `errorCode: ABNORMAL_READING`, and nothing is stored.

| Method | Endpoint                                          | Description |
|--------|---------------------------------------------------|-------------|
| POST | `/api/v1/telemetry/readings/whatsapp`             | Receive the chatbot webhook payload for image-based meter readings |
| POST | `/api/v1/telemetry/intro`                         | Send the flow intro message for a contact |
| POST | `/api/v1/telemetry/closing`                       | Send the flow closing message for a contact |
| POST | `/api/v1/telemetry/language/selection`            | Return the language selection prompt/options for a contact |
| POST | `/api/v1/telemetry/selected/language`             | Persist the selected language for a contact |
| POST | `/api/v1/telemetry/channel/selection`             | Return the channel selection prompt/options for a contact |
| POST | `/api/v1/telemetry/selected/channel`              | Persist the selected channel for a contact |
| POST | `/api/v1/telemetry/schemes`                       | Return the schemes the contact's operator is mapped to |
| POST | `/api/v1/telemetry/scheme/selected`               | Persist the selected scheme for a contact |
| POST | `/api/v1/telemetry/item/selection`                | Return the item selection prompt/options for a contact |
| POST | `/api/v1/telemetry/selected/item`                 | Persist the selected item for a contact |
| POST | `/api/v1/telemetry/trigger-welcome-message`       | Build the onboarding message for a newly registered operator |
| POST | `/api/v1/telemetry/meter-change`                  | Return meter-change reason prompts/options |
| POST | `/api/v1/telemetry/issue-report`                  | Return issue-report prompt/options |
| POST | `/api/v1/telemetry/issue-report/submit`           | Save the issue report details provided by the contact |
| POST | `/api/v1/telemetry/issue-report/telemetry`        | Return telemetry-specific issue-report prompt/options |
| POST | `/api/v1/telemetry/issue-report/telemetry/submit` | Save telemetry issue report details |
| POST | `/api/v1/telemetry/meter/issue-report`            | Return telemetry issue-report reasons (JSON list) |
| POST | `/api/v1/telemetry/meter/meter-change`            | Return meter-change reasons (JSON list) |
| POST | `/api/v1/telemetry/meter/meter-change/submit`     | Save the selected meter-change reason |
| POST | `/api/v1/telemetry/others`                        | Return the “other issue” prompt/options |
| POST | `/api/v1/telemetry/others/submitted`              | Save “other issue” details |
| POST | `/api/v1/telemetry/take-meter-reading`            | Return the take‑meter‑reading prompt/options |
| POST | `/api/v1/telemetry/manual-reading`                | Submit a manual meter reading |
| POST | `/api/v1/telemetry/location`                      | Submit/update location details for a contact. Response carries `locationMismatch` — see [location-affinity-check.md](../docs/location-affinity-check.md) |
| POST | `/api/v1/telemetry/update-previous-reading`       | Update the previous reading for a contact |

---

### Telemetry service — republishing ELM and PDU readings (operations)

`POST /api/v1/telemetry/internal/readings/republish` sends a tenant's stored ELM and PDU readings to
analytics again, so their water quantities are recalculated from the formula and pump data configured
now. Use it after setting up a tenant's ELM formula or a scheme's pumps or `k_factor`: readings stored
before that have no water quantity, and saving the configuration doesn't recalculate them.

It is an operations route, not a partner one, and takes two headers:

- `X-Internal-Token`: the operations token. Configure its SHA-256 hex hash, not the token, in
  `TELEMETRY_INTERNAL_AUTH_TOKEN_HASH`. While that is unset, the route is disabled and every call gets
  `401`. The api-gateway routes `/api/v1/telemetry/**` publicly, so this token is the only thing in
  front of it.
- `X-Tenant-Code`: the tenant's state code, in any case, such as `AS`. The run covers only that tenant.

| Field | Required | Description |
|-------|----------|-------------|
| `fromDate`, `toDate` | yes | Reading dates (`yyyy-MM-dd`), both included, at most 31 days |
| `stateSchemeId`, `centreSchemeId` | no | One scheme, found as on `POST /readings`: the state id first, then the centre id. Left out, every scheme |
| `channel` | no | `ELM` or `PDU`, case-insensitive. Left out, both |

`from_date`, `to_date`, `state_scheme_id` and `centre_scheme_id` are accepted too. A scheme created
automatically for an unknown scheme id can't be named; leave the scheme out to include it.

```json
{"success": true, "data": {"republishedCount": 42, "withheldCount": 1}}
```

`republishedCount` is the number of readings sent to analytics. Each is sent before the response,
and Kafka acknowledges it before the next one goes; the dashboards update once analytics has
processed them. `withheldCount` is the number of quarantined readings, which aren't sent, as on
every other path. Readings go oldest first, and sending the same range again is safe: analytics
updates the reading it already holds instead of adding another.

With `ANALYTICS_READ_FROM_AGGREGATES` on, the dashboards read pre-aggregated tables, which the nightly
job rebuilds only for the last `ANALYTICS_AGG_DAILY_LOOKBACK_DAYS` days (3 by default). After
republishing older dates, re-aggregate them: restart analytics-service with
`ANALYTICS_AGG_BACKFILL_ENABLED=true` and `ANALYTICS_AGG_BACKFILL_START_DATE` set to the earliest
`fromDate`, then turn it off again once its log shows `[aggregation-backfill] DONE`.

Failures return `success: false` with a code in `data.errorCode`:

- `400 VALIDATION_FAILED`: `X-Tenant-Code` or a date is missing, `toDate` is before `fromDate`, or
  the range is longer than 31 days.
- `400 MALFORMED_REQUEST`: the body isn't JSON, or a date isn't `yyyy-MM-dd`.
- `400 CHANNEL_NOT_SUPPORTED`: a channel other than ELM or PDU. BFM readings can't be republished:
  their water quantity doesn't depend on configuration, and older ones would be counted twice.
- `401`, with the body `{"success": false, "message": "Unauthorized"}` and no `data`: the token is
  missing or wrong, or no token is configured.
- `404 TENANT_NOT_FOUND`: no tenant has that state code.
- `404 SCHEME_NOT_FOUND`: the scheme id matches none of the tenant's schemes.
- `503 PROCESSING_FAILED`: Kafka stopped acknowledging readings part-way, so the run stopped.
  `republishedCount` and `withheldCount` count the readings before it stopped, and `notSentCount`
  the ones not sent. Send the same range again.
- `500 PROCESSING_FAILED`: safe to retry.

---

### Telemetry service — releasing quarantined readings (operations)

`POST /api/v1/telemetry/internal/readings/quarantine/release` assesses a tenant's quarantined BFM
readings again with the implausible-supply check as it stands, and releases the ones it accepts:
their quarantine marker is cleared and they are sent to analytics for the first time. Readings it
still finds implausible stay quarantined. Use it for readings quarantined under an earlier rule, or
after correcting a scheme's connection counts. Details and caveats:
[`docs/implausible-water-supply-runbook.md`](../docs/implausible-water-supply-runbook.md) §4.

Same headers as the republish route. The body:

| Field | Required | Description |
|-------|----------|-------------|
| `fromDate`, `toDate` | yes | Reading dates (`yyyy-MM-dd`), both included, at most 31 days |
| `stateSchemeId`, `centreSchemeId` | no | One scheme, found as on `POST /readings`. Left out, every scheme |
| `dryRun` | no | `true` to get the counts a release would give, with nothing changed or sent. Default `false` |

`from_date`, `to_date`, `state_scheme_id`, `centre_scheme_id` and `dry_run` are accepted too.

```json
{"success": true, "data": {"dryRun": false, "releasedCount": 42, "stillQuarantinedCount": 3}}
```

Readings go oldest first, each released before the next is assessed, so release ranges in date
order. Releasing the same range again is safe: released readings are no longer quarantined. With
`ANALYTICS_READ_FROM_AGGREGATES` on, re-aggregate older dates afterwards, as after a republish.

Failures are those of the republish route, without `CHANNEL_NOT_SUPPORTED`. On `503
PROCESSING_FAILED`, `releasedCount` and `stillQuarantinedCount` count the readings before Kafka
stopped acknowledging, and `notSentCount` the ones left quarantined. Release the same range again.

#### Before the first run

ELM and PDU readings that reached analytics before its V56 have fact rows with no source reading id.
Republishing one adds a second fact row instead of updating the first, so the submission counts
twice. Once both telemetry-service and analytics-service from this release are deployed, find them:

```sql
SELECT tenant_id, channel, COUNT(*), MIN(reading_date), MAX(reading_date)
FROM analytics_schema.fact_meter_reading_table
WHERE channel IN (2, 3) AND source_reading_id IS NULL
GROUP BY tenant_id, channel;
```

Channel `2` is ELM and `3` is PDU. For each tenant it returns, delete those rows, then straight away
republish the tenant from its earliest `MIN` to its latest `MAX`, in calls of at most 31 days. Its
dashboards miss those readings until the run finishes.

```sql
DELETE FROM analytics_schema.fact_meter_reading_table
WHERE tenant_id = :tenantId AND channel IN (2, 3) AND source_reading_id IS NULL;
```

Only readings telemetry still holds are sent again, so a fact row whose reading has since been
deleted doesn't come back. Don't link the old rows to their readings by `correlation_id` instead:
the WhatsApp flows share one correlation id across several readings (analytics V48).

---

### Telemetry service — internal endpoints

| Old | New |
|-----|-----|
| `GET /api/telemetry` | — (removed) |
| `POST /api/publish` | — (removed) |

---

### Anomaly service

| Old | New |
|-----|-----|
| `GET /api/anomalies` | `GET /api/v1/anomalies` |
| `POST /api/publish` | `POST /api/v1/publish` |

---

### Message service

| Old | New | Notes |
|-----|-----|-------|
| `GET /api/notifications` | — | Removed; only ever returned placeholder data |
| `POST /api/notifications/send` | `POST /api/v1/message/notifications` | Verb `/send` removed; use POST to collection |
| `POST /api/publish` | `POST /api/v1/message/events` | Renamed to noun; added `/v1/message/` prefix |
