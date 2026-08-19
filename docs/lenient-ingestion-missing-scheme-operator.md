# Lenient ingestion — recording submissions with a missing scheme / operator

**Scope:** Assam REST channel (`POST /api/v1/telemetry/readings`) only.
**Status:** on by default, gated by a flag and a greppable code marker so it can be reverted.
**Marker:** every code/DB change for this feature is tagged `LENIENT-INGEST`.

---

## 1. Problem

Every meter reading becomes a row in `<tenant>.flow_reading_table`, which has three `NOT NULL`
foreign keys: `scheme_id → scheme_master_table`, and `created_by`/`updated_by → user_table`.
So a reading could not be inserted unless both the scheme **and** the operator already existed in
the tenant master data. Assam submissions were being dropped at two points in
`telemetry-service`:

1. **Unknown operator** — the submitted `phone_number` was not in `user_table`
   (`GlificOperatorContextService` threw `No operator found…`).
2. **Unknown / unmapped scheme** — the submitted `state_scheme_id`/`centre_scheme_id` was not in
   `scheme_master_table`, or existed but the operator was not mapped to it
   (`GlificImageWorkflowService.resolveAssamSchemeId` threw `scheme_not_found` /
   `operator_not_mapped_to_scheme`).

> Note: a scheme id is always present (bean-validation requires one), and originally so was the phone
> — see §2a, `phone_number` is now optional. For the cases below the drop was about those values
> **not existing in our DB**, which is a different thing from being absent from the request.

## 2. What changed

Instead of rejecting, the Assam path now **records the submission and tags it** so nothing is lost
and it can be filtered / reconciled later. Four cases, each represented differently:

| Case                                       | Representation                                                                                                      | `ingestion_source` bit    |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- | ------------------------- |
| Scheme id not in our records               | recorded against an **auto-provisioned placeholder scheme** (`scheme_master_table.is_auto_provisioned = TRUE`)      | `1` `UNKNOWN_SCHEME`      |
| Phone not in our records                   | recorded against the single **sentinel "Unknown operator"** (`user_table.is_auto_provisioned = TRUE`, `status = 0`) | `2` `UNKNOWN_OPERATOR`    |
| Scheme + operator exist but are not mapped | recorded against the **existing** scheme (no mapping created)                                                       | `4` `OPERATOR_NOT_MAPPED` |
| No phone in the payload at all             | operator **inferred from the scheme** (see §2a)                                                                     | `8` `PHONE_ABSENT`        |

Bits are combined (e.g. unknown scheme **and** unknown operator → `1 | 2 = 3`). A normal submission
has `ingestion_source = 0`.

### 2a. Optional `phone_number` (marker `PHONE-OPTIONAL`)

`phone_number` is no longer required by bean validation; a blank value is treated exactly like an
absent one. Resolution order flips when it is missing, because there is no submitter to start from:

| Payload       | Order             | Operator credited                                                                                                                                         |
| ------------- | ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| With phone    | operator → scheme | the operator behind the phone (or the sentinel, per §2)                                                                                                   |
| Without phone | scheme → operator | the **first pump operator mapped to the resolved scheme** (oldest active `user_scheme_mapping_table` row, `user_type = PUMP_OPERATOR`), else the sentinel |

Because the operator is _inferred_, every such reading is tagged `PHONE_ABSENT` — including the case
where it lands on a perfectly normal, mapped operator, which trips none of the other bits and would
otherwise be indistinguishable from a genuine submission by that operator. Combinations follow the
same rules: no phone **and** no mapped pump operator → `8 | 2 = 10`; no phone **and** an unknown
scheme id → `8 | 1 | 2 = 11` (a placeholder scheme never has a mapped operator).

Two consequences worth knowing:

- `submitted_phone_hash` is always `NULL` for these rows — nothing was submitted to hash.
- The reading channel (BFM/ELM/PDU/…) is looked up from the **credited** operator's own number, so
  the per-channel water-quantity maths in analytics stays correct instead of silently defaulting.

With the lenient off-switch disabled (§8), a phone-less submission is still accepted when the scheme
has a mapped pump operator; it is rejected when there is none (no sentinel to fall back on).

Because the reading becomes a real row and publishes `METER_READING_RECORDED`, it flows into
analytics' `fact_meter_reading_table` and **counts in totals / scheme-level KPIs automatically**.
See §6 for the region-rollup caveat.

### 2b. Optional `phone_number` on `PUT /api/v1/telemetry/readings` (marker `PHONE-OPTIONAL`)

The update endpoint corrects an existing row, so unlike the POST it cannot infer anything from a
scheme — the body carries no scheme id, and `reading_url` / `image_id` is not a key the reading can
be looked up by. It therefore needs **one of two identifiers**, and either alone is enough:

| Payload                                    | Row corrected                                                         |
| ------------------------------------------ | --------------------------------------------------------------------- |
| `correlation_id` (with or without a phone) | the reading with that `correlation_id` or `flowvision_correlation_id` |
| `phone_number` only                        | the latest reading created by the operator behind that phone          |
| neither                                    | rejected — `400 Either correlationId or phoneNumber must be provided` |

