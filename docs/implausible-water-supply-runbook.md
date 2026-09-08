# Implausible water supply check — ops runbook

The check refuses a meter reading whose implied daily volume is more than the scheme's connected
population could physically drink. A refused submission is **stored but quarantined**: kept in the
tenant database, excluded from every baseline, and never published to the warehouse.

This document is for whoever is on call when a scheme stops reporting, an operator says their
reading "didn't go through", or the daily report shows a gap nobody expected. It describes the
behaviour as shipped. The design rationale lives in the implementation plan; this is the operational
half.

**Scope.** `POST /api/v1/telemetry/readings` and `PUT /api/v1/telemetry/readings` only — the Assam
API endpoints. The Glific/WhatsApp paths are untouched: a rejection inside a live WhatsApp
conversation has no correction path, whereas an API caller gets a 400 and can resubmit.

---

## 1. The first thing to know

**A quarantined reading makes the scheme look non-reporting and the operator look absent for that
day.** That is the intended consequence, not a pipeline fault.

Withholding the row from Kafka means `fact_meter_reading_table`, `dim_operator_attendance` and
`fact_water_quantity` all get nothing for that scheme-day. So the gap surfaces first on the daily
report or an attendance dashboard, usually to someone who has never heard of this check. Before
chasing a consumer lag or a failed publish, check for a quarantined row:

```sql
SELECT id, scheme_id, created_by, reading_date, confirmed_reading, quarantine_reason
FROM   tenant_<state_code>.flow_reading_table
WHERE  scheme_id = :schemeId
  AND  reading_date = :date
  AND  quarantine_reason <> 0;
```

`quarantine_reason` is `0` = ordinary reading, `1` = `IMPLAUSIBLE_WATER_SUPPLY`. A row with `1` is
the explanation: the reading is there, it is simply not counted.

---

## 2. What the check does

```
litres     = (confirmed_reading − baseline) × 1000        -- m³ index to litres
population = connections × AVERAGE_MEMBERS_PER_HOUSEHOLD
ceiling    = population × limit-per-person-litres          -- default 150 L/person/day
quarantine iff litres > ceiling
```

`baseline` is the latest **non-quarantined** confirmed reading strictly before this one.
`connections` falls back `fhtc_count` → `planned_fhtc` → `house_hold_count`, first non-zero wins.

Two things follow that are worth holding onto:

- The ceiling scales with the scheme. A hamlet and a town do not share an allowance.
- Quarantined rows are excluded from the baseline, so one bad day cannot poison the next day's
  comparison.

### When it does not run at all

Any of these and the reading is served exactly as it was before the check existed:

| Condition | Why |
| --- | --- |
| `mode` is `OFF`, or `AUDIT` | See §3 |
| Tenant schema predates the V40 migration | No column to mark the row with |
| Channel is not BFM | Only BFM readings are cumulative m³ indices; the delta is not a volume otherwise |
| Meter was replaced | The delta across a meter swap is meaningless |
| No earlier reading for the scheme | Nothing to subtract; no derivable volume |
| `confirmed_reading ≤ baseline` | Delta clamps to zero |
| Scheme has `fhtc_count`, `planned_fhtc` **and** `house_hold_count` all `0` | No population, so no ceiling |
| `AVERAGE_MEMBERS_PER_HOUSEHOLD` unparseable **and** no env default set | Same |

The last two are the ones to watch. **A scheme with no connection or household counts is never
checked** — fixing its master data is what turns the check on for it, and until then enforcing is a
no-op for that scheme.

---

## 3. Configuration

```yaml
telemetry:
  supply-plausibility:
    mode: ${TELEMETRY_SUPPLY_PLAUSIBILITY_MODE:AUDIT}
    limit-per-person-litres: ${HIGH_QUANTITY_THRESHOLD_LIMIT_PER_PERSON:150}
    default-members-per-household: ${DEFAULT_MEMBERS_PER_HOUSEHOLD:5}
```

| Mode | Behaviour |
| --- | --- |
| `OFF` | The check never runs. |
| `AUDIT` (default) | Evaluates and logs what it *would* have quarantined. No anomaly, no quarantine, no rejection — the reading is served normally. |
| `ENFORCE` | Quarantines and rejects. |

**The kill switch is a restart, not a deploy.** Changing `TELEMETRY_SUPPLY_PLAUSIBILITY_MODE` and
restarting telemetry-service is the whole procedure. A bad startup value fails the boot with a
message naming the property, which in a rolling deploy leaves the previous container serving.

`AVERAGE_MEMBERS_PER_HOUSEHOLD` is a per-tenant config key in
`common_schema.tenant_config_master_table`, stored as `{"value":"4.5"}`. A malformed value falls back
to `default-members-per-household` rather than failing the reading.

150 L/person/day is roughly 2.7× the 55 LPCD design norm. **It is a policy number, not one derived
from production data.** If the audit week says it is wrong, change it.

### Turning it on

1. Ship at `AUDIT`. Nothing is quarantined.
2. Run a full week across all live tenants. Watch `implausible_supply.would_quarantine` and
   `implausible_supply.skipped{reason="no_population"}` — a high count of the latter means master
   data is missing and enforcing would do nothing for those schemes.
3. Review the would-be rejections against what the field says actually happened. Adjust the limit if
   the data says so.
4. Flip to `ENFORCE`. Watch the 400 rate on `POST /readings` and the volume of type-10 anomalies.

Do not enforce on day one. Quarantining on an unvalidated threshold drops real field submissions, and
the attendance side effect in §1 makes the loss visible on the daily report before anyone traces it
back here.

