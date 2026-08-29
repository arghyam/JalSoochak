# User Service

**Port:** `8082` | **Module:** `backend/user-service`

## Overview

The User Service manages all identity, authentication, and user lifecycle operations:

- Email + password login (delegates credential validation to Keycloak)
- WhatsApp OTP login for field staff (operators)
- JWT refresh token management with database-backed revocation
- Staff invitations and account activation
- Password reset via email
- Tenant staff management (operators, section officers, district officers)
- Bulk CSV/XLSX upload of pump operators and operator-to-scheme mappings
- PII encryption for phone numbers and user names

---

## Architecture

```
user-service
├── controller/
│   ├── AuthController              – Login, logout, OTP, token refresh
│   ├── UserController              – Profile management, password change
│   ├── InvitationController        – Invite new staff members
│   ├── StaffController             – State admin: list and manage staff
│   └── BulkUploadController        – CSV/XLSX bulk upload endpoints
├── service/
│   ├── AuthService                 – Credential validation and Keycloak token exchange
│   ├── JwtTokenService             – Issue, refresh, and revoke tokens
│   ├── OtpService                  – Generate and validate WhatsApp OTPs
│   ├── UserService                 – User profile CRUD
│   ├── InvitationService           – Generate invite tokens, trigger invitation emails
│   ├── StaffService                – Staff listing and role management
│   └── BulkUploadService           – Parse CSV/XLSX and persist users and mappings
├── repository/
│   ├── UserRepository              – JPA for user_table
│   ├── TokenRepository             – JPA for user_token_table (refresh tokens and OTPs)
│   └── InviteRepository            – JPA for user_invite_table
├── kafka/
│   ├── KafkaProducer               – Publishes to user-service-topic and common-topic
│   └── KafkaConsumer               – Consumes from common-topic
└── config/
    ├── SecurityConfig              – JWT resource server, endpoint security rules
    ├── JwtAuthConverter            – Extracts ROLE_*, TENANT_*, USER_TYPE_* from JWT
    └── KeycloakConfig              – Keycloak admin client for user provisioning
```

---

## REST API

### Authentication

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/v1/auth/login` | Public | Email + password login — returns access JWT and refresh token |
| `POST` | `/api/v1/auth/refresh` | Public | Exchange a refresh token for a new access token |
| `POST` | `/api/v1/auth/logout` | Bearer token | Revoke the refresh token |
| `POST` | `/api/v1/auth/staff/otp` | Public | Request a WhatsApp OTP for staff login |
| `POST` | `/api/v1/auth/staff/otp/verify` | Public | Verify OTP and receive JWT tokens |
| `POST` | `/api/v1/auth/invites/activate` | Public | Activate a staff account using an invite token |
| `POST` | `/api/v1/auth/forgot-password` | Public | Trigger a password reset email |
| `POST` | `/api/v1/auth/reset-password` | Public | Set a new password using a reset token |

**Login Request:**
```json
{
  "email": "admin@example.gov.in",
  "password": "<password>"
}
```

**Login Response:**
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
| `GET` | `/api/v1/users/me` | Bearer token | Get the current user's profile |
| `PATCH` | `/api/v1/users/me` | Bearer token | Update name or language preference |
| `PATCH` | `/api/v1/users/me/password` | Bearer token | Change password |

### Invitations

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/users/invitations` | `STATE_ADMIN` | Invite a new staff member (triggers invitation email) |
| `POST` | `/api/v1/users/invitations/resend` | `STATE_ADMIN` | Resend an invitation email |

