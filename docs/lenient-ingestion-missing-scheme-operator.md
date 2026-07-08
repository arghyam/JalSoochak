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

> Note: the payload always carries a phone and a scheme id (bean-validation requires them). The drop
> is about those values **not existing in our DB**, which is a different thing from being present in
> the request.

## 2. What changed

Instead of rejecting, the Assam path now **records the submission and tags it** so nothing is lost
and it can be filtered / reconciled later. Three cases, each represented differently:

| Case | Representation | `ingestion_source` bit |
|---|---|---|
| Scheme id not in our records | recorded against an **auto-provisioned placeholder scheme** (`scheme_master_table.is_auto_provisioned = TRUE`) | `1` `UNKNOWN_SCHEME` |
| Phone not in our records | recorded against the single **sentinel "Unknown operator"** (`user_table.is_auto_provisioned = TRUE`, `status = 0`) | `2` `UNKNOWN_OPERATOR` |
| Scheme + operator exist but are not mapped | recorded against the **existing** scheme (no mapping created) | `4` `OPERATOR_NOT_MAPPED` |

Bits are combined (e.g. unknown scheme **and** unknown operator → `1 | 2 = 3`). A normal submission
has `ingestion_source = 0`.

Because the reading becomes a real row and publishes `METER_READING_RECORDED`, it flows into
analytics' `fact_meter_reading_table` and **counts in totals / scheme-level KPIs automatically**.
See §6 for the region-rollup caveat.

## 3. Database changes (migration `V31__add_lenient_ingestion_tracking.sql`)

Applied to every existing `tenant_%` schema and baked into `create_tenant_schema()` for new tenants.

### `flow_reading_table`
| Column | Type | Meaning |
|---|---|---|
| `ingestion_source` | `SMALLINT NOT NULL DEFAULT 0` | Bitmask (0 = normal). `1`=unknown scheme, `2`=unknown operator, `4`=operator-not-mapped. |
| `submitted_state_scheme_id` | `VARCHAR(255)` | Raw `state_scheme_id` from the payload (for reconciliation). Null for normal rows. |
| `submitted_centre_scheme_id` | `VARCHAR(255)` | Raw `centre_scheme_id` from the payload. Null for normal rows. |
| `submitted_phone_hash` | `VARCHAR(64)` | HMAC of the digit-normalized submitted phone (no raw PII). Set only for the unknown-operator case. |

Partial index `idx_<schema>_flow_ingestion_source ON flow_reading_table(ingestion_source) WHERE ingestion_source <> 0`.

### `scheme_master_table`
| Column | Type | Meaning |
|---|---|---|
| `is_auto_provisioned` | `BOOLEAN NOT NULL DEFAULT FALSE` | `TRUE` for placeholder schemes created by the lenient path. |

Partial index `idx_<schema>_scheme_auto_prov … WHERE is_auto_provisioned`.
Placeholder rows have `scheme_name = 'Auto-provisioned scheme (state:<id>|centre:<id>)'`,
`work_status = 0`, `operating_status = 0`, and the submitted ids in `state_scheme_id`/`centre_scheme_id`.

### `user_table`
| Column | Type | Meaning |
|---|---|---|
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

-- Daily breakdown by category
SELECT reading_date,
       COUNT(*) FILTER (WHERE (ingestion_source & 1) <> 0) AS unknown_scheme,
       COUNT(*) FILTER (WHERE (ingestion_source & 2) <> 0) AS unknown_operator,
       COUNT(*) FILTER (WHERE (ingestion_source & 4) <> 0) AS operator_not_mapped,
       COUNT(*) FILTER (WHERE ingestion_source = 0)        AS normal
FROM <schema>.flow_reading_table
GROUP BY reading_date
ORDER BY reading_date DESC;
```

## 5. How to track it in the logs

Logger: `org.arghyam.jalsoochak.telemetry.service.GlificImageWorkflowService` (INFO).

| What | Grep |
|---|---|
| Every leniently-recorded submission (canonical audit line) | `assam_reading_lenient_recorded` |
| Unknown-scheme events (with the auto-provisioned id) | `assam_reading_lenient reason="scheme_not_found"` |
| Unknown-operator events (masked phone) | `assam_reading_lenient reason="operator_not_found"` |
| Operator-not-mapped events | `assam_reading_lenient reason="operator_not_mapped_to_scheme"` |
| The actual phone behind an unknown-operator row (PII) | `rawContactId=` — **DEBUG only** |

The `assam_reading_lenient_recorded` line prints `ingestionSource`, boolean flags
(`unknownScheme` / `unknownOperator` / `operatorNotMapped`), `operatorId`, `schemeId`, the submitted
scheme ids, and the **masked** phone (`****1234`). Raw phone numbers only ever appear at DEBUG, per
the project privacy rule.

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
- **Full code removal:** every change is marked `LENIENT-INGEST`. To find them:
  ```bash
  grep -rn "LENIENT-INGEST" backend/ docs/
  ```
  Touched files: `database/V31__add_lenient_ingestion_tracking.sql`,
  `telemetry-service/.../service/IngestionSource.java`,
  `.../service/GlificImageWorkflowService.java`, `.../service/GlificOperatorContextService.java`,
  `.../service/BfmReadingService.java`, `.../repository/TelemetryTenantRepository.java`,
  `.../dto/requests/CreateReadingRequest.java`, `telemetry-service/.../application.yml`.
  The DB columns are additive and safe to leave in place even if the code is removed.
