# Reading provenance + threshold disclosure — response to the CWE-840 audit finding

**Audit finding:** `POST /api/v1/telemetry/readings` accepts `confirmed_reading`, which records a
meter value with no image and no FlowVision AI analysis, answering `qualityStatus: "CONFIRMED"`.
`POST /api/v1/telemetry/manual-reading` allows the same and echoes the tenant's configured
validation threshold in its rejection message.

**Decision:** submitting a value instead of an image is **intended behaviour** — it is the
server-to-server contract for state IT systems that read their own meters (see
`docs/pluggable-ingestion-testing-guide.md`). The bypass is accepted risk and is not restricted. The
API-key authentication gap on the Glific webhook routes is owned by a separate change.

**What this change does:** three things only.

1. Records **how** a confirmed value arrived, so an API-supplied number is identifiable in the
   database rather than indistinguishable from an AI-extracted one.
2. Stops the manual-reading rejection message from disclosing the tenant's configured threshold.
3. Stops unmatched exceptions from returning raw text — SQL, schema names, config keys and, in one
   case, the caller's own phone number — to the user (§5).

Nothing is restricted, no request that succeeded before fails now, and no API response changes for
the ingestion endpoints.

---

## 1. Provenance — `confirmed_reading_source = 3`

`flow_reading_table.confirmed_reading_source` (added in `V35`, `SMALLINT NOT NULL DEFAULT 0`) gains a
fourth value. **No migration is needed** — the column already exists and this is a new value in it.

```
0 = AS_EXTRACTED          confirmed_reading is FlowVision's pick
1 = ROLLOVER_RESOLVED     resolved to a sibling rollover digit
2 = MANUAL                set by an explicit manual override
3 = EXTERNALLY_ASSERTED   supplied by the caller via confirmed_reading — no image, no AI   <-- new
```

### Why the marker is needed at all

`extracted_reading` is `NOT NULL`, so a value-only submission still writes the caller's number into
the column named "what the AI extracted". Before this change such a row was indistinguishable from an
AI-verified one in every column — same `extracted_reading`, same `confirmed_reading`, same
`confirmed_reading_source = 0`. Absence of `image_url` / `flowvision_correlation_id` is a weak proxy
(other paths also leave them empty). The marker makes it explicit and queryable.

### Where it is written

Inside the insert transaction, not as a follow-up `UPDATE`. API-supplied readings are routed through
the existing `@Transactional persistFlowReadingWithTracking` (already used by the lenient-ingestion
path) rather than the bare `createFlowReading` / `updateFlowReadingFromIngestion` calls. The reading
values, correlation ids, timestamps and placeholder-row reuse are byte-identical — the only
difference is one extra `UPDATE ... SET confirmed_reading_source = 3` in the same transaction.

A tenant schema that has not run `V35` (column absent) is a silent no-op, guarded by the existing
`columnExists` check — the reading is still recorded, just without the marker.

### Finding API-supplied readings

```sql
SELECT id, scheme_id, created_by, reading_date, extracted_reading, confirmed_reading
  FROM tenant_<code>.flow_reading_table
 WHERE confirmed_reading_source = 3
 ORDER BY reading_date DESC;
```

## 2. Threshold disclosure on `/manual-reading`

The rejection said:

> Reading rejected because it is above the allowed maximum. Submitted: 5000. **Maximum allowed
> reading: 260.**

`260` is `last confirmed + WATER_NORM × (100 + oversupplyThresholdPercent)%`. Two submissions against
a scheme with a known last-confirmed value are enough to solve for both config values. It now says:

> Reading rejected because it is above the allowed maximum for this scheme. Submitted: 5000.

The operator still learns their value was rejected and why. The numbers are unchanged in the anomaly
record, the `ANOMALY_RECORDED` Kafka event and a new `manual_reading_rejected` WARN log — all
staff-side. The rejection threshold itself is unchanged: the same submissions are rejected as before.

## 3. Effects to be aware of