`correlation_id` already won over `phone_number` before this change; the phone was accepted and then
discarded. Only the controller's "phone is mandatory" gate was dropped.

No `PHONE_ABSENT` bit is set here: nothing is inferred. The row keeps its original `created_by`, and
`ingestion_source` is not touched by the update at all.

**Tenant resolution on the `correlation_id` path.** There is no operator to derive a schema from, so
it comes from the tenant behind the `X-Api-Key`, via `findSchemaNameByTenantId`. The `X-Tenant-Code`
header (`TenantContext`) is now only the fallback for callers with no authenticated tenant. The API
key deliberately wins: that header is unauthenticated, so letting it take precedence would let a
caller holding one tenant's key reach another tenant's schema. The same tenant id also backstops the
`METER_READING_RECORDED` event when the row's creator cannot be resolved — analytics drops the
attendance and water-quantity facts for any event with a null `tenantId` (see §6).

## 3. Database changes (migration `V31__add_lenient_ingestion_tracking.sql`)

Applied to every existing `tenant_%` schema and baked into `create_tenant_schema()` for new tenants.

### `flow_reading_table`

| Column                       | Type                          | Meaning                                                                                                                                     |
| ---------------------------- | ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `ingestion_source`           | `SMALLINT NOT NULL DEFAULT 0` | Bitmask (0 = normal). `1`=unknown scheme, `2`=unknown operator, `4`=operator-not-mapped, `8`=phone absent from the payload.                 |
| `submitted_state_scheme_id`  | `VARCHAR(255)`                | Raw `state_scheme_id` from the payload (for reconciliation). Null for normal rows.                                                          |
| `submitted_centre_scheme_id` | `VARCHAR(255)`                | Raw `centre_scheme_id` from the payload. Null for normal rows.                                                                              |
| `submitted_phone_hash`       | `VARCHAR(64)`                 | HMAC of the digit-normalized submitted phone (no raw PII). Set only for the unknown-operator case; always null when no phone was submitted. |

No migration was needed for `PHONE_ABSENT` — it is another bit in the existing `SMALLINT` column.

Partial index `idx_<schema>_flow_ingestion_source ON flow_reading_table(ingestion_source) WHERE ingestion_source <> 0`.

### `scheme_master_table`

| Column                | Type                             | Meaning                                                     |
| --------------------- | -------------------------------- | ----------------------------------------------------------- |
| `is_auto_provisioned` | `BOOLEAN NOT NULL DEFAULT FALSE` | `TRUE` for placeholder schemes created by the lenient path. |

Partial index `idx_<schema>_scheme_auto_prov … WHERE is_auto_provisioned`.
Placeholder rows have `scheme_name = 'Auto-provisioned scheme (state:<id>|centre:<id>)'`,
`work_status = 0`, `operating_status = 0`, and the submitted ids in `state_scheme_id`/`centre_scheme_id`.

### `user_table`

| Column                | Type                             | Meaning                                     |
| --------------------- | -------------------------------- | ------------------------------------------- |
| `is_auto_provisioned` | `BOOLEAN NOT NULL DEFAULT FALSE` | `TRUE` for the sentinel "Unknown operator". |

Partial index `idx_<schema>_user_auto_prov … WHERE is_auto_provisioned`.
The sentinel is a single row per tenant: `email = 'unknown-operator@auto.jalsoochak.invalid'`,
`title = 'Unknown Operator'`, `status = 0` (inactive, so it never appears in active-operator KPIs).

> Find the Assam schema name: `SELECT id, state_code FROM common_schema.tenant_master_table;`
> The schema is `tenant_<state_code>` (e.g. `tenant_as`). Substitute it for `<schema>` below.

## 4. How to filter / track each category (SQL)

```sql
-- Everything recorded through the lenient path
SELECT * FROM <schema>.flow_reading_table
WHERE ingestion_source <> 0
ORDER BY created_at DESC;

-- Case 1 — unknown scheme (recorded against a placeholder scheme)
SELECT * FROM <schema>.flow_reading_table
WHERE (ingestion_source & 1) <> 0;
-- or, from the master side:
SELECT * FROM <schema>.scheme_master_table WHERE is_auto_provisioned;

-- Case 2 — unknown operator (recorded against the sentinel)
SELECT * FROM <schema>.flow_reading_table
WHERE (ingestion_source & 2) <> 0;
SELECT id FROM <schema>.user_table WHERE is_auto_provisioned;  -- the sentinel id

-- Case 3 — operator not mapped to an existing scheme
SELECT * FROM <schema>.flow_reading_table
WHERE (ingestion_source & 4) <> 0;

-- Case 4 — no phone in the payload (operator inferred from the scheme)
SELECT * FROM <schema>.flow_reading_table
WHERE (ingestion_source & 8) <> 0;
-- ... of which the scheme had no pump operator to credit (fell back to the sentinel)
SELECT * FROM <schema>.flow_reading_table
WHERE (ingestion_source & 8) <> 0 AND (ingestion_source & 2) <> 0;

-- Daily breakdown by category
SELECT reading_date,
       COUNT(*) FILTER (WHERE (ingestion_source & 1) <> 0) AS unknown_scheme,
       COUNT(*) FILTER (WHERE (ingestion_source & 2) <> 0) AS unknown_operator,
       COUNT(*) FILTER (WHERE (ingestion_source & 4) <> 0) AS operator_not_mapped,
       COUNT(*) FILTER (WHERE (ingestion_source & 8) <> 0) AS phone_absent,
       COUNT(*) FILTER (WHERE ingestion_source = 0)        AS normal
FROM <schema>.flow_reading_table
GROUP BY reading_date
ORDER BY reading_date DESC;
```