### Staff Management

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/tenant/user/staff` | `STATE_ADMIN` | List all staff in the tenant |
| `PUT` | `/api/v1/tenant/user/staff/{id}/role` | `STATE_ADMIN` | Update a staff member's role |
| `DELETE` | `/api/v1/tenant/user/staff/{id}` | `STATE_ADMIN` | Deactivate a staff member |

### Bulk Upload

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `POST` | `/api/v1/state-admin/pump-operators/upload` | `STATE_ADMIN` | Bulk upload pump operators from CSV |
| `POST` | `/api/v1/state-admin/user-scheme-mappings/upload` | `STATE_ADMIN` | Bulk upload operator-to-scheme mappings from CSV/XLSX |

**Pump Operator CSV Format:**
```
name,phone_number,language_id,scheme_ids
Ramesh Kumar,91XXXXXXXXXX,1,101;102
```

---

## Authentication Flows

### Email + Password Login

```
Client  →  POST /auth/login { email, password }
        →  AuthService validates credentials against Keycloak
        →  Keycloak issues access token and ID token
        →  Refresh token UUID stored (hashed) in user_token_table
        →  Access JWT + refresh UUID returned to client
```

### WhatsApp OTP Login (Field Staff)

```
Client  →  POST /auth/staff/otp { phone }
        →  OtpService generates a time-limited OTP
        →  SEND_OTP event published → message-service → Glific → WhatsApp
        →  OTP hash + expiry stored in user_token_table

Client  →  POST /auth/staff/otp/verify { phone, otp }
        →  OtpService validates OTP hash and expiry
        →  If valid: Keycloak impersonation → JWT issued
        →  Access JWT + refresh UUID returned to client
```

### Token Refresh

```
Client  →  POST /auth/refresh { refreshToken: "<uuid>" }
        →  JwtTokenService looks up the hashed UUID in user_token_table
        →  Validates: not revoked, not expired
        →  Issues new Keycloak token pair
        →  Old refresh token invalidated; new one stored
        →  New access JWT + new refresh UUID returned