| Area | Effect |
|---|---|
| Ingestion API responses | **None.** Still `200`, `success: true`, `qualityStatus: "CONFIRMED"`, same `meterReading`. |
| Values written to `flow_reading_table` | **None**, except `confirmed_reading_source = 3` instead of `0` on API-supplied rows. |
| Other tables | **None.** No new table or column. |
| Kafka / analytics | **None.** `MeterReadingEvent` is unchanged and does not carry the marker, so analytics KPIs and the dashboard are untouched. |
| WhatsApp operators | Only the manual-reading over-maximum message, which no longer names the limit. |
| Glific flow exports | Untouched. No new header, no new field, no changed branch. |
| Migrations | None. `confirmed_reading_source` already exists (`V35`). |
| Historical rows | Not backfilled. Rows written before this change carry `0` whether or not they were API-supplied. |
| Rollback | Delete the marker: `UPDATE tenant_<code>.flow_reading_table SET confirmed_reading_source = 0 WHERE confirmed_reading_source = 3;` The code change is one commit. |

If the marker is wanted on analytics events too, `MeterReadingEvent` would need a new field and
`FactMeterReading` a new column — deliberately not done here.

## 4. Findings reviewed and deliberately not changed

| Finding | Decision |
|---|---|
| `confirmed_reading` bypasses the AI pipeline | **Intended behaviour.** Documented contract; accepted risk. |
| No plausibility bound on submitted values | **Not wanted.** Readings are deliberately not restricted by min/max checks. The commented-out below-previous and below-minimum validations in `BfmReadingService` / `GlificMeterWorkflowService` are left as they are. |
| `/manual-reading` has no API-key authentication | **Owned by the Glific authentication change**, handled separately. |
| The tenant API key carries no capabilities | Not addressed — it only matters if value submission is to be restricted, which it is not. |

## 5. Raw exception text in error replies

`GlificLocalizationService.resolveUserFacingErrorMessage` mapped a set of known exception messages to
friendly text and, for anything unmatched, **returned the raw exception message to the caller**. Every
caller catches `Exception`, so that path was reachable by far more than the business validations it
was written for. Messages that could reach a WhatsApp operator or an API response included:

| Leaked | Discloses |
|---|---|
| `bad SQL grammar [SELECT … FROM tenant_as.flow_reading_table …]; nested exception is org.postgresql.util.PSQLException` | SQL, table and schema names, database engine |
| `Missing required column reading_date` | Schema internals |
| `Tenant not found for schema tenant_as` | Tenant schema naming |
| `PII_ENCRYPTION_KEY must decode to exactly 32 bytes` | Key configuration |
| `AES-GCM decryption failed`, `HMAC-SHA256 failed` | Crypto internals |
| `No operator found for contactId 919999900001` | **The submitted phone number, echoed back** — and confirms whether a number is registered |
| `400 BAD_REQUEST "Operator does not belong…"` | HTTP status prefix shown to an operator |
| `API key service not configured` | Deployment state |

**Fix:** the method is now an **allowlist**. Only a message matched by an explicit rule is shown;
everything else becomes the caller's own fallback, which is already context-specific — *"Manual
reading could not be saved."*, *"Image could not be processed."*, *"Location could not be saved."*,
*"Assam reading could not be processed."* — so the user still gets a message that fits what they were
doing, with no internal detail. Adding a user-facing message now means adding a rule; the safe
default is not to disclose.

Every legitimate message that previously passed through unmapped has a rule, so nothing the operator
relied on is lost. The rules added beyond the original set: scheme and meter-change-reason selection
errors, generic reading validation, `contactId` / `phoneNumber` / `correlationId` identity errors,
`issueReason is required`, all geolocation validation shapes, operator-not-found, scheme-not-found,
`Operator does not belong to the specified scheme`, and the `METER_CHANGE_REASONS` /
`SUPPLY_OUTAGE_REASONS` config errors (which named the config key).

Order matters in two places, and both are commented in the code: `manualreading is required` must be
tested before `reading is required` (the first string contains the second), and
`scheme not found for the provided state or centre scheme id` before `scheme not found`.

A suppressed message is logged server-side at INFO on `error_message_suppressed` with the exception
type, so a missing rule is visible in ops and easy to add. The exception itself is still logged with
its stack trace by the calling service, as before.

Hindi replies are unaffected: mapped messages reuse the existing English strings that
`localizeMessage` already translates, and a fallback such as *"Manual reading could not be saved."*
has a Hindi translation too. Rules added here that have no Hindi entry return English, which is what
those messages already did.
