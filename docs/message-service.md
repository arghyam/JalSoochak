# Message Service

**Port:** `8085` | **Module:** `backend/message-service`

## Overview

The Message Service is the **unified notification delivery layer** of JalSoochak V2. It:

- Consumes notification events from Kafka (`common-topic`)
- Routes events to the appropriate delivery channel (WhatsApp, Email, SMS, or Webhook)
- Manages WhatsApp delivery via the Glific GraphQL API, including automatic session token refresh
- Sends transactional emails via SendGrid using dynamic templates
- Generates escalation summary PDFs (Apache PDFBox) and uploads them to object storage (MinIO/S3)
- Provides a fully localised notification pipeline — messages are sent in the operator's preferred language

Unlike other services, the Message Service uses **Spring WebFlux** (Project Reactor) for all outbound HTTP calls, ensuring non-blocking I/O for high-throughput notification delivery.

---

## Architecture

```
message-service
├── controller/
│   ├── NotificationController     – REST: list and manually trigger notifications
│   └── EventController            – REST: dispatch Kafka events (admin use)
├── service/
│   ├── NotificationEventRouter    – Kafka consumer; routes events to the correct channel
│   ├── EscalationPdfService       – Generate PDF reports of operators with missed readings
│   └── MinioStorageService        – Upload files to MinIO or S3-compatible storage
├── channel/
│   ├── GlificGraphQLClient        – WhatsApp delivery via Glific GraphQL API (WebClient)
│   ├── GlificAuthService          – Session token management with auto-refresh on 401
│   ├── SendGridEmailChannel       – Email delivery via SendGrid REST API (WebClient)
│   ├── WebhookChannel             – Generic HTTP webhook delivery (WebClient)
│   └── SmsCountryChannel          – SMS delivery via SMSCountry REST API (WebClient)
├── repository/
│   └── NotificationRepository     – JPA for notification_table (per-tenant schema)
├── entity/
│   └── Notification               – notification_table entity
├── kafka/
│   ├── KafkaConsumer              – Listens to common-topic; passes events to EventRouter
│   └── KafkaProducer              – Publishes WHATSAPP_CONTACT_REGISTERED to common-topic
└── config/
    ├── SecurityConfig             – JWT resource server
    ├── WebClientConfig            – WebClient beans with configured timeouts
    └── MinioConfig                – S3Client bean for object storage
```

---

## Event Routing

`NotificationEventRouter` is the central dispatch hub. It consumes from `common-topic` and routes each event to the appropriate channel:

| Event Type | Delivery Channel | Action |
|------------|-----------------|--------|
| `NUDGE` | WhatsApp (Glific) | Send localised HSM reminder to operator |
| `ESCALATION` | WhatsApp (Glific) | Generate PDF → upload to MinIO → send document HSM to officer |
| `SEND_INVITE_EMAIL` | Email (SendGrid) | Staff invitation email with activation link |
| `SEND_REINVITE_EMAIL` | Email (SendGrid) | Resend invitation email |
| `SEND_PASSWORD_RESET_EMAIL` | Email (SendGrid) | Password reset email |
| `SEND_WELCOME_MESSAGE` | WhatsApp (Glific) | Welcome message on account activation |
| `SEND_OTP` | WhatsApp (Glific) | One-time password for staff login |

---

## Delivery Channels

### WhatsApp via Glific

**Technology:** Spring WebFlux `WebClient` → Glific GraphQL API

**Authentication:** Glific session tokens are acquired at startup via `GlificAuthService` and automatically refreshed when a `401` response is received.

**Nudge delivery** (`sendHsmMessage`):
- Template variables: `{{1}}` = operator name, `{{2}}` = date

**Escalation delivery** (two-step process):
1. `createMessageMedia(publicUrl)` → Glific registers the PDF and returns a `mediaId`
2. `createAndSendMessage(templateId, mediaId, officerContactId, [localizedText])` → sends the document HSM

**Operator contact registration:** On the first WhatsApp delivery to an operator, if they do not yet have a Glific contact ID:
1. Call `createContact(phone)` → receive Glific `contactId`
2. Store `contactId` in the user record
3. Publish `WHATSAPP_CONTACT_REGISTERED` back to `common-topic`

{% hint style="info" %}
Required environment variables: `GLIFIC_API_URL`, `GLIFIC_API_KEY`, `GLIFIC_NUDGE_TEMPLATE_ID`, `GLIFIC_ESCALATION_TEMPLATE_ID`, `GLIFIC_NUDGE_FLOW_ID`
{% endhint %}

### Email via SendGrid

**Technology:** Spring WebFlux `WebClient` → SendGrid Transactional Mail API

| Event | Template Used | Dynamic Data Fields |
|-------|--------------|---------------------|
| `SEND_INVITE_EMAIL` | Invitation template | `recipientName`, `activationUrl`, `tenantTitle` |
| `SEND_REINVITE_EMAIL` | Invitation template | `recipientName`, `activationUrl` |
| `SEND_PASSWORD_RESET_EMAIL` | Password reset template | `recipientName`, `resetUrl` |

{% hint style="info" %}
Required environment variables: `SENDGRID_API_KEY`, `SENDGRID_TEMPLATE_DEFAULT_INVITATION`, `SENDGRID_TEMPLATE_PASSWORD_RESET`
{% endhint %}

### SMS via SMSCountry

Used as a fallback delivery channel when WhatsApp is unavailable.

{% hint style="info" %}
Required environment variables: `SMSCOUNTRY_BASE_URL`, `SMSCOUNTRY_AUTH_KEY`, `SMSCOUNTRY_AUTH_TOKEN`, `SMSCOUNTRY_SENDER_ID`
{% endhint %}

