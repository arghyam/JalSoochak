# Anomaly Flows

This document describes how anomalies are created, persisted, deduplicated, and exposed in JalSoochak V2. It also explains when anomaly creation leads to downstream escalation records.

---

## Purpose

Anomalies represent abnormal telemetry or reporting situations that require attention. They are generated automatically during reading ingestion, water-supply validation, and scheduled missed-submission processing.

At a high level, anomalies are used to:

- detect bad or suspicious meter-reading submissions
- detect supply and no-submission issues
- persist the issue for tenant-level operational tracking
- publish the issue into analytics for dashboards and reports
- optionally create escalation records for specific anomaly types

---

## Architecture Overview

```text
telemetry-service / tenant-service
          |
   anomaly detected
          |
          +-----------------------------+
          |                             |
 tenant schema anomaly_table      Kafka event / analytics ingest
          |                             |
          |                     analytics-service
          |                             |
          +---- operational record      +---- analytics_schema.anomaly_table
                                        +---- optional fact_escalation_table row
```

There are two main anomaly creation paths:

- `telemetry-service` creates anomalies during reading and issue-report processing
- `tenant-service` creates no-submission anomalies during scheduled escalation checks

---

## Anomaly Types

The current anomaly type constants in `telemetry-service` are:

- `1` - `UNREADABLE_IMAGE`
- `2` - `MANUAL_OVERRIDE`
- `3` - `CONSECUTIVE_OVERRIDE_5_DAYS`
- `4` - `DUPLICATE_IMAGE_SUBMISSION`
- `5` - `READING_LESS_THAN_PREVIOUS`
- `6` - `NO_WATER_SUPPLY`
- `7` - `LOW_WATER_SUPPLY`
- `8` - `OVER_WATER_SUPPLY`
- `9` - `NO_SUBMISSION`

Source: `telemetry-service` `AnomalyConstants`.

---

## 1. Reading-Time Anomalies

### Trigger

Reading-time anomalies are created in `BfmReadingService` while processing BFM/manual meter readings.

### Typical cases

#### Unreadable image

If OCR cannot extract a valid meter reading from the uploaded image:

- a tenant anomaly row is created for `UNREADABLE_IMAGE`
- an `ANOMALY_RECORDED` event is published
- the reading request returns a rejected response to the flow

#### Reading less than previous confirmed reading

If the submitted or confirmed reading is lower than the latest confirmed reading for the scheme:

- a tenant anomaly row is created for `READING_LESS_THAN_PREVIOUS`
- an `ANOMALY_RECORDED` event is published
- the reading is rejected

#### Duplicate image submission

If the extracted reading matches the previous confirmed reading and came from an image submission:

- a tenant anomaly row is created for `DUPLICATE_IMAGE_SUBMISSION`
- an `ANOMALY_RECORDED` event is published
- the reading is rejected

#### Low water supply

If the submitted reading is below the configured minimum threshold derived from tenant configs such as `WATER_NORM` and water-supply threshold settings:

- a tenant anomaly row is created for `LOW_WATER_SUPPLY`
- an `ANOMALY_RECORDED` event is published
- the reading is rejected

### Persistence at tenant level

`telemetry-service` writes a tenant operational anomaly row into:

- `<tenant_schema>.anomaly_table`

The row typically includes:

- `user_id`
- `scheme_id`
- `type`
- `reason`
- `status`
- `created_at`

---

## 2. Operator-Reported Supply / No-Submission Anomalies

### Trigger

During Glific issue-report and meter workflow flows, the operator can report issues such as no water supply or no submission reasons.

These issues eventually produce anomaly or water-quantity side effects depending on the selected workflow branch and downstream processing.

### Typical cases

- `NO_WATER_SUPPLY`
- `NO_SUBMISSION`

These cases are important because they are not only operational anomaly signals, but can also feed escalation-style reporting in analytics.

---

## 3. Scheduled No-Submission Anomalies

### Trigger

`tenant-service` runs a scheduled escalation job per tenant using `EscalationSchedulerService`.

This job:

- loads tenant escalation config
- finds operators who have missed submissions for the configured number of days
- classifies them into level 1 or level 2 severity
- groups them by officer
- creates downstream escalation events

### Anomaly side effect

While processing each missed-submission operator, analytics ingestion creates an anomaly row representing the no-submission condition.

Typical anomaly reason values are:

- `No submission — operator has never uploaded a reading`
- `No submission for <N> consecutive days`

This means missed-submission operational escalations also become anomaly records in analytics.

---

## 4. Deduplication Behavior