---

## 4. Releasing a quarantined reading

`PUT /api/v1/telemetry/readings` with a corrected `confirmed_reading`. A value that passes the check
is written, clears `quarantine_reason`, and publishes the row to analytics for the first time. That
is the only supported release path.

The flag is cleared on **every** successful correction, including under `mode=OFF` — otherwise a row
quarantined during an earlier `ENFORCE` window would sit outside every baseline with no route back.

`POST /readings/reset-latest` also clears the marker, because the reset zeroes the value the marker
described.

**Do not hand-edit `confirmed_reading` in the database.** It is the one action that puts the tenant
DB and the warehouse permanently out of step: the row would pass telemetry's baseline queries while
analytics still holds nothing for that day, and no later reading fixes the mismatch.

---

## 5. When a correction is refused

A `PUT` whose new value is *also* implausible is refused. This is the case most likely to be
misread, so precisely:

- **Nothing is written.** The reading keeps its stored value; analytics keeps whatever it already
  had. The response is `400` with `errorCode: ABNORMAL_READING`.
- **The reading row carries no trace of the attempt.** No counter, no second marker,
  `quarantine_reason` unchanged.
- **The only record is a type-10 anomaly**, and its `reason` text says which case it was.

To find attempts against a reading, query `anomaly_table` by scheme and date — not
`flow_reading_table`:

| `reason` | Meaning |
| --- | --- |
| `Submitted reading implies an implausible daily water supply for this scheme.` | **A** — a POST submission was quarantined |
| `Correction rejected: implies an implausible daily water supply. The published reading is unchanged.` | **B** — a correction was refused over an already-published reading. The published value stands and the day *is* counted |
| `Correction rejected: implies an implausible daily water supply. The reading remains quarantined.` | **C** — a correction was refused over a quarantined reading. The day is still missing from analytics and needs a plausible value before it will ever appear |

B and C are separated because they need different follow-up. B is cosmetic — the data is fine, someone
tried to change it and was refused. C is an outstanding gap.

The reason strings are exact constants with no numbers in them, so they can be counted with a
`GROUP BY`. The per-row numbers are in the structured columns: `overridden_reading` is the value that
failed and `previous_reading` is the baseline it was measured against, so
`(overridden_reading − previous_reading) × 1000` reproduces the litres from the row alone.

Rejected attempts accumulate — each one lands its own anomaly. The client may simply retry with a
different value.

---

## 6. When the rejection was wrong

If a refused reading was actually correct — a genuine burst, a tanker fill, or FHTC master data that
understates the scheme — the fix is upstream of the check, not around it:

1. **Correct the scheme's master data** (`fhtc_count`, `planned_fhtc`, `house_hold_count`) if the
   population is understated, then resubmit the correction. The ceiling is recomputed from current
   master data on every attempt, so no replay is needed.
2. **Or raise `limit-per-person-litres`** if the threshold itself is wrong for this deployment, and
   restart.
3. **Or set `mode=OFF`** and restart if the check is misfiring broadly enough to be blocking field
   work, then correct the readings through `PUT` — the release path still clears markers under `OFF`.

Surfacing exactly these cases is what the `AUDIT` period is for. A pattern of them after enforcement
is a signal that the threshold or the master data is wrong, not that operators are.

---

## 7. Signals

Metrics on `/actuator/prometheus`, all tagged `path` (`submission` | `correction`) and `mode`:

| Metric | Meaning |
| --- | --- |
| `implausible_supply.quarantined` | Acted on — a reading was quarantined |
| `implausible_supply.would_quarantine` | `AUDIT` only — what enforcement would have caught |
| `implausible_supply.accepted` | Evaluated and passed |
| `implausible_supply.skipped` | Not evaluated; `reason` tag says which condition from §2 |

Log lines, all prefixed `implausible_supply_`:

- `implausible_supply_rejected` / `implausible_supply_would_reject` (WARN) — carries submitted value,
  baseline, litres, ceiling and population
- `implausible_supply_skipped reason=no_population` (WARN) — the fixable master-data gap
- other skips and acceptances at DEBUG

**The ceiling and the population appear in the log line and nowhere else.** They are deliberately
absent from the API response and from the anomaly row: an API-key holder who learns the ceiling can
solve for the scheme's connection count and the per-person limit in two submissions. The consequence
to be aware of — *the ceiling as it stood on the day is not persisted*, so a retrospective audit
either reads the log or reconstructs an approximate ceiling from current master data.

---

## 8. Known interactions

- **Reset then resubmit.** A quarantined API submission with no image has a `0` sentinel for
  `extracted_reading` and an empty `image_url`. Resetting it zeroes `confirmed_reading` too, at which
  point the row matches the same-day placeholder predicate and the next submission reuses it. The
  reuse paths pass no quarantine reason — meaning "leave the column alone" — so a stale marker would
  silently withhold a perfectly good reading. The reset path clears the marker for this reason. **Any
  future path that writes a new value onto an existing row must resolve the marker too.**
- **Pre-V40 tenant schemas** are skipped entirely rather than checked. Storing a quarantined row in a
  schema with no column to mark it would leave it indistinguishable from an accepted one.
- **`OVER_WATER_SUPPLY` (anomaly type 8) is a different check** on the WhatsApp path, and this mode
  does not govern it. Type 8 means "above the tenant's configured tolerance over the norm"; type 10
  means "physically impossible for this population". Type 8 also still computes its ceiling in the
  wrong units — a known, deliberately unfixed bug — so do not read a type-8 anomaly as a
  plausibility judgement.