## 5. How to track it in the logs

Logger: `org.arghyam.jalsoochak.telemetry.service.GlificImageWorkflowService` (INFO).

| What                                                       | Grep                                                                |
| ---------------------------------------------------------- | ------------------------------------------------------------------- |
| Every leniently-recorded submission (canonical audit line) | `assam_reading_lenient_recorded`                                    |
| Unknown-scheme events (with the auto-provisioned id)       | `assam_reading_lenient reason="scheme_not_found"`                   |
| Unknown-operator events (masked phone)                     | `assam_reading_lenient reason="operator_not_found"`                 |
| Operator-not-mapped events                                 | `assam_reading_lenient reason="operator_not_mapped_to_scheme"`      |
| Phone-less submissions (both outcomes)                     | `assam_reading_phone_absent`                                        |
| …credited to the scheme's pump operator                    | `assam_reading_phone_absent reason="operator_inferred_from_scheme"` |
| …with no pump operator on the scheme (sentinel)            | `assam_reading_phone_absent reason="no_operator_mapped_to_scheme"`  |
| The actual phone behind an unknown-operator row (PII)      | `rawContactId=` — **DEBUG only**                                    |

The `assam_reading_lenient_recorded` line prints `ingestionSource`, boolean flags
(`unknownScheme` / `unknownOperator` / `operatorNotMapped` / `phoneAbsent`), `operatorId`,
`schemeId`, the submitted scheme ids, and the **masked** phone (`****1234`, `n/a` when none was
submitted). Raw phone numbers only ever appear at DEBUG, per the project privacy rule.

## 6. Analytics behaviour

- **Totals / scheme-level KPIs:** count immediately — `fact_meter_reading_table` stores `scheme_id`
  as a plain value with no FK to `dim_scheme`, so the reading is counted as soon as it is recorded.
- **Region / district breakdowns:** these join `dim_scheme` → `dim_lgd_location`. A placeholder
  scheme has no location mapping, so leniently-recorded readings show up under **Unknown / Unmapped**
  (or are omitted from a region roll-up) until the placeholder is reconciled to a real scheme with an
  LGD mapping. This is the agreed behaviour, not a bug.

## 7. Reconciliation (once the real scheme/operator is added)

1. Create the real scheme/operator through the normal admin flow (this also propagates to analytics
   via the usual `SCHEME_CREATED` / `USER_CREATED` events).
2. Re-point the recorded rows and clear the tag, e.g. for an unknown scheme:

   ```sql
   UPDATE <schema>.flow_reading_table
   SET scheme_id = :realSchemeId,
       ingestion_source = ingestion_source & ~1   -- clear UNKNOWN_SCHEME bit
   WHERE scheme_id = :placeholderSchemeId;
   ```

   Then soft-delete the placeholder (`UPDATE … scheme_master_table SET deleted_at = NOW() …`).
3. Analytics fact rows already written against the placeholder `scheme_id` are **not** updated
   automatically — re-point `analytics_schema.fact_meter_reading_table.scheme_id` the same way (or
   replay) if you need historical region attribution corrected.

## 8. Turning it off / reverting

- **Runtime off-switch (no deploy revert):** set `telemetry.lenient-ingestion.enabled=false`
  (env `TELEMETRY_LENIENT_INGESTION_ENABLED=false`). Submissions with a missing scheme/operator go
  back to being rejected exactly as before; the tracking columns and any already-recorded rows stay.
- **Full code removal:** every change is marked `LENIENT-INGEST`, and the optional-phone work on top
  of it is marked `PHONE-OPTIONAL`. To find them:

  ```bash
  grep -rn "LENIENT-INGEST\|PHONE-OPTIONAL" backend/ docs/
  ```

  Reverting the optional phone means restoring `@NotBlank` on `AssamReadingRequest.phoneNumber` and
  dropping `resolveOperatorFromScheme` / `findFirstPumpOperatorForScheme`; already-recorded rows keep
  their `PHONE_ABSENT` bit.
  Touched files: `database/V31__add_lenient_ingestion_tracking.sql`,
  `telemetry-service/.../service/IngestionSource.java`,
  `.../service/GlificImageWorkflowService.java`, `.../service/GlificOperatorContextService.java`,
  `.../service/BfmReadingService.java`, `.../repository/TelemetryTenantRepository.java`,
  `.../dto/requests/CreateReadingRequest.java`, `telemetry-service/.../application.yml`.
  The DB columns are additive and safe to leave in place even if the code is removed.
