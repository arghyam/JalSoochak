# Telemetry Service

**Port:** `8989` | **Module:** `backend/telemetry-service`

## Overview

The Telemetry Service is the **core data-ingestion layer** of JalSoochak V2. It accepts daily meter readings from field operators via multiple channels:

- **WhatsApp / Glific** — the primary channel for field pump operators, using photo submission with AI-assisted reading extraction
- **Web dashboard** — manual entry by authorised staff
- **IoT / API** — automated submissions from connected flow sensors

It integrates with **FlowVision** (an AI service) to extract numeric readings from meter images, manages an operator-facing confirmation workflow, validates readings for correctness, and publishes `MeterReadingEvent` to Kafka for downstream analytics.

---

## Architecture

```
telemetry-service
├── controller/
│   ├── GlificWebhookController     – Glific WhatsApp flow step webhooks: POST /api/v1/telemetry/*
│   ├── SingleTenantTelemetryController – Flow handlers for reading / issue-report / meter-change submission
│   └── ApiController               – Staff reading query (GET /api/v1/telemetry) and Kafka publish
├── service/
│   ├── TelemetryService            – Orchestrates the reading ingestion lifecycle
│   ├── FlowVisionService           – Calls FlowVision AI API to extract reading from image
│   ├── ReadingValidationService    – Validates readings (monotonic, outlier, duplicate checks)
│   └── GlificWebhookService        – Processes and routes Glific webhook payloads
├── repository/
│   ├── FlowReadingRepository       – JPA for flow_reading_table
│   └── SchemeRepository            – Reads scheme metadata for validation context
├── entity/
│   └── FlowReading                 – flow_reading_table entity
├── kafka/
│   ├── KafkaProducer               – Publishes MeterReadingEvent to telemetry-service-topic
│   └── KafkaConsumer               – Consumes from common-topic
└── config/
    ├── SecurityConfig              – Glific flow webhooks under /api/v1/telemetry are public; others require JWT
    └── WebClientConfig             – WebClient bean for FlowVision HTTP calls
```

---

## REST API

### Glific Flow Webhooks (WhatsApp)

The WhatsApp submission journey is a multi-step Glific flow. Glific calls a dedicated webhook on `telemetry-service` at **each step** of the conversation, all under the `/api/v1/telemetry` base path (these endpoints are public — secured by Glific signature, not JWT). Representative steps:

| Method | Endpoint | Step in the flow |
|--------|----------|------------------|
| `POST` | `/api/v1/telemetry/intro` | Flow entry — greet operator, resolve contact |
| `POST` | `/api/v1/telemetry/language/selection` | Present / record preferred language |
| `POST` | `/api/v1/telemetry/channel/selection` | Present / record submission channel |
| `POST` | `/api/v1/telemetry/schemes` · `/scheme/selected` | List operator's schemes and capture the chosen one |
| `POST` | `/api/v1/telemetry/take-meter-reading` | Receive the meter photo → call FlowVision AI |
| `POST` | `/api/v1/telemetry/manual-reading` | Operator enters / corrects the reading manually |
| `POST` | `/api/v1/telemetry/update-previous-reading` | Adjust the previous baseline reading |
| `POST` | `/api/v1/telemetry/meter-change` · `/meter/meter-change/submit` | Record a meter replacement |
| `POST` | `/api/v1/telemetry/issue-report` · `/issue-report/submit` | Report a supply outage / operational issue |
| `POST` | `/api/v1/telemetry/closing` | Confirm submission and close the flow |

{% hint style="info" %}
The exact set of webhook endpoints maps 1:1 to the steps configured in the Glific flow. See `GlificWebhookController` and `SingleTenantTelemetryController` for the authoritative list.
{% endhint %}

**Processing Steps (reading submission):**
1. Resolve the operator from the Glific contact / phone number hash
2. On `take-meter-reading`, call FlowVision AI with the image URL → receive `extractedReading` and `confidence`
3. If confidence ≥ threshold: show operator the extracted value for confirmation
4. If confidence < threshold: route to `manual-reading` so the operator enters the value
5. Validate the reading and persist the `FlowReading` record
6. Publish `MeterReadingEvent` to Kafka

