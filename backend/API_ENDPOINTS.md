# JalSoochak V2 — API Endpoints Reference

All endpoints follow the pattern `/api/v1/<resource>/...`.
When accessed through the API gateway, prefix each path with the service slug (e.g. `/tenant/api/v1/...`).

---

## Required Environment Variables

Set the following environment variables before running the Telemetry services:

- `GLIFIC_API_URL`
- `GLIFIC_API_KEY`
- `GLIFIC_NUDGE_TEMPLATE_ID`
- `GLIFIC_ESCALATION_TEMPLATE_ID`
- `MINIO_ENDPOINT`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `MINIO_BUCKET`
- `MINIO_BASE_URL`

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
| POST | `/api/v1/tenant/user/welcome` | Send welcome message to staff |

### Pump Operators

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/pumpoperator/pump-operators/{id}` | Get pump operator by ID |
| GET | `/api/v1/pumpoperator/pump-operators/{id}/reading-compliance` | Reading compliance for one operator |
| GET | `/api/v1/pumpoperator/pump-operators/{id}/details-with-compliance` | Operator details + compliance |
| GET | `/api/v1/pumpoperator/pump-operators/reading-compliance` | Reading compliance for all operators |
| GET | `/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance` | Compliance grouped by scheme |
| GET | `/api/v1/pumpoperator/pump-operators/by-scheme` | Operators grouped by scheme |
| POST | `/api/v1/state-admin/pump-operators/upload` | Bulk upload pump operators (CSV) |

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

### System Config

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/system/config` | Get system config |
| GET | `/api/v1/system/channels` | Get available channels |
| PUT | `/api/v1/system/config` | Update system config |

---

## Schemes (`scheme-service` · port 8086)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/public/schemes/{schemeId}` | Get scheme by ID (public) |
| GET | `/api/v1/scheme/schemes` | List all schemes |
| GET | `/api/v1/scheme/schemes/mappings` | List scheme mappings |
| GET | `/api/v1/scheme/schemes/counts` | Scheme counts |
| GET | `/api/v1/scheme/schemes/counts/by-status` | Scheme counts by status |
| POST | `/api/v1/scheme/schemes/upload` | Bulk upload schemes (CSV) |
| POST | `/api/v1/scheme/schemes/mappings/upload` | Bulk upload scheme mappings (CSV) |

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
| GET | `/api/v1/analytics/scheme-regularity/periodic/national` | National periodic scheme regularity |
| POST | `/api/v1/analytics/date-dimension/populate` | Populate date dimension table |
| GET | `/api/v1/analytics/schemes/status-count` | Scheme status counts |
| GET | `/api/v1/analytics/schemes/dashboard` | Scheme dashboard |
| GET | `/api/v1/analytics/schemes/region-report` | Scheme region report |
| GET | `/api/v1/analytics/escalations` | Escalation analytics |
| GET | `/api/v1/analytics/scheme-performance` | Scheme performance metrics |
| GET | `/api/v1/analytics/tenants` | Tenant analytics |
| GET | `/api/v1/analytics/tenant_data` | Tenant data |
| GET | `/api/v1/analytics/schemes` | Analytics schemes list |
| GET | `/api/v1/analytics/meter-readings` | Meter readings analytics |
| GET | `/api/v1/analytics/scheme-regularity/average` | Average scheme regularity |
| GET | `/api/v1/analytics/scheme-regularity/periodic` | Periodic scheme regularity |
| GET | `/api/v1/analytics/reading-submission-rate` | Reading submission rate |
| GET | `/api/v1/analytics/anomalies/statuses` | Anomaly statuses list |
| GET | `/api/v1/analytics/escalations/statuses` | Escalation statuses list |

---

### Telemetry · Internal

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/telemetry` | List telemetry records |
| POST | `/api/v1/publish` | Dispatch a Kafka event |

---

## Message (`message-service` · port 8085)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/message/notifications` | List notifications |
| POST | `/api/v1/message/notifications` | Send a notification (specify channel in body) |
| POST | `/api/v1/message/events` | Dispatch a Kafka event |

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

### Telemetry service — webhook base URL (affects all 23 Glific webhook endpoints)

> ## Telemetry · Webhook (`telemetry-service` · port 8084)
> These endpoints are called by **Glific** (WhatsApp bot platform), not by the frontend.

| Method | Endpoint                                          | Description |
|--------|---------------------------------------------------|-------------|
| POST | `/api/v1/telemetry/readings`                      | Receive the generic Glific webhook payload for image-based meter readings |
| POST | `/api/v1/telemetry/intro`                         | Send the flow intro message for a contact |
| POST | `/api/v1/telemetry/closing`                       | Send the flow closing message for a contact |
| POST | `/api/v1/telemetry/language/selection`            | Return the language selection prompt/options for a contact |
| POST | `/api/v1/telemetry/selected/language`             | Persist the selected language for a contact |
| POST | `/api/v1/telemetry/channel/selection`             | Return the channel selection prompt/options for a contact |
| POST | `/api/v1/telemetry/selected/channel`              | Persist the selected channel for a contact |
| POST | `/api/v1/telemetry/item/selection`                | Return the item selection prompt/options for a contact |
| POST | `/api/v1/telemetry/selected/item`                 | Persist the selected item for a contact |
| POST | `/api/v1/telemetry/meter-change`                  | Return meter-change reason prompts/options |
| POST | `/api/v1/telemetry/issue-report`                  | Return issue-report prompt/options |
| POST | `/api/v1/telemetry/issue-report/submit`           | Save the issue report details provided by the contact |
| POST | `/api/v1/telemetry/issue-report/telemetry`        | Return telemetry-specific issue-report prompt/options |
| POST | `/api/v1/telemetry/issue-report/telemetry/submit` | Save telemetry issue report details |
| POST | `/api/v1/telemetry/meter/issue-report`            | Return telemetry issue-report reasons (JSON list) |
| POST | `/api/v1/telemetry/meter/change`                  | Return meter-change reasons (JSON list) |
| POST | `/api/v1/telemetry/meter/change/submit`           | Save the selected meter-change reason |
| POST | `/api/v1/telemetry/others`                        | Return the “other issue” prompt/options |
| POST | `/api/v1/telemetry/others/submitted`              | Save “other issue” details |
| POST | `/api/v1/telemetry/take-meter-reading`            | Return the take‑meter‑reading prompt/options |
| POST | `/api/v1/telemetry/manual-reading`                | Submit a manual meter reading |
| POST | `/api/v1/telemetry/location`                      | Submit/update location details for a contact |
| POST | `/api/v1/telemetry/update-previous-reading`       | Update the previous reading for a contact |

---

### Telemetry service — internal endpoints

| Old | New |
|-----|-----|
| `GET /api/telemetry` | `GET /api/v1/telemetry` |
| `POST /api/publish` | `POST /api/v1/publish` |

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
| `GET /api/notifications` | `GET /api/v1/message/notifications` | Added `/v1/message/` prefix |
| `POST /api/notifications/send` | `POST /api/v1/message/notifications` | Verb `/send` removed; use POST to collection |
| `POST /api/publish` | `POST /api/v1/message/events` | Renamed to noun; added `/v1/message/` prefix |