```

---

## Kafka Events

### Published

**Topic: `user-service-topic`**

| Event Type | Trigger | Key Payload Fields |
|------------|---------|-------------------|
| `USER_CREATED` | New user registered | `eventType`, `userId`, `tenantId`, `userType`, `schemeIds[]` |
| `USER_UPDATED` | Profile or role changed | `eventType`, `userId`, `tenantId`, `userType` |
| `USER_DEACTIVATED` | Staff member deactivated | `eventType`, `userId`, `tenantId` |

**Topic: `common-topic`**

| Event Type | Trigger | Key Payload Fields |
|------------|---------|-------------------|
| `SEND_INVITE_EMAIL` | New staff invited | `recipientEmail`, `inviteToken`, `recipientName`, `tenantTitle` |
| `SEND_REINVITE_EMAIL` | Invite resent | `recipientEmail`, `inviteToken`, `recipientName` |
| `SEND_PASSWORD_RESET_EMAIL` | Forgot password triggered | `recipientEmail`, `resetToken`, `recipientName` |
| `SEND_WELCOME_MESSAGE` | Account activated | `recipientPhone`, `operatorName`, `tenantId`, `whatsappConnectionId` |
| `SEND_OTP` | WhatsApp OTP requested | `recipientPhone`, `expirySeconds` |

### Consumed

**Topic: `common-topic`**

| Event Type | Action |
|------------|--------|
| `WHATSAPP_CONTACT_REGISTERED` | Updates `phone_verification_status` in the user's record |

### Dead-Letter Topics

| Dead-Letter Topic | Failed Events | Common Cause |
|------------------|--------------|-------------|
| `account-email-dlt` | `SEND_INVITE_EMAIL`, `SEND_REINVITE_EMAIL`, `SEND_PASSWORD_RESET_EMAIL` | SendGrid API failure or blank email address |
| `welcome-message-dlt` | `SEND_WELCOME_MESSAGE` | Blank phone number or missing WhatsApp connection ID |

---

## Data Models

### User (`user_table` in tenant schema)

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | UUID | Public identifier |
| `tenant_id` | INTEGER | Reference to `common_schema.tenant_master_table` |
| `title` | BYTEA | AES-256 encrypted full name |
| `title_hash` | VARCHAR | HMAC-SHA256 for equality lookups without decryption |
| `email` | VARCHAR | Login email address |
| `user_type` | INTEGER | Role (1=SUPER_USER, 2=STATE_ADMIN, 3=DISTRICT_OFFICER, 4=SECTION_OFFICER, 5=OPERATOR) |
| `phone_number` | BYTEA | AES-256 encrypted phone number |
| `phone_number_hash` | VARCHAR | HMAC-SHA256 for lookup without decryption |
| `language_id` | INTEGER | Preferred language for notifications |
| `status` | INTEGER | 0=INACTIVE, 1=ACTIVE, 2=SUSPENDED |
| `phone_verification_status` | INTEGER | 0=UNVERIFIED, 1=VERIFIED (WhatsApp) |

### User Token (`user_token_table`)

| Column | Type | Description |
|--------|------|-------------|
| `user_id` | BIGINT | Reference to the user |
| `token_hash` | VARCHAR | SHA-256 hash of the refresh token UUID |
| `token_type` | VARCHAR | `REFRESH` or `OTP` |
| `expires_at` | TIMESTAMP | Token expiry time |
| `revoked` | BOOLEAN | Whether the token has been invalidated |

### User Invite (`user_invite_table`)

| Column | Type | Description |
|--------|------|-------------|
| `tenant_id` | INTEGER | Tenant the invite belongs to |
| `invited_email` | VARCHAR | Email address the invite was sent to |
| `invite_token` | VARCHAR | URL-safe random token (unique) |
| `expires_at` | TIMESTAMP | Token expiry (7 days from creation) |
| `used_at` | TIMESTAMP | Timestamp of activation; null until used |
| `status` | INTEGER | 0=PENDING, 1=USED, 2=EXPIRED |

---

## PII Encryption

Phone numbers and user names are encrypted at rest:

- **Encryption:** AES-256 CBC mode using `PII_ENCRYPTION_KEY` environment variable
- **Search:** HMAC-SHA256 hash stored alongside the encrypted value for equality lookups
- **Logging:** Phone numbers must only appear in `DEBUG` log statements

{% hint style="danger" %}
The `PII_ENCRYPTION_KEY` and `PII_HMAC_KEY` values must be kept secret and rotated according to your organisation's key management policy. Losing these keys means encrypted data cannot be recovered.
{% endhint %}

---

## User Roles

| Role | Description |
|------|-------------|
| `SUPER_USER` | Global platform admin; creates tenants and manages super admins |
| `STATE_ADMIN` | Tenant-wide admin; manages staff, schemes, and configuration |
| `DISTRICT_OFFICER` | Level 2 escalation recipient; district-level dashboard access |
| `SECTION_OFFICER` | Level 1 escalation recipient; section-level dashboard access |
| `OPERATOR` | Field worker; submits daily meter readings via WhatsApp |

---

## Configuration

```yaml
server:
  port: 8082

spring:
  application:
    name: user-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}

keycloak:
  issuer-uri: ${KEYCLOAK_ISSUER_URI}
  auth-server-url: ${KEYCLOAK_AUTH_SERVER_URL}
  realm: jalsoochak-realm
  client-id: jalsoochak-client
  client-secret: ${KEYCLOAK_CLIENT_SECRET}
  admin:
    username: ${KEYCLOAK_ADMIN_USERNAME}
    password: ${KEYCLOAK_ADMIN_PASSWORD}

pii:
  encryption-key: ${PII_ENCRYPTION_KEY}
  hmac-key: ${PII_HMAC_KEY}

kafka:
  bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}

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
| Spring Data JPA | User, token, and invite entity persistence |
| Spring Security + OAuth2 Resource Server | JWT validation |
| Keycloak Admin Client | User provisioning and token exchange |
| Spring Kafka | Event publishing and consumption |
| Apache POI | XLSX parsing for bulk uploads |
| OpenCSV | CSV parsing for bulk uploads |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |
