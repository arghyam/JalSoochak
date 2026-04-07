# Notification Flows

This document covers the four WhatsApp notification pipelines in JalSoochak V2: nudges, escalations, welcome messages, and login OTPs. All four flows share the same Kafka transport layer — events are published to `common-topic` and consumed by `message-service`, which routes them to the Glific WhatsApp API.

---

## Architecture Overview

```text
tenant-service          user-service           (external / telemetry)
      |                       |                         |
  [cron jobs]           [bulk upload]             [OTP trigger]
      |                       |                         |
      +----------+------------+-------------------------+
                 |
          Kafka: common-topic
                 |
          message-service
          NotificationEventRouter
                 |
         GlificWhatsAppService
                 |
     GlificGraphQLClient (WebClient)
                 |
           Glific API (GraphQL)
                 |
           WhatsApp (operator/officer)
```

**Glific authentication** (`GlificAuthService`) logs in on service startup via `POST /api/v1/session`, stores an `access_token` and `renewal_token`, and auto-refreshes on 401 responses. The `GlificGraphQLClient` enforces a minimum interval between requests (`glific.request-interval-ms`, default 500 ms) and retries up to three times on 429 rate-limit responses using exponential back-off (5 s → 10 s → 20 s with ±1 s jitter).

---

## 1. Nudges

### Purpose

Sends a WhatsApp nudge to every pump operator who has not submitted a flow reading for the current day. The message arrives as an interactive Glific flow with clickable buttons rather than a plain text HSM.

### Trigger

`TenantSchedulerManager` starts on service startup (`@PostConstruct`), reads all active tenants from `common_schema`, and schedules one nudge cron job per tenant using the hour and minute read from `common_schema.tenant_config_master_table`. Missing config falls back to application defaults. The cron expression runs in the **Asia/Kolkata** timezone.

Calling `TenantSchedulerManager.rescheduleForTenant(tenantId, stateCode)` after updating a tenant's config applies the new schedule immediately without a restart.

### tenant-service: NudgeSchedulerService

`processNudgesForTenant(schema, tenantId)` is called by the scheduler.

**Database query** (`NudgeRepository.streamUsersWithNoUploadToday`):

```sql
SELECT u.id, u.title, u.phone_number, u.language_id,
       u.whatsapp_connection_id, usm.scheme_id
FROM <schema>.user_scheme_mapping_table usm
JOIN <schema>.user_table u ON u.id = usm.user_id
JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
LEFT JOIN <schema>.flow_reading_table fr
    ON fr.scheme_id = usm.scheme_id
    AND fr.created_by = u.id
    AND fr.reading_date = <today>
WHERE usm.status = 1
  AND UPPER(ut.c_name) = 'PUMP_OPERATOR'
  AND fr.id IS NULL
```

Uses a server-side cursor (fetch size 500) to avoid materialising large result sets into heap.

**Kafka event** published to `common-topic` for each matching operator:

```json
{
  "eventType": "NUDGE",
  "recipientPhone": "<phone in E.164>",
  "operatorName": "<user_table.title>",
  "schemeId": "<scheme_id>",
  "tenantId": 42,
  "languageId": 2,
  "userId": 1001,
  "whatsappConnectionId": 98765,
  "tenantSchema": "tenant_mp"
}
```

Operators who have neither a phone nor a stored `whatsapp_connection_id` are silently skipped.

### message-service: handleNudge

1. Reads `recipientPhone` and `whatsappConnectionId` from the event.
2. **Contact ID resolution**:
   - If `whatsappConnectionId > 0` — use it directly (fast path, no Glific API call).
   - Otherwise — call `GlificWhatsAppService.optIn(phone)` to register or look up the contact in Glific, then publish a `WHATSAPP_CONTACT_REGISTERED` event back to `common-topic` so tenant-service can persist the new contact ID.
3. Calls `WhatsAppChannel.sendNudgeViaFlow(contactId, operatorName, todayDate)`.
4. This triggers `GlificWhatsAppService.startNudgeFlow`, which executes the `startContactFlow` GraphQL mutation:

   ```graphql
   mutation startContactFlow($flowId: ID!, $contactId: ID!, $defaultResults: Json!) {
     startContactFlow(flowId: $flowId, contactId: $contactId, defaultResults: $defaultResults) {
       success
       errors { key message }
     }
   }
   ```

   The `defaultResults` JSON carries `{"name": "<operatorName>", "date": "<dd MMMM yyyy>"}`. These values are available as flow variables inside the Glific nudge flow.