### Generic Webhook

Delivers notifications to custom HTTP endpoints configured per tenant. Useful for integration with third-party systems (e.g. state government portals).

---

## Escalation PDF Generation

`EscalationPdfService` generates a structured PDF using Apache PDFBox:

**PDF Contents:**
- Header: Tenant name, report date, escalation level (L1 or L2)
- Table: Operator name, scheme name, number of days missed, date of last reading
- Footer: Report generation timestamp

**End-to-End Pipeline:**

```
ESCALATION event received
       ↓
EscalationPdfService.generate(operators[])
       ↓ PDF bytes
MinioStorageService.upload(bytes, filename) → publicUrl
       ↓
GlificGraphQLClient.createMessageMedia(publicUrl) → mediaId
       ↓
GlificGraphQLClient.createAndSendMessage(
    templateId, mediaId, officerContactId, [localizedBodyText]
)
       ↓
Officer receives WhatsApp message with PDF attachment
```

---

## REST API

| Method | Endpoint | Required Role | Description |
|--------|----------|--------------|-------------|
| `GET` | `/api/v1/message/notifications` | `STATE_ADMIN` | List notification history for the tenant |
| `POST` | `/api/v1/message/notifications` | `STATE_ADMIN` | Manually trigger a notification |
| `POST` | `/api/v1/message/events` | `SUPER_USER` | Dispatch a Kafka event directly (admin use) |

---

## Data Models

### Notification (`notification_table` in tenant schema)

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | UUID | Public identifier |
| `tenant_id` | INTEGER | Owning tenant |
| `user_id` | BIGINT | Recipient user |
| `message_type` | VARCHAR | `NUDGE`, `ESCALATION`, `OTP`, `INVITE`, `WELCOME` |
| `channel` | VARCHAR | `whatsapp`, `email`, `sms`, `webhook` |
| `status` | INTEGER | `0`=PENDING, `1`=SENT, `2`=FAILED, `3`=DELIVERED |
| `payload` | JSONB | Full event payload (for audit trail and retry) |
| `error_message` | VARCHAR | Delivery error description if status = FAILED |
| `sent_at` | TIMESTAMP | Timestamp of successful delivery |

---

## Language Resolution

Nudge and escalation messages are sent in the operator's preferred language:

```
Event contains languageId (e.g. 2)
         ↓
Query tenant_config: key = "language_2" → "Hindi"
         ↓
Normalise: "Hindi" → "hindi"
         ↓
Query tenant_config: key = "nudge_message_hindi" → localised text
         ↓
If not found → fallback to "nudge_message_english"
         ↓
If still not found → use built-in generic default
```

---

## Dead-Letter Queues

When message delivery fails after retries, events are routed to dead-letter topics for manual review:

| Dead-Letter Topic | Failed Event Types | Common Cause |
|------------------|--------------------|-------------|
| `welcome-message-dlt` | `SEND_WELCOME_MESSAGE` | Blank phone number, missing WhatsApp connection ID |
| `account-email-dlt` | All `SEND_*_EMAIL` events | SendGrid API failure, blank email address |

---

## Configuration

```yaml
server:
  port: 8085

spring:
  application:
    name: message-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}

glific:
  api-url: ${GLIFIC_API_URL}
  api-key: ${GLIFIC_API_KEY}
  nudge-template-id: ${GLIFIC_NUDGE_TEMPLATE_ID}
  escalation-template-id: ${GLIFIC_ESCALATION_TEMPLATE_ID}
  nudge-flow-id: ${GLIFIC_NUDGE_FLOW_ID}
  timeout-ms: ${GLIFIC_TIMEOUT_MS:15000}

sendgrid:
  api-key: ${SENDGRID_API_KEY}
  from-email: ${SENDGRID_FROM_EMAIL}
  from-name: ${SENDGRID_FROM_NAME:JalSoochak}
  templates:
    invitation: ${SENDGRID_TEMPLATE_DEFAULT_INVITATION}
    password-reset: ${SENDGRID_TEMPLATE_PASSWORD_RESET}

minio:
  endpoint: ${MINIO_ENDPOINT}
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  bucket: ${MINIO_BUCKET}
  base-url: ${MINIO_BASE_URL}

smscountry:
  base-url: ${SMSCOUNTRY_BASE_URL}
  auth-key: ${SMSCOUNTRY_AUTH_KEY}
  auth-token: ${SMSCOUNTRY_AUTH_TOKEN}
  sender-id: ${SMSCOUNTRY_SENDER_ID}

# Set to true to log notification events without sending actual messages.
# Recommended for staging environments.
notifications:
  dry-run: ${NOTIFICATIONS_DRY_RUN:false}

kafka:
  bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}

eureka:
  client:
    enabled: ${EUREKA_ENABLED:true}
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
```

{% hint style="success" %}
Set `NOTIFICATIONS_DRY_RUN=true` on staging or QA environments to verify event routing and language resolution without sending real WhatsApp messages or emails.
{% endhint %}

{% hint style="danger" %}
Phone numbers are PII. They must never appear in `INFO`, `WARN`, or `ERROR` log statements anywhere in this service — including notification payload logs.
{% endhint %}

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| Spring WebFlux | Non-blocking `WebClient` for all outbound HTTP calls |
| Spring Data JPA | Notification record persistence |
| Spring Kafka | Event consumption and dead-letter publishing |
| Spring Security + OAuth2 Resource Server | JWT authentication |
| Apache PDFBox | Escalation PDF generation |
| AWS SDK v2 S3 | MinIO / S3 file uploads |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |
