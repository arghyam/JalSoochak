# Reading provenance + threshold disclosure — response to the CWE-840 audit finding

**Audit finding:** `POST /api/v1/telemetry/readings` accepts `confirmed_reading`, which records a
meter value with no image and no FlowVision AI analysis, answering `qualityStatus: "CONFIRMED"`.
`POST /api/v1/telemetry/manual-reading` allows the same and echoes the tenant's configured
validation threshold in its rejection message.

**Decision:** submitting a value instead of an image is **intended behaviour** — it is the
server-to-server contract for state IT systems that read their own meters (see
`docs/pluggable-ingestion-testing-guide.md`). The bypass is accepted risk and is not restricted. The
API-key authentication gap on the Glific webhook routes is owned by a separate change.

**What this change does:** two things only.

1. Records **how** a confirmed value arrived, so an API-supplied number is identifiable in the
   database rather than indistinguishable from an AI-extracted one.
2. Stops the manual-reading rejection message from disclosing the tenant's configured threshold.

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

## 5. One finding worth triaging separately

`GlificLocalizationService.resolveUserFacingErrorMessage` maps a set of known exception messages to
friendly text and, for anything unmatched, **returns the raw exception message to the caller**
(`return localizeMessage(message.trim(), languageKey);`). The catch blocks that call it catch
`Exception`, so an unmapped infrastructure failure — a `DataAccessException`, for example — puts its
message into the API response and into the operator's WhatsApp reply. That can carry SQL text, schema
names such as `tenant_as`, or driver internals.

Not changed here: many deliberate business messages (`"Scheme not found for the provided state or
centre scheme id"`, `"Operator does not belong to the specified scheme"`) rely on that same
pass-through, so tightening it needs a decision about which messages are contractually user-facing.
The narrow fix, if wanted, is to fall back to the generic message when the exception text matches
infrastructure patterns (`jdbc`, `SQL`, `org.postgresql`, `nested exception`, `tenant_`) rather than
changing the default.
