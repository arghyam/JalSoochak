# Users & Tenancy

## 1. Multi-Tenancy Model

Each Indian state that deploys JalSoochak operates as an independent **tenant**. Tenants are fully isolated from each other at the database level using PostgreSQL's schema-per-tenant approach.

Each tenant has:

* Its own PostgreSQL schema (`tenant_<stateCode>`) containing all operational data
* Its own configuration (languages, water norms, escalation thresholds, notification templates, cron schedules)
* Its own user hierarchy (operators, section officers, district officers, state admin)
* Its own scheme and location data
* Its own analytics data in the shared analytics warehouse, scoped by `tenant_id`

New tenants are provisioned by a Super User via the API — a dedicated schema is created automatically and the tenant moves through a defined lifecycle before becoming active.

---

## 2. Tenant Lifecycle

| Status Code | Status | Description |
|-------------|--------|-------------|
| `0` | `INACTIVE` | Tenant registered but not yet configured |
| `1` | `ONBOARDED` | PostgreSQL schema provisioned |
| `2` | `CONFIGURED` | Configuration keys set (languages, norms, templates) |
| `3` | `ACTIVE` | Fully operational; field operations running |
| `4` | `SUSPENDED` | Temporarily disabled |
| `5` | `DEGRADED` | Partial service failure (e.g. Glific integration down) |
| `6` | `ARCHIVED` | Retired tenant |

### Onboarding Sequence

```
1. Super User: POST /api/v1/tenants
      → PostgreSQL schema provisioned (status: ONBOARDED)

2. State Admin: Upload LGD location hierarchy CSV
      → State, District, Block, Panchayat, Village nodes created

3. State Admin: Upload departmental hierarchy CSV
      → Zone, Circle, Division, Sub-Division nodes created

4. State Admin: Bulk upload water supply schemes CSV
      → scheme_master_table populated

5. State Admin: Bulk upload pump operators CSV
      → user_table populated (phone numbers encrypted at rest)

6. State Admin: Upload operator-to-scheme mappings CSV
      → user_scheme_mapping_table populated

7. State Admin: Configure tenant settings
      → languages, water_norm, escalation thresholds, cron expressions set

8. Super User: PUT /api/v1/tenants/{id} (status → ACTIVE)
      → Nudge/escalation schedulers activate for this tenant
```

---

## 3. User Roles

JalSoochak distinguishes between two categories of user roles:

### 3.1 System User Roles (Login Access)

These roles have login credentials and use the web dashboard or API:

| Role | Access Level | Description |
|------|-------------|-------------|
| `SUPER_USER` | Global | Platform-level admin; creates tenants, manages super admins; can view all tenants |
| `STATE_ADMIN` | Tenant-wide | Full admin for a single state tenant; manages staff, schemes, configuration, and analytics |
| `DISTRICT_OFFICER` | District-level | Read access to district dashboard; receives Level 2 escalation WhatsApp alerts |
| `SECTION_OFFICER` | Section-level | Read access to section dashboard; receives Level 1 escalation WhatsApp alerts |

### 3.2 Field User Roles

These roles interact with the platform primarily through WhatsApp:

| Role | Access Level | Description |
|------|-------------|-------------|
| `OPERATOR` (Pump Operator) | Scheme-level | Submits daily BFM meter readings via WhatsApp; receives nudge reminders |

Pump operators are bulk-uploaded via CSV by the State Admin. They do not receive email invitations and do not use the web dashboard. Their primary interface is the Glific WhatsApp flow.

---

## 4. Role-Based Access Control

Access to every API endpoint is enforced by Spring Security using claims extracted from the Keycloak JWT:

| JWT Claim | Maps To | Used For |
|-----------|---------|---------|
| `realm_access.roles` | `ROLE_SUPER_USER`, `ROLE_STATE_ADMIN` | Global platform roles |
| `tenant_state_code` | `TENANT_<STATE_CODE>` | Restricts data access to the correct tenant schema |
| `user_type` | `USER_TYPE_<TYPE>` | Functional role within a tenant |

### Endpoint Access Matrix

| Feature | SUPER_USER | STATE_ADMIN | DISTRICT_OFFICER | SECTION_OFFICER | OPERATOR |
|---------|:---:|:---:|:---:|:---:|:---:|
| Create / manage tenants | ✓ | — | — | — | — |
| Tenant configuration | ✓ | ✓ | — | — | — |
| Staff management | — | ✓ | — | — | — |
| Scheme management | — | ✓ | — | — | — |
| View scheme list | — | ✓ | ✓ | ✓ | — |
| Submit readings (web) | — | ✓ | — | ✓ | — |
| Submit readings (WhatsApp) | — | — | — | — | ✓ |
| National analytics | ✓ | — | — | — | — |
| State analytics | ✓ | ✓ | — | — | — |
| District analytics | — | ✓ | ✓ | — | — |

---

## 5. User Provisioning

### 5.1 Staff (Section Officers, District Officers, State Admins)

Staff members are invited by email:

1. State Admin sends an invitation via the dashboard (`POST /api/v1/users/invitations`)
2. A secure invite token is generated (expires in 7 days)
3. The staff member receives an email with an activation link
4. Staff sets their password and activates their Keycloak account
5. On activation, a `SEND_WELCOME_MESSAGE` event is published and a WhatsApp welcome message is sent

### 5.2 Pump Operators

Pump operators are provisioned in bulk:

1. State Admin uploads a CSV via the dashboard (`POST /api/v1/state-admin/pump-operators/upload`)
2. Operator records are created in `user_table` with phone numbers AES-256 encrypted
3. Operator-to-scheme mappings are uploaded separately
4. On the first WhatsApp nudge delivery, Glific contact registration occurs and the resulting `contactId` is stored back in `user_table.whatsapp_connection_id`

**CSV format:**
```
name,phone_number,language_id,scheme_ids
Ramesh Kumar,91XXXXXXXXXX,1,101;102
```

---

## 6. Authentication Flows

### 6.1 Email + Password (Staff)

```
POST /api/v1/auth/login { email, password }
  → Credentials validated against Keycloak
  → Access JWT + refresh UUID returned
  → Refresh token stored hashed in user_token_table
```

### 6.2 WhatsApp OTP (Field Staff)

```
POST /api/v1/auth/staff/otp { phone }
  → OTP generated and SEND_OTP event published → WhatsApp OTP delivered
  → OTP hash + expiry stored in user_token_table

POST /api/v1/auth/staff/otp/verify { phone, otp }
  → OTP hash and expiry validated
  → Keycloak impersonation → JWT issued
  → Access JWT + refresh UUID returned
```

### 6.3 Token Refresh

Refresh tokens use a rotation scheme — each use issues a new token and invalidates the old one:

```
POST /api/v1/auth/refresh { refreshToken: "<uuid>" }
  → Hashed UUID looked up in user_token_table
  → Validated: not revoked, not expired
  → New Keycloak token pair issued
  → Old refresh token revoked; new one stored
```

---

## 7. PII Protection for User Data

Phone numbers and user names are PII and are protected at rest:

| Field | How Stored | How Queried |
|-------|-----------|------------|
| Phone number | AES-256 CBC encrypted (BYTEA) | HMAC-SHA256 hash in `phone_number_hash` |
| User name / title | AES-256 CBC encrypted (BYTEA) | HMAC-SHA256 hash in `title_hash` |

{% hint style="danger" %}
Phone numbers must **never** appear in `INFO`, `WARN`, or `ERROR` log statements — in application code, Kafka event payloads logged at INFO level, or test helpers. They may only appear at `DEBUG` level.
{% endhint %}