**Config required** (`glific.flow.nudge-id`): the Glific flow ID. The service fails to start if this is blank.

### WHATSAPP_CONTACT_REGISTERED feedback loop

When `optIn` is called (first nudge for a new operator), message-service publishes:

```json
{
  "eventType": "WHATSAPP_CONTACT_REGISTERED",
  "tenantSchema": "tenant_mp",
  "userId": 1001,
  "contactId": 98765
}
```

tenant-service `KafkaConsumer` receives this and calls `NudgeRepository.updateWhatsAppConnectionId`, persisting the Glific contact ID into `user_table.whatsapp_connection_id`. Subsequent nudges skip the `optIn` call.

---

## 2. Escalations

### Purpose

Sends a PDF report via WhatsApp document HSM to the responsible officer when pump operators under their supervision have missed consecutive days of readings. Two escalation levels exist:

- **Level 1** — operator missed ≥ `level1Days` days → notifies the section officer (default: `SECTION_OFFICER`).
- **Level 2** — operator missed ≥ `level2Days` days, or has **never** uploaded → notifies the district officer (default: `DISTRICT_OFFICER`). Never-uploaded operators always go straight to level 2.

Both thresholds and officer role names are read from tenant config at job execution time.

### Trigger

Same `TenantSchedulerManager` as nudges, but a separate cron schedule (`escalation` config key). Runs in **Asia/Kolkata** timezone.

### tenant-service: EscalationSchedulerService

`processEscalationsForTenant(schema, tenantId)` runs the following steps:

1. Loads escalation config from `TenantConfigService` (`level1Days`, `level2Days`, `level1OfficerType`, `level2OfficerType`).
2. Pre-loads officer rows for both levels in two bulk queries (`NudgeRepository.findAllOfficersByUserType`) to avoid N+1 lookups inside the operator stream.
3. Streams all operators who have missed ≥ `level1Days` days (or never uploaded) via `NudgeRepository.streamUsersWithMissedDays`.
4. For each operator, determines the escalation level and groups them by officer using the key `"LEVEL_<n>|<officerPhone>"`.
5. After streaming, publishes **one `EscalationEvent` per officer group** to `common-topic`.

**Operator correlation IDs** are deterministic UUID v3 values derived from `schema:schemeId:userId:NO_SUBMISSION:<streakStart>`, enabling idempotent downstream deduplication.

**Kafka event** published to `common-topic`:

```json
{
  "eventType": "ESCALATION",
  "escalationLevel": 1,
  "officerPhone": "<phone>",
  "officerName": "<name>",
  "officerUserType": "SECTION_OFFICER",
  "officerLanguageId": 1,
  "officerId": 500,
  "officerWhatsappConnectionId": 11111,
  "tenantId": 42,
  "tenantSchema": "tenant_mp",
  "correlationId": "<uuid>",
  "operators": [
    {
      "name": "Ramesh Kumar",
      "phoneNumber": "<phone>",
      "schemeName": "Scheme A",
      "schemeId": "101",
      "soName": "Section Officer Name",
      "consecutiveDaysMissed": 4,
      "lastRecordedBfmDate": "2026-03-20",
      "lastConfirmedReading": 12.5,
      "userId": 1001,
      "correlationId": "<uuid>"
    }
  ]
}
```

`consecutiveDaysMissed` is `null` for never-uploaded operators; `lastRecordedBfmDate` is `"Never"` for them.

### message-service: handleEscalation

1. Parses the `EscalationEvent` and the nested operator list.
2. **PDF generation** (`EscalationPdfService`): generates an A4 PDF using Apache PDFBox, listing each operator's name, phone, scheme name, scheme ID, SO name, days missed, and last BFM date. Multi-page pagination is automatic. File is saved to `escalation.report.dir` (default: `/tmp/escalation-reports/`) as `escalation_L<level>_<officerName>_<date>-<uuid>.pdf`.
3. **MinIO upload** (`MinioStorageService`): uploads the PDF and returns a public URL (`<minio.base-url>/<bucket>/<filename>`). The local file is deleted only after the upload returns successfully; if the upload throws, an error is logged, the local file is retained for manual recovery, and the exception is rethrown.
4. **Contact ID resolution** (same logic as nudge): uses `officerWhatsappConnectionId` if set, otherwise calls `optIn` and publishes `WHATSAPP_CONTACT_REGISTERED`.
5. **Glific document HSM** — Java entry point: `GlificWhatsAppService.sendEscalationHsm(contactId, minioUrl)`. Internally two GraphQL mutations are executed in sequence:
   - Step 1 (`uploadMedia` helper → `createMessageMedia` GraphQL mutation): registers the MinIO PDF URL with Glific (`url`, `source_url`, `caption`, `thumbnail`, `isTemplateMedia=true`) and receives a `mediaId`.
   - Step 2 (`createAndSendMessage` GraphQL mutation): sends the HSM with `templateId`, `receiverId`, `isHsm=true`, and the `mediaId` from Step 1 as the document header attachment. The params list is empty because the body text is baked into the template.