Anomaly creation is not always naive append-only behavior.

### Same-day deduplication in telemetry-service

For certain image-related anomalies, `telemetry-service` first checks whether the same anomaly type already exists for the same user and scheme on the current date.

If one already exists:

- it updates or "touches" the latest anomaly row for today instead of creating endless duplicates
- it can still publish the anomaly event with updated retry information

This is used to avoid duplicate same-day unreadable-image style noise.

### UUID-based deduplication in analytics

When `analytics-service` ingests an `ANOMALY_RECORDED` event:

- it uses the anomaly `uuid` as the deduplication key
- if the UUID already exists, it updates the timestamp instead of inserting another row

This makes the analytics anomaly pipeline idempotent across retries or duplicate Kafka deliveries.

---

## 5. Kafka / Analytics Ingestion

### Event publishing

`telemetry-service` publishes anomaly events through `TelemetryEventPublisher.publishAnomalyRecorded(...)`.

The event type is:

- `ANOMALY_RECORDED`

Typical event payload fields include:

- `tenantId`
- `type`
- `userId`
- `schemeId`
- `aiReading`
- `aiConfidencePercentage`
- `overriddenReading`
- `retries`
- `previousReading`
- `previousReadingDate`
- `consecutiveDaysMissed`
- `reason`
- `status`
- `correlationId`
- `uuid`

### Analytics ingestion

`analytics-service` consumes `ANOMALY_RECORDED` from `telemetry-service-topic` and writes a row to:

- `analytics_schema.anomaly_table`

This is the analytics-facing anomaly store used by reporting and list APIs.

Stored fields include:

- `uuid`
- `tenant_id`
- `scheme_id`
- `user_id`
- `type`
- `reason`
- `status`
- `correlation_id`
- `ai_reading`
- `ai_confidence_percentage`
- `overridden_reading`
- `previous_reading`
- `previous_reading_date`
- `consecutive_days_missed`
- `created_at`
- `updated_at`

---

## 6. Relationship Between Anomalies and Escalations

Not every anomaly becomes an escalation.

### Anomalies that remain anomalies only

Examples:

- unreadable image
- duplicate image submission
- reading less than previous

These are persisted and reported as anomalies, but they do not automatically create officer-facing escalation cases.

### Anomalies that also create escalation facts

For water-related anomaly types, analytics ingestion also creates a row in:

- `analytics_schema.fact_escalation_table`

This currently applies to water anomaly categories such as:

- `NO_WATER_SUPPLY`
- `LOW_WATER_SUPPLY`
- `OVER_WATER_SUPPLY`
- `NO_SUBMISSION` in the scheduled escalation path

This allows anomaly-driven operational issues to also appear in escalation analytics.

---

## 7. Lifecycle

The anomaly lifecycle is:

- an abnormal condition is detected
- the anomaly is persisted in the tenant operational schema and/or analytics schema
- an anomaly event is published for analytics ingestion when applicable
- repeated occurrences may be deduplicated or timestamp-touched
- the anomaly remains in the status model until acted on

The analytics status model currently exposes:

- `0` - `Unresolved`
- `1` - `In-Progress`
- `2` - `Resolved`

The data model includes fields such as:

- `status`
- `remarks`
- `resolved_by`
- `resolved_at`

This means the system is designed to support a full operational lifecycle, even where some update flows are lighter than the escalation status-update path.

---

## 8. Key Tables

### Tenant operational tables

- `<tenant_schema>.anomaly_table`
- `<tenant_schema>.flow_reading_table`

### Analytics tables

- `analytics_schema.anomaly_table`
- `analytics_schema.fact_escalation_table`
- `analytics_schema.fact_meter_reading_table`
- `analytics_schema.fact_water_quantity_table`

---

## 9. Main Services Involved

### telemetry-service

- `BfmReadingService`
- `TelemetryEventPublisher`
- `TelemetryTenantRepository`
- `AnomalyConstants`

### tenant-service

- `EscalationSchedulerService`
- `NudgeRepository`

### analytics-service

- `AnalyticsKafkaConsumer`
- `FactServiceImpl`
- `AnomalyRepository`
- `FactEscalationRepository`

---

## 10. Summary

Anomalies in JalSoochak are system-detected operational exceptions created during reading ingestion, supply validation, and scheduled missed-submission checks. They are first captured as operational records, then ingested into analytics for reporting. Some anomaly types stay purely as anomalies, while water- and submission-related anomaly types can also generate escalation facts. The pipeline includes deduplication and status tracking so anomalies can be managed as ongoing operational cases rather than just raw error logs.