**Response to Glific** (rendered in the operator's WhatsApp chat):
```json
{
  "success": true,
  "message": "Reading submitted successfully",
  "extractedReading": 1250,
  "previousReading": 1180,
  "quantity": 70
}
```

### Staff Query

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/v1/telemetry` | Authenticated (JWT) | List meter readings with filters (used by dashboards) |

{% hint style="info" %}
Manual reading entry, meter replacement, and issue reporting are driven through the Glific flow webhooks listed above (`/api/v1/telemetry/manual-reading`, `/meter-change`, `/issue-report`) rather than separate staff REST endpoints.
{% endhint %}

**Query Parameters for `GET /api/v1/telemetry`:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `schemeId` | Long | Filter by scheme |
| `fromDate` | LocalDate | Start date (inclusive) |
| `toDate` | LocalDate | End date (inclusive) |
| `channel` | Integer | `1`=WhatsApp, `2`=Web, `3`=IoT |
| `page` | Integer | Page number (0-indexed) |
| `size` | Integer | Page size (default: 20) |

---

## Data Models

### FlowReading (`flow_reading_table` in tenant schema)

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | UUID | Public identifier |
| `scheme_id` | BIGINT | Reference to `scheme_master_table` |
| `reading_at` | TIMESTAMP | Exact submission timestamp |
| `reading_date` | DATE | Calendar date (used for daily deduplication) |
| `extracted_reading` | BIGINT | Value extracted by FlowVision AI from the meter image |
| `confirmed_reading` | BIGINT | Final value confirmed or entered by the operator |
| `confidence` | FLOAT | AI confidence score (0–100) |
| `quantity` | BIGINT | Delta from previous reading in litres |
| `channel` | INTEGER | Submission channel: `1`=WhatsApp, `2`=Web, `3`=IoT |
| `image_url` | VARCHAR | Object storage URL of the meter photo |
| `reading_type` | INTEGER | `1`=Normal, `2`=Meter replacement, `3`=Issue report |
| `submission_status` | INTEGER | `1`=Submitted, `2`=Confirmed, `3`=Rejected |

### MeterReadingEvent (Kafka Payload)

```json
{
  "eventType": "METER_READING_SUBMITTED",
  "tenantId": 1,
  "schemeId": 101,
  "userId": 42,
  "extractedReading": 1250,
  "confirmedReading": 1250,
  "confidence": 97.3,
  "imageUrl": "https://storage.example.com/readings/abc123.jpg",
  "readingAt": "2024-01-15T08:30:00Z",
  "channel": 1,
  "readingDate": "2024-01-15",
  "submissionStatus": 1,
  "readingType": 1,
  "quantity": 70
}
```

---

## FlowVision Integration

FlowVision is an AI service that extracts numeric readings from meter photographs.

**Endpoint:** `POST <FLOWVISION_URL>/extract-reading`

**Request:**
```json
{
  "imageUrl": "https://storage.example.com/media/meter.jpg"
}
```

**Response:**
```json
{
  "extractedReading": 1250,
  "confidence": 97.3,
  "processingTimeMs": 342
}
```

**Confidence Handling:**

| Confidence | Behaviour |
|-----------|-----------|
| ≥ 85% | Reading auto-accepted; operator prompted to confirm the extracted value |
| < 85% | Operator prompted to enter the reading manually in the WhatsApp flow |

---

## Reading Validation

All readings pass through `ReadingValidationService` before being persisted:

| Check | Rule |
|-------|------|
| **Monotonic** | `confirmedReading ≥ previousReading` — meters cannot run backwards (except after a replacement) |
| **Outlier** | Daily delta must not exceed N× the scheme's average daily consumption |
| **Duplicate** | Only one reading per scheme per calendar day is accepted |
| **Meter replacement** | Resets the baseline; monotonic check is skipped for that submission |

---

## Kafka Events

### Published

**Topic: `telemetry-service-topic`**

| Event Type | Trigger | Key Payload Fields |
|------------|---------|-------------------|
| `METER_READING_SUBMITTED` | Reading validated and persisted | `tenantId`, `schemeId`, `userId`, `extractedReading`, `confirmedReading`, `quantity`, `readingDate`, `channel` |

### Consumed

**Topic: `common-topic`**

Events are logged; no active processing is performed by this service.

---

## Submission Channels

| Channel ID | Name | Description |
|-----------|------|-------------|
| `1` | `WHATSAPP` | Glific WhatsApp flow (primary channel for operators) |
| `2` | `WEB` | Web dashboard manual entry by staff |
| `3` | `IOT` | Automated submission from connected IoT sensors |

---

## Configuration

```yaml
server:
  port: 8989

spring:
  application:
    name: telemetry-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}

flowvision:
  url: ${FLOWVISION_URL}
  confidence-threshold: ${FLOWVISION_CONFIDENCE_THRESHOLD:85}
  timeout-ms: ${FLOWVISION_TIMEOUT_MS:10000}

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
| Spring WebFlux | `WebClient` for non-blocking FlowVision HTTP calls |
| Spring Data JPA | Reading persistence |
| Spring Kafka | `MeterReadingEvent` publishing |
| Spring Security + OAuth2 Resource Server | JWT auth (Glific flow webhooks under `/api/v1/telemetry` are public) |
| springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui/index.html` |