**Config required**:
- `glific.template.escalation-id`: Glific template ID for the document HSM.
- `minio.*`: endpoint, access key, secret key, bucket, base URL.
- `app.base-url`: publicly reachable URL; a warning is logged at startup if it resolves to localhost.

---

## 3. Welcome Messages

### Purpose

Sends a Glific onboarding welcome flow to newly registered pump operators. The flow introduces the Jalmitra application and sets up the operator's preferred language in Glific.

### Trigger

Published by `user-service` (`UserEventPublisher.publishPumpOperatorOnboardedAfterCommit`) after a successful bulk pump-operator upload and DB commit. The publisher batches phones into groups of up to 1000 and emits two Kafka events per batch: `UPDATE_USER_LANGUAGE` (updates the contact's language in Glific) followed by `SEND_WELCOME_MESSAGE`.

### Kafka event

```json
{
  "eventType": "SEND_WELCOME_MESSAGE",
  "tenantCode": "mp",
  "tenantId": 42,
  "glificLanguageId": "3",
  "pumpOperatorPhones": ["919876543210", "919876543211"]
}
```

### message-service: handleSendWelcomeMessage

For each phone in the batch:

1. Looks up `whatsapp_connection_id` in `<tenantSchema>.user_table` via a direct JDBC query (`fetchWhatsappConnectionId`). The `tenantCode` is validated against the regex `[a-z0-9_]+` before it is interpolated into the `tenantSchema` string used in the SQL query. This validation prevents SQL injection and unauthorized schema access by ensuring only safe, alphanumeric/underscore schema names can reach the database — without it, a crafted `tenantCode` could escape the schema prefix and query arbitrary tables.
2. If no contact ID is found, the phone is routed to the dead-letter topic `welcome-message-dlt` with reason `no_whatsapp_connection_id` — the batch continues.
3. Calls `GlificWhatsAppService.startWelcomeFlow(contactId)`, which triggers the `startContactFlow` mutation with `flowId = glific.flow.welcome-id` and empty `defaultResults`.

Failures for individual phones are dead-lettered rather than thrown, preventing a single bad record from causing Kafka to retry the entire batch (which would re-send welcome messages to already-succeeded phones).

**Dead-letter record** (`welcome-message-dlt`):

```json
{
  "retryId": "<deterministic-uuid-v3>",
  "eventType": "SEND_WELCOME_MESSAGE_RETRY",
  "tenantSchema": "tenant_mp",
  "failedAt": "2026-03-24T08:00:00Z",
  "errorMessage": "no_whatsapp_connection_id",
  "phone": "<phone>"
}
```

The `retryId` is a deterministic UUID v3 derived from `"SEND_WELCOME_MESSAGE_RETRY:<tenantSchema>:<phone>"` for idempotent downstream reprocessing.

**Config required** (`glific.flow.welcome-id`): the Glific welcome flow ID. The service fails to start if this is blank.

### UPDATE_USER_LANGUAGE (companion event)

Published alongside `SEND_WELCOME_MESSAGE` by user-service. Handled separately by `handleUpdateUserLanguage`:

1. For each phone, looks up `whatsapp_connection_id` from the tenant schema.
2. Calls `GlificWhatsAppService.updateContactLanguage(contactId, glificLanguageId)` (`updateContact` GraphQL mutation).
3. Unlike welcome messages, failures here cause the whole Kafka message to be rethrown (triggering retry/DLT), because `updateContactLanguage` is idempotent — re-setting the same language on a contact that already has it is harmless.

---

## 4. Login OTP (Send OTP)

### Purpose

Delivers a one-time password to an officer via WhatsApp HSM. This is used for phone-based authentication of officers who log into the system.

### Trigger

Any upstream service publishes a `SEND_LOGIN_OTP` event to `common-topic`. The event producer is responsible for generating the OTP and including it in the payload.

### Kafka event

```json
{
  "eventType": "SEND_LOGIN_OTP",
  "officerName": "Suresh Sharma",
  "OTP": "482931",
  "glific_id": "98765",
  "officerPhoneNumber": "919876543210"
}
```

Either `glific_id` or `officerPhoneNumber` must be present. If both are provided, `glific_id` takes priority.

### message-service: handleSendLoginOtp

1. **Contact ID resolution**:
   - If `glific_id` is a valid positive integer — use it directly.
   - If `glific_id` is absent — call `GlificWhatsAppService.optIn(officerPhoneNumber)` to register or look up the contact.
   - If neither is present or both are invalid — log and skip.
2. Calls `WhatsAppChannel.sendLoginOtp(contactId, otp)`.
3. This invokes `GlificWhatsAppService.sendLoginOtpHsm`, which sends a `sendHsmMessage` mutation:

   ```graphql
   mutation sendHsmMessage($templateId: ID!, $receiverId: ID!, $parameters: [String]) {
     sendHsmMessage(templateId: $templateId, receiverId: $receiverId, parameters: $parameters) {
       message { id body isHSM }
       errors { key message }
     }
   }
   ```

   `parameters[0]` = OTP string (template variable `{{1}}`).
4. On failure, throws `IllegalStateException` so the Kafka container applies its retry/back-off policy.

**Config required** (`glific.template.login-otp-id`): the Glific HSM template ID for OTP delivery. The service fails to start if this is blank.

---

## Configuration Reference

All Glific and storage properties can be overridden via environment variables.

| Property | Env var | Required | Description |
|---|---|---|---|
| `glific.auth-url` | `GLIFIC_AUTH_URL` | No | Defaults to `https://api.arghyam.glific.com/api/v1/session` |
| `glific.username` | `GLIFIC_USERNAME` | Yes | Glific login phone |
| `glific.password` | `GLIFIC_PASSWORD` | Yes | Glific login password |
| `glific.api-url` | `GLIFIC_API_URL` | Yes | GraphQL endpoint |
| `glific.flow.nudge-id` | `GLIFIC_FLOW_NUDGE_ID` | Yes | Glific flow ID for the nudge interactive flow |
| `glific.flow.welcome-id` | `GLIFIC_FLOW_WELCOME_ID` | Yes | Glific flow ID for the welcome onboarding flow |
| `glific.template.escalation-id` | `GLIFIC_ESCALATION_TEMPLATE_ID` | Yes | Glific HSM template ID for escalation document |
| `glific.template.login-otp-id` | `GLIFIC_LOGIN_OTP_TEMPLATE_ID` | Yes | Glific HSM template ID for login OTP |
| `glific.request-interval-ms` | — | No | Min ms between Glific API calls (default: 500) |
| `minio.endpoint` | `MINIO_ENDPOINT` | Yes | MinIO server URL |
| `minio.access-key` | `MINIO_ACCESS_KEY` | Yes | MinIO access key |
| `minio.secret-key` | `MINIO_SECRET_KEY` | Yes | MinIO secret key |
| `minio.bucket` | `MINIO_BUCKET` | No | Bucket name (default: `escalation-reports`) |
| `minio.base-url` | `MINIO_BASE_URL` | No | Public base URL for PDF links |
| `app.base-url` | `APP_BASE_URL` | No | Public URL of message-service itself |
| `notifications.dry-run` | `NOTIFICATIONS_DRY_RUN` | No | Set `true` to suppress all Glific API calls |
| `escalation.report.dir` | — | No | Local PDF output directory (default: `/tmp/escalation-reports/`) |

---

## Dead-Letter Topics

| Topic | Published by | Consumed by |
|---|---|---|
| `welcome-message-dlt` | message-service | External ops/monitoring consumer |

The DLT is not consumed by message-service itself — doing so would create an unbounded retry loop. Each record carries a deterministic `retryId` (UUID v3) for idempotent replay. Configure alerting (e.g., Kafka consumer-lag alerts) on this topic to detect delivery failures.

---

## Privacy Rules

Phone numbers are PII. They must never appear in `INFO`, `WARN`, or `ERROR` log lines. All phone logging is done at `DEBUG` level only. This applies to application code, tests, and any helper utilities.
