# ELM & PDU Reading Channels

**Scope:** how a scheme with no bulk flow meter (BFM) gets a daily water quantity from its pump's
electricity meter (**ELM**) or from how long its pumps ran (**PDU**), from the moment a reading is
submitted to the analytics tables. Also covers what that required of the BFM path: one fact row per
submission, corrections that republish the stored reading, and recalculation from stored readings
under a per-scheme lock.

**Services:** `telemetry-service` (submission, corrections, snapshot, backfill), `analytics-service`
(calculation), `tenant-service` (ELM formula config), `user-service` and `scheme-service` (SO/SDO
screens), plus `backend/database/`.

**Shipped as:** branch `elm-pdu-mock-impl`, not yet merged. The decisions and the questions answered
along the way are in [ELM_PDU_CHANNEL_ADDITION_PLAN.md](../ELM_PDU_CHANNEL_ADDITION_PLAN.md).

| Commits | Service | Contents |
| --- | --- | --- |
| `d893e347` | telemetry | WhatsApp manual readings publish `METER_READING_RECORDED`; `ReadingRepublisher` |
| `eb43e3f7` | analytics | One fact row per submission (V56), `WaterQuantityRecalculationService`, scheme lock |
| `e5548edd`, `606b4c0c` | user, scheme, telemetry | SO/SDO screens show BFM only; `LAG` ordering fix |
| `0191fff5`, `e1ca6936`, `f9590a3f`, `86e5eb2c`, `9741fe20`, `27474504`, `26499d3b` | telemetry | Channel and unit on the row (V56), `reading_unit`, capture step, OCR by channel, channel-scoped lookups and corrections, calculation snapshot |
| `09d9cd4f`, `41574be9` | analytics | Starting-point rules across channels |
| `95920447`, `526542b7`, `26660f6b` | database, tenant, analytics | V57, `ELM_WATER_QUANTITY_FORMULA`, the ELM and PDU calculators |
| `01bdd715`, `728435c9`, `4a1b0382` | analytics | Lead-review fixes: calculable-total fallback, PDU pump sum, moved readings, bad version → DLT |
| `b11003ed`, `4b7369f7` | telemetry | PDU 1,440-minute day limit, under a lock |
| `ddd704f7`, `74a786d5`, `9190c90b`, `e0db17f5`, `5feecc54` | telemetry | Republish endpoint for ELM and PDU readings |

**Not included:** ELM and PDU over WhatsApp (phase 6 of the plan). An ELM OCR model. An API for pump
ratings or `k_factor`. Showing ELM or PDU readings on the SO/SDO screens. The IOT and MAN channels.

---

## 1. What it does

| | Before | Now |
| --- | --- | --- |
| Water quantity | BFM only. ELM and PDU readings were stored, and their day skipped | BFM, ELM and PDU |
| Unit of a reading | Implicitly the meter's own | Optional `reading_unit`, converted to the channel's standard unit; `submitted_unit` records what was sent |
| OCR provider | Tenant override, else a direct call to the built-in BFM model | Chosen by channel through the registry. ELM and PDU photos are rejected |
| Fact rows | A new row per publish, so a correction added a second | One row per submission, versioned |
| Corrections | Two paths worked out water quantities themselves | Every path republishes the stored row; analytics recalculates |
| Day's total | Worked out from the event | Worked out from stored readings, by channel, under a per-scheme lock |
| WhatsApp manual readings | Never reached analytics | Published like any other reading |
| SO/SDO staff screens | Every reading | BFM only |

**Pump data is entered by SQL.** The ratings the formulas need live in the tenant schema
(`asset_pump_registry_table`, `scheme_master_table.k_factor`) and have no API yet. A tenant also needs
an ELM formula configured (§8). Until both are in place, ELM and PDU readings are stored but their day
has no total; §7 re-sends them once they are.

---

## 2. Moving parts

```text
 tenant-service                  telemetry-service                         analytics-service
 ──────────────                  ─────────────────                         ─────────────────
 ELM_WATER_QUANTITY_FORMULA ──┐  POST /readings, /readings/formats/{f}     FactServiceImpl.ingestMeterReading
                              │  WhatsApp photo, manual reading              │ parse version (bad → DLT)
 tenant schema (entered by SQL)  │                                           │ lock the scheme(s)
   asset_pump_registry_table  │  ▼                                           │ upsert one row per submission
   scheme_master_table.k_factor  BfmReadingService.createReading             ▼
                              │    1 resolve the channel                   WaterQuantityRecalculationService
                              │    2 ReadingCapture: value | photo → OCR     │ day's channel → kind → amount
                              │    3 PduDayLimit (PDU only)                  │ WaterQuantityCalculator
                              │    4 insert channel + submitted_unit         │   Bfm | Elm | Pdu
                              └──▶ 5 CalculationParametersSnapshotter        │   PumpParameterAggregator
                                   ▼                                         ▼
                                 METER_READING_RECORDED ───────────────▶  fact_meter_reading_table
                                   ▲  sourceReadingId, sourceUpdatedAt,   fact_water_quantity_table
                                   │  calculationParameters
                                 ReadingRepublisher ◀── corrections: PUT /readings, reset-latest,
                                                        PATCH yesterday-final-reading, WhatsApp
                                                    ◀── POST /internal/readings/republish
```

---

## 3. Data model

### 3.1 Channels

| Channel | Code | Kind | Standard unit | `reading_unit` accepted | Photo | Day's water |
| --- | --- | --- | --- | --- | --- | --- |
| BFM | 1 | `METER_INDEX` | `m3` | `m3`, `kL`, `L` (also `m³`, `litre`, `liter`) | Yes | (latest − starting point) × 1000 |
| ELM | 2 | `METER_INDEX` | `kW.h` | `kW.h` (also `kWh`) | Once an ELM model exists | day's kWh → formula × `k_factor` |
| PDU | 3 | `PERIOD_AMOUNT` | `min` | `min`, `h` (also `hr`) | Never | Σ runs × Σ active pumps' L/min |
| IOT, MAN | 4, 5 | none | none | none | No | Not worked out; the day is left alone |

A `METER_INDEX` reading is a running total, so a day's amount is its latest reading minus a starting
point. A `PERIOD_AMOUNT` reading is one run on its own, so a day's amount is the sum of its runs.
Analytics holds the kind (`ReadingChannel.kind()`); telemetry holds the units and whether a photo can
be read (`standardUnit()`, `supportsImageReading()`).

The codes are also the reading channels' ids in `common_schema.channel_master_table`. A tenant stores
them in `flow_reading_table.channel_id` and `scheme_master_table.channel_id`, both keys into that
table. A NULL `channel_id` on a reading row is a BFM reading from before the channel was recorded, or
a row with no reading (placeholder, location, meter change).

`flow_reading_table.confirmed_reading` always holds the channel's standard unit, because many
queries do arithmetic on it directly. Nothing forces a scheme onto one channel.

### 3.2 Tenant schema — `backend/database/`

| Migration | Change |
| --- | --- |
| V56 `add_submitted_unit_to_flow_reading_table` | `flow_reading_table.submitted_unit VARCHAR(16)`, nullable since old rows have none. Column comments on `extracted_reading`, `confirmed_reading` and `submitted_unit` |
| V57 `default_asset_pump_registry_status_to_active` | `asset_pump_registry_table.status` gets `DEFAULT 1`. Column comments give the unit each pump rating must be entered in |
| V58 `add_channel_id_to_flow_reading_and_scheme_master_tables` | Seeds `channel_master_table` with ids 1–5 = BFM, ELM, PDU, IOT, MAN. `channel_id INTEGER` on both tables, keyed to `channel_master_table` (`NOT VALID` on `flow_reading_table`), and a trigger keeping `flow_reading_table.channel` and `channel_id` in step while pods writing either serve |
| V59 `fill_flow_reading_channel_id_and_validate_its_key` | Fills `flow_reading_table.channel_id` from `channel` in batches, then validates its key. A NULL `channel` stays NULL |
| V60 `drop_channel_from_flow_reading_and_scheme_master_tables` | Drops the trigger, its function and both `channel` columns, from existing tenant schemas and from provisioning |

V56 and V57 follow V54: existing tenant schemas first, then the `create_tenant_schema` wrapper, under a
`SHARE` lock on `tenant_master_table` so no tenant is provisioned in between. Both were checked on a
scratch database only, per the project's rule for simple column changes.

V58–V60 came after this branch, in releases of their own (§13). They replace the text
`flow_reading_table.channel` and the integer `scheme_master_table.channel`, which held a list position
rather than a code, with `channel_id`. As in V55, they run outside Flyway's transaction, with a commit
per tenant table and a 3 s `lock_timeout` with retries.

Already on `dev` before this branch: the pump table rename (V52), the pump and motor ratings (V53) and
`scheme_master_table.k_factor` (V54).

**The inputs entered by SQL.** Only pumps with `status = 1 AND deleted_at IS NULL` count.

| Column | Unit | Used by |
| --- | --- | --- |
| `pump_discharge_capacity` | L/min | PDU, F1, F2 |
| `units_consumed_per_hour` | kWh per hour | F1 |
| `motor_power` + `motor_power_unit` | `kW`, `HP` or `bHP` | F2 |
| `pump_efficiency`, `motor_efficiency` | fraction, 0 to 1 (0.7, not 70) | F3 |
| `pump_head` | metres | F3 |
| `scheme_master_table.k_factor` | multiplier; NULL means 1 | ELM only |

The ratings are plain `FLOAT`s, so nothing stops a wrong unit: a discharge capacity in m³/h instead of
L/min makes every PDU total about 16.7 times too high.

### 3.3 `analytics_schema` — analytics-service Flyway

`V56__add_submission_identity_and_calculation_inputs_to_fact_meter_reading.sql` (first committed as
V50 and renumbered after the dev merge brought V50–V55):

```text
fact_meter_reading_table
  + source_reading_id       BIGINT     -- the tenant's flow_reading_table.id
  + source_updated_at       TIMESTAMP  -- that row's updated_at: the event's version
  + calculation_parameters  JSONB      -- telemetry's snapshot, ELM and PDU only
  uq_fact_meter_reading_source  UNIQUE (tenant_id, source_reading_id) WHERE source_reading_id IS NOT NULL
  idx_fact_meter_reading_tenant_scheme_date_lookup
                                (tenant_id, scheme_id, reading_date DESC, reading_at DESC, id DESC)
```

- All three columns are nullable. Older rows, and events from an older telemetry, have no source id
  and insert as before: NULLs never conflict in the unique index.
- The lookup index has the name and definition `scripts/water_quantity_units_fix.py` already builds,
  so on a database where that script ran (prod) the statement does nothing. It is a plain
  `CREATE INDEX`, because `CONCURRENTLY` hangs under Flyway inside Spring Boot (V43).
- `fact_water_quantity_table` gets **no** channel column. A day's channel is looked up at query time
  from its latest reading (§6.3, §9).

---

## 4. Submission path — `telemetry-service`

### 4.1 Order of steps in `createReading`

1. Scheme and operator checks, as before.
2. **Resolve the channel**: the declared `channel` (strict; an unknown value is a 400
   `CHANNEL_NOT_SUPPORTED`), else the operator's `user_channel_preference`. This now happens before
   anything is captured, because the channel decides which units are accepted and how a photo is read.
3. **Capture** (§4.2): a value was sent → `SubmittedValueCapture`; only a photo → `ImageReadingCapture`;
   neither → 400.
4. **Compare within the channel.** The latest-reading snapshot, the rollover history and the
   response's `lastConfirmedReading` are read from the same channel only, with a NULL channel counted
   as BFM. A PDU reading has no earlier reading to compare with, and no `lastConfirmedReading`.
5. The supply-plausibility guard runs for BFM only: it measures a water volume between two meter
   totals.
6. **Write** the row with its channel and `submitted_unit` in the insert itself (the follow-up
   `UPDATE` of the channel is gone). A PDU run is written through `PduDayLimit` (§4.4).
7. **Publish** `METER_READING_RECORDED` with the row's identity, version and snapshot (§4.5, §4.6).

Every refusal comes back as a `CreateReadingResponse` with an error code, never an exception: the
catch-all around the reading APIs would turn an exception into `PROCESSING_FAILED`.

### 4.2 Capture — `service/capture/`

`ReadingCapture.capture(CaptureInput)` returns a sealed `CaptureOutcome`: `Captured(CapturedReading)`,
`Rejected(errorCode, message)` or `Retry(message)`. `CapturedReading` holds the value in the standard
unit, the submitted unit, the extracted value, confidence, provenance and the OCR result.

**`SubmittedValueCapture`** — a typed or API-asserted value:

- matches `reading_unit` against the channel's units, ignoring case and surrounding spaces and
  accepting the listed alternative spellings, and stores only the UCUM code. Plurals such as `litres`
  are rejected, not guessed at;
- converts exactly with `BigDecimal` (every factor is a terminating decimal);
- rejects a PDU run over 1,440 minutes;
- `captureCorrection(channel, value, unit)` applies the same rules to a correction (§5).

A wrong unit is a 400 `READING_UNIT_NOT_SUPPORTED`, and its message never echoes the submitted value.

**`ImageReadingCapture`** — the OCR block, moved as it was, plus two checks before any OCR config is
read: a channel that can't read photos, and a `reading_unit` other than the channel's standard one
(OCR reads a meter in its standard unit). Unreadable images still record an anomaly; a channel with
no provider does not, because the photo is not at fault.

A photo sent **with** a value is accepted on every channel: the value is captured, the photo is kept,
and OCR doesn't run.

### 4.3 OCR by channel

| | BFM | ELM | PDU, IOT, MAN |
| --- | --- | --- | --- |
| Tenant keys | `ocr_provider`, `ocr_url`, `ocr_api_key`, `ocr_auth_header` | the same with `ocr_elm_` | none |
| Unset fields fall back to `ocr.*` | Yes, as before | **Never**; to `ocr.elm.*` instead | — |
| Default provider | `ocr.default-provider` | `ocr.elm.default-provider`, blank by default | — |

- Each `MeterReadingExtractor` declares its `channel()`. `FlowVisionBfmOcrExtractor` is BFM's, and is
  no longer `@Primary`: nothing injects a single extractor, so a photo is never read by another
  channel's model. `FlowVisionElmOcrExtractor` is ELM's.
- `OcrProviderRegistry.get(channel, providerId)` returns the tenant's provider if it is registered for
  that channel, else the channel's default (with a WARN if a named provider was not found), else
  empty. Empty is a 400 `IMAGE_NOT_SUPPORTED_FOR_CHANNEL`.
- ELM never falls back to `ocr.*`, so an ELM photo can never be sent to the BFM endpoint with the BFM
  key. While `ocr.elm.default-provider` is blank, an ELM photo is read only for a tenant whose
  `ocr_elm_provider` names a registered ELM provider; every other ELM photo is rejected. A default that
  isn't registered for ELM fails startup.
- `OcrReadingsRetryService` runs the extractor the capture picked. Its resilience instances stay per
  provider.
- WhatsApp photos go through the same `createReading`, with the channel from the operator's preference.
  The rejection is localised in `ConversationLocalizationService` (English and Hindi).

### 4.4 PDU limits

| Limit | Enforced by | Result |
| --- | --- | --- |
| One run ≤ 1,440 minutes | `SubmittedValueCapture` | 400 `ABNORMAL_READING`, nothing stored, no anomaly |
| A scheme's PDU minutes on one day ≤ 1,440 | `PduDayLimit.writeWithinLimit` | The same |

Both apply to `POST /readings`, `/readings/formats/{format}`, `PUT /readings` and both WhatsApp paths.
The day limit is per scheme, not per pump: a run's minutes are the time all active pumps ran together.

`writeWithinLimit` takes `pg_advisory_xact_lock(hashtextextended('pdu_day:<schema>:<scheme>:<date>', 0))`,
sums the day's other PDU rows, and writes, all in one transaction, so two runs sent at the same moment
can't both pass. A correction's own row, and the row a WhatsApp manual value overwrites, are left out
of the sum. `PUT /readings` joins its existing transaction.

### 4.5 Versioning the row

- Every `flow_reading_table` write sets `updated_at = clock_timestamp()`. `NOW()` is the time the
  transaction *started*, so two concurrent corrections could store versions in the reverse order of
  their commits, and analytics would keep the losing value.
- Writes return `FlowReadingVersion(id, updatedAt)` from the same statement.
- The event carries them as `sourceReadingId` and `sourceUpdatedAt` (ISO-8601 local date-time). The
  version is never the Java clock.

### 4.6 The calculation snapshot

`CalculationParametersSnapshotter` runs for ELM and PDU only, on every publish:

```json
{
  "version": 1,
  "elmFormula": "F2",
  "kFactor": 0.95,
  "pumps": [
    { "pumpId": 12, "pumpDischargeCapacityLpm": 500, "pumpEfficiency": 0.7, "pumpHeadM": 40,
      "motorPower": 7.5, "motorPowerUnit": "HP", "motorEfficiency": 0.85, "unitsConsumedPerHour": 5 }
  ]
}
```

- `elmFormula` is read from `ELM_WATER_QUANTITY_FORMULA`. A missing or unreadable value is sent as
  `null`, with an `elm_formula_unreadable` WARN in the second case.
- `kFactor` comes from `scheme_master_table`. PDU's snapshot has neither field: its formula uses
  neither.
- Pump values are sent exactly as stored, and analytics does all filtering, conversion and validation,
  so every calculation rule lives in one service. `FLOAT` columns are read with
  `BigDecimal.valueOf`, so 0.7 stays 0.7.
- The snapshot is taken again on every republish, so a corrected reading is calculated with the pump
  data current **at the correction**.

---

## 5. Corrections and republishing

| Path | Endpoint (under `/api/v1/telemetry`) | Channels | Unit field |
| --- | --- | --- | --- |
| WhatsApp manual reading | `POST /manual-reading` | any | none: standard unit |
| WhatsApp update previous reading | `POST /update-previous-reading` | any | none |
| State-IT correction | `PUT /readings` | any | `reading_unit` (also `readingUnit`) |
| State-IT reset | `POST /readings/reset-latest` | any | — |
| SO/SDO Fix-readings save | `PATCH /schemes/{schemeId}/yesterday-final-reading` | **BFM only** (§9) | none: `m3` |

A correction follows its row's channel's submission rules: the unit, the PDU limits, and
`submitted_unit` written. The BFM-only checks stay BFM-only: the supply-plausibility guard and the
WhatsApp manual reading's maximum (water norm plus oversupply threshold over the last meter total). For
PDU, a correction changes the one run it targets, not the day.

**Every path publishes through `ReadingRepublisher`**, which:

- reads the row back by id (`findFlowReadingById`). `correlation_id` can't be used: the WhatsApp
  flows reuse it across rows;
- builds the event from the row as stored, with its own correlation id, `sourceReadingId`,
  `sourceUpdatedAt` and a fresh snapshot;
- **withholds a row that is still quarantined** (`reading_republish_withheld`), as the submission path
  does. A path that releases a row clears the marker first.

**What changed for two paths.** The PATCH and the WhatsApp update-previous-reading used to work out
the corrected day's and the following day's water quantities themselves and send
`WATER_QUANTITY_RECORDED`. They now republish the row, and analytics recalculates both days from the
stored readings (§6.3). `publishWaterQuantityRecorded` and its "day before" and "day after" lookups are
gone. This also fixes a bug: with no earlier reading, those paths published the whole meter total as
one day's water.

`WATER_QUANTITY_RECORDED` now carries only outage, non-submission and meter-change reasons. Analytics'
handler for it stays.

**WhatsApp manual readings** go through `SubmittedValueCapture` and are published in all three
branches: the pending meter-change row, the operator's row today, and a new row. Before this branch
they never reached analytics.

---

## 6. Calculation — `analytics-service`

### 6.1 Event contract

Three additive fields on `MeterReadingEvent`; the analytics copy already ignores unknown properties.

| Field | Type | Notes |
| --- | --- | --- |
| `sourceReadingId` | `Long` | `flow_reading_table.id` |
| `sourceUpdatedAt` | `String` | The row's `updated_at` as the database wrote it |
| `calculationParameters` | `CalculationParameters` | ELM and PDU only; version 1 |

### 6.2 Ingestion — `FactServiceImpl.ingestMeterReading`

All in one transaction:

1. **Parse the version.** An unparseable `sourceUpdatedAt` throws `MalformedEventException`, which the
   Kafka error handler does not retry: the record goes to `<topic>.DLT` at once and is counted.
   Stored as unknown, the upsert would have dropped it as stale against any version.
2. **Lock** the scheme (§6.5). When a correction has moved the submission to another scheme or day,
   both schemes are locked, in ascending key order, and where the submission is stored is read again
   under the locks. A row moved meanwhile to a scheme not locked throws
   `ConcurrencyFailureException`, and Kafka retries.
3. **Upsert** through `FactIngestionRepository.upsertMeterReading`:

   ```sql
   INSERT INTO analytics_schema.fact_meter_reading_table (...) VALUES (...)
   ON CONFLICT (tenant_id, source_reading_id) WHERE source_reading_id IS NOT NULL
   DO UPDATE SET ...
   WHERE fact_meter_reading_table.source_updated_at IS NULL
      OR fact_meter_reading_table.source_updated_at <= EXCLUDED.source_updated_at
   RETURNING id
   ```

   No row back means a newer version is stored: the event is dropped and counted
   (`meter_reading.stale_event`). `<=` makes a replay of the same version harmless. An event with no
   `sourceReadingId` always inserts. `JdbcTemplate`, because a Spring Data `@Modifying` query can't
   return the `RETURNING` row; it joins the JPA transaction.
4. `ensureDateExists` and operator attendance, as before.
5. **Recalculate** the reading's day, then the follow-up days (§6.3).
6. If the submission moved, **recalculate the day it left**: written only if it changed, or its
   reading-derived total removed when it has no reading left.

### 6.3 Recalculation rules — `WaterQuantityRecalculationService`

Everything is read back from `fact_meter_reading_table`, never taken from the event, so the result
depends only on which readings are stored, not on the order they arrived in.

**The day's channel** is the channel of its latest reading (`reading_at DESC, id DESC`), with NULL
counted as BFM. A channel with no kind or no calculator (IOT, MAN) leaves the day's row untouched and
counts `water_quantity.calculator.missing`.

**`METER_INDEX` (BFM, ELM).** The amount is `max(0, latest − starting point)`.

- The starting point is the latest reading on the same channel before the day, with
  `confirmed_reading > 0`.
- It is **not used** when another channel has a reading dated strictly between it and the day. Those
  dates were counted on the other channel; measuring across them would count their water twice.
- With no starting point, the day is worked out from **another channel's reading that day**, if one
  gives the day a total of its own: a `METER_INDEX` reading with a starting point, or PDU runs. So the
  day a scheme starts a new meter keeps what the old one measured. Only with none is the amount 0.

**`PERIOD_AMOUNT` (PDU).** Each of the day's runs is turned into litres with its own minutes and its
own snapshot, and the results are added up (`Math.addExact`). If any run can't be calculated, neither
can the day, so a total is never silently too low. Litres are stored only for the whole day.

**A total that can be calculated is never replaced by one that can't.** If the day's own channel gives
`NotDerivable` but another channel's latest reading that day gives litres, that total is used, logged
at WARN and counted in `water_quantity.channel_fallback`.

**Writing the result:**

| Outcome | Effect |
| --- | --- |
| Litres | Find-and-update the day's `fact_water_quantity_table` row. `user_id` and `submission_status` come from the reading the total was worked out from, not from the event; the reason columns are cleared |
| Can't be calculated | WARN + `water_quantity.not_derivable{channel, reason}`. The day's reading-derived row (both reason columns NULL) is deleted, counted in `water_quantity.day_total_removed`. A row holding a reason stays |
| Past `BIGINT` litres | Day left as it was; `water_quantity.unstorable` |
| No calculator | Day left as it was; `water_quantity.calculator.missing` |

**The follow-up.** After any reading, the next date with a reading on each `METER_INDEX` channel is
recalculated: that day may now start from this reading, or may no longer be allowed to start from
before it. One step is enough, because no later day depends on this one. A follow-up day is written
**only if** its total, user, status or a reason to clear changed: the SO/SDO pump-operator list shows
the row's `updated_at` as the operator's last submission.

### 6.4 Calculators and formulas

`WaterQuantityCalculator.calculate(WaterQuantityContext)` takes an **amount** in the channel's
standard unit plus the snapshot, and returns a sealed `WaterQuantityOutcome`: `Derived(long litres)`
or `NotDerivable(reason)`. Working out the amount moved out of the calculators into the kind rule
above, so `BfmWaterQuantityCalculator` is now just `WaterVolumeUnits.cubicMetresToLitres`, and BFM
results are unchanged.

| Code | Class | Litres |
| --- | --- | --- |
| BFM | `BfmWaterQuantityCalculator` | `m³ × 1000` |
| ELM F1 | `ConsumptionRateFormula` | `kWh × LPM × 60 / units_consumed_per_hour` |
| ELM F2 | `MotorPowerFormula` | `kWh × LPM × 60 / motor_kW` |
| ELM F3 | `HydraulicEnergyFormula` | `366.97 × kWh × Np × Nm / H × 1000` |
| PDU | `PduWaterQuantityCalculator` | `minutes × LPM × number of active pumps` |

- **`ElmWaterQuantityCalculator`** picks the formula by the snapshot's `elmFormula` code and multiplies
  by `k_factor` (NULL is 1; 0 or less is `INVALID_PARAMETER`). There is no default formula: none, or
  an unknown code, is `MISSING_FORMULA` even for 0 kWh. A new formula is a new `ElmVolumeFormula`
  bean.
- **`PumpParameterAggregator`** turns the snapshot's pumps into one value per parameter the formula
  asks for. It converts motor power to kW (`kW` as is, `HP × 0.7457`, `bHP × 0.9863 × 0.7457`; any
  other unit, or none, counts as missing and increments `water_quantity.motor_power_unit.unknown`),
  checks each pump's own value (0 or less, or an efficiency above 1, is `INVALID_PARAMETER`), then
  averages over the pumps that have one. Parameters are checked in `PumpParameter` order and the first
  failure is the reason.
- **Several active pumps run together.** For F1 and F2 the ratio of two averages equals the ratio of
  the totals. PDU needs the scheme's total rate, so it multiplies the average rate by the number of
  pumps: a pump missing its rate counts at the others' average. F3 is exact only for pumps with the
  same efficiencies and head.
- **Rounding:** `BigDecimal` with `MathContext.DECIMAL64`, rounded once, `HALF_UP`, to whole litres
  (`WaterVolumeUnits.wholeLitres`).

| Reason | Meaning |
| --- | --- |
| `MISSING_FORMULA` | ELM, and the tenant has no formula, or an unknown one |
| `NO_ACTIVE_PUMP` | The snapshot lists no active pump |
| `MISSING_PARAMETER` | No active pump has a value the formula needs |
| `INVALID_PARAMETER` | A value is 0 or less, an efficiency is above 1, or `k_factor` is 0 or less |

**Worked examples** (k = 1, one pump), used as the tests' expected values:

| Case | Inputs | Litres |
| --- | --- | --- |
| F1 | 10 kWh, 500 L/min, 5 kWh/h | 60,000 |
| F2 | 10 kWh, 500 L/min, 7.5 HP | 53,641 |
| F2 | 10 kWh, 500 L/min, 7.5 bHP | 54,386 |
| F3 | 10 kWh, Np 0.70, Nm 0.85, H 40 m | 54,587 |
| PDU | 90 min, 500 L/min (a k_factor of 0.9 is ignored) | 45,000 |

### 6.5 Concurrency

Telemetry publishes without a key and from an `@Async` executor, so a scheme's events can arrive in
any order and, with several analytics consumers, be processed at the same time. Both writers of a
day's total — a reading's recalculation and a reason event (`ingestWaterQuantity`) — take
`pg_advisory_xact_lock(namespace, key)` through `FactIngestionRepository` before their first write.

- The namespace is a constant derived from the table name, keeping these keys apart from
  tenant-service's two-int locks in the shared database. The key is `Objects.hash(tenantId, schemeId)`;
  two schemes that collide only make one wait.
- Transaction-scoped, so there is no unlock call. Both lock methods are
  `Propagation.MANDATORY`: outside a transaction the lock would be released at once.
- `WaterQuantityRecalculationService` is `MANDATORY` too, so it can't run without the caller's lock.

### 6.6 The recompute script

`db/scripts/recompute_water_quantity.sql` is the SQL form of the BFM rule, and
`WaterQuantityBackfillParityIntegrationTest` keeps the two identical. It now filters to BFM, applies
the "no starting point across another channel" rule, and returns `new_qty = NULL` for a day it must
not touch: a day whose latest reading is not BFM, and a BFM day with no starting point that another
channel read that day (the live path may work it out from that channel).
`scripts/water_quantity_units_fix.py` skips those as cases **A4** and **B3**, and keeps them out of its
"still in m³" figure.

---

## 7. Republishing stored readings — the backfill

`POST /api/v1/telemetry/internal/readings/republish` re-sends a tenant's stored ELM and PDU readings, so
their water quantities are worked out from the formula and pump data configured **now**. Saving the
configuration doesn't trigger it; it runs only when called.

| Header / field | Required | Notes |
| --- | --- | --- |
| `X-Internal-Token` | yes | Operations token. Its SHA-256 hex hash goes in `TELEMETRY_INTERNAL_AUTH_TOKEN_HASH` |
| `X-Tenant-Code` | yes | The tenant's state code, any case |
| `fromDate`, `toDate` | yes | Both included, at most 31 days |
| `stateSchemeId`, `centreSchemeId` | no | One scheme, state id first, as on `POST /readings`. Left out: every scheme |
| `channel` | no | `ELM` or `PDU`. Left out: both. BFM is refused |

**Why an operations token and not the tenant's API key.** The run acts on a whole tenant, and a
tenant's `X-Api-Key` is also held by its integrator. `InternalAuthFilter` guards everything under
`/api/v1/telemetry/internal`, so a new internal route is protected the moment it is mapped. It
compares digests in constant time and never logs the token. An unset or malformed hash **disables**
the routes (every call gets 401) rather than failing startup, since the route is run by hand and a
deploy that leaves it out must not take ingestion down. The api-gateway routes
`/api/v1/telemetry/**` publicly, so this token is the only thing in front of it.

**Why BFM is refused.** Its water quantity doesn't depend on configuration, and BFM fact rows from
before analytics V56 have no `source_reading_id`, so republishing would add a second row.

**How it sends.** `ReadingBackfillService` selects rows by `channel IN (...)`, so placeholder,
location, meter-change and issue-report rows (no channel) are never sent. Readings go oldest first,
**one at a time on the request thread**, each waiting up to 5 s for Kafka's acknowledgement
(`ReadingRepublisher.republishAndAwait`). A run on the shared `kafkaPublisherExecutor` (4 threads, a
500-slot queue) could fill its queue and get live submissions' events rejected. The run stops at the
first reading Kafka doesn't acknowledge and answers 503 with `notSentCount`; sending the same range
again is safe, because analytics updates the row it already holds.

```json
{"success": true, "data": {"republishedCount": 42, "withheldCount": 1}}
```

`withheldCount` is quarantined readings, which are never sent. Errors and their codes are listed in
[API_ENDPOINTS.md](../backend/API_ENDPOINTS.md).

**Before a tenant's first run**, delete its ELM and PDU fact rows that predate analytics V56 (no
`source_reading_id`), or each would be counted twice; the query and the delete are in
`API_ENDPOINTS.md`. They aren't relinked by `correlation_id`, which the WhatsApp flows share across
readings.

**With `ANALYTICS_READ_FROM_AGGREGATES` on**, the nightly job rebuilds only the last
`ANALYTICS_AGG_DAILY_LOOKBACK_DAYS` days. Republished older dates need a re-aggregation run
(`ANALYTICS_AGG_BACKFILL_ENABLED=true`, `ANALYTICS_AGG_BACKFILL_START_DATE` = the earliest `fromDate`).

---

## 8. Configuration

### `ELM_WATER_QUANTITY_FORMULA` — tenant-service

| Key | Type | `isPublic` | `managedValue` | `mandatory` |
| --- | --- | --- | --- | --- |
| `ELM_WATER_QUANTITY_FORMULA` | `GENERIC` | false | false | false |

Written through the generic `PUT /api/v1/tenants/{tenantId}/config` as `{"formula": "F1" | "F2" | "F3"}`
(`ElmFormulaConfigDTO`). The code is accepted in any case and stored in upper case
(`ElmFormula.fromCode`). An unknown code, a JSON `null` and `{}` are all 400s before anything is
stored: there is no default formula to fall back on. No event is published, because telemetry reads
the key each time it publishes an ELM reading. A change therefore applies to readings submitted or
corrected after it; earlier ones need §7.

### Other settings

| Setting | Service | Default | Notes |
| --- | --- | --- | --- |
| `TELEMETRY_INTERNAL_AUTH_TOKEN_HASH` (`telemetry.internal.auth.token-hash`) | telemetry | empty: internal routes disabled | SHA-256 hex of the token, never the token |
| `ocr_elm_provider`, `ocr_elm_url`, `ocr_elm_api_key`, `ocr_elm_auth_header` | tenant config rows | unset | Unset fields fall back to `ocr.elm.*`. Raw rows; tenant-service needs no change |
| `OCR_ELM_DEFAULT_PROVIDER`, `OCR_ELM_URL`, `OCR_ELM_API_KEY`, `OCR_ELM_AUTH_HEADER`, `OCR_ELM_CONNECT_TIMEOUT_MS`, `OCR_ELM_READ_TIMEOUT_MS` (`ocr.elm.*`) | telemetry | blank, blank, blank, `X-API-Key`, `5000`, `120000` | A blank default provider rejects ELM photos from tenants with no `ocr_elm_provider` |
| `ocr.*` | telemetry | as before | Now documented as BFM's only |

---

## 9. SO/SDO staff screens — BFM only

The screens Section Officers and Sub-Divisional Officers log into show and act on BFM readings only,
as if ELM and PDU readings didn't exist. Legacy NULL-channel rows count as BFM.

| Where | Change |
| --- | --- |
| user-service `PersonSchemeRepository` (scheme list, scheme view, pump-operator list and readings) | Every `flow_reading_table` read keeps `COALESCE(fr.channel_id, 1) = 1`, inside the CTE and **before** `LAG`, so an ELM or PDU value is never a BFM reading's previous one. Covers readings, water supplied, reporting rate and last submission time |
| The same, pump-operator list's analytics branch | Skips days whose latest `fact_meter_reading_table` reading is not BFM — the day-channel rule of §6.3, looked up at query time |
| The same, pump-operator list's flow-reading fallback | Returns **litres** (`ROUND((confirmed_reading − prev_confirmed) × 1000)`), like the analytics value it stands in for, so the `COALESCE` of the two no longer mixes units |
| scheme-service `SchemeDbRepository` fix-readings list | Each scheme's latest BFM reading |
| telemetry PATCH `yesterday-final-reading` | Targets the latest BFM reading; a scheme with none gets the existing 404 |

**A pre-existing bug fixed alongside (`606b4c0c`).** The scheme list and the pump-operator fallback
ran `LAG` over a *descending* window, so the latest reading's previous value was always NULL and
`lastWaterSupplied` had been NULL since `b50885b8`. The window is now ascending, and the operator
fallback is partitioned by scheme.

**Dashboard APIs are not filtered, even where a staff screen calls them**: `GET
/api/v1/pumpoperator/pump-operators/{id}` (shared with the village dashboard) and every
`/api/v1/analytics/…` endpoint. On the pump-operator view, the header counts every channel while the
readings list below it counts BFM only. Accepted.

---

## 10. Observability

**Metrics** — all in analytics-service except where noted. `channel` is the numeric code.

| Metric | Tags | Meaning |
| --- | --- | --- |
| `water_quantity.not_derivable` | `channel`, `reason` | A day that can't be calculated. **The one to alert on**: a tenant or scheme is missing configuration |
| `water_quantity.day_total_removed` | `channel` | A day that had a total lost it |
| `water_quantity.channel_fallback` | `channel`, `reason` | A day's total came from another channel because its own couldn't be calculated |
| `water_quantity.motor_power_unit.unknown` | none | A pump's `motor_power_unit` isn't kW, HP or bHP |
| `water_quantity.calculator.missing` | `channel` | A day on a channel with no calculator. Lost its `tenantId` and `schemeId` tags (cardinality); the WARN carries both |
| `water_quantity.implausible`, `water_quantity.unstorable` | `source` | As before, now in `WaterQuantityRangeReporter` |
| `meter_reading.stale_event` | none | An older version of a submission arrived late and was dropped |
| `meter_reading.source_updated_at.unparseable` | none | An event sent to the DLT |

Tenant and scheme go in the WARN line, never in tags.

**Logs**

- analytics: `Water quantity cannot be calculated: <reason> (channel=, tenantId=, schemeId=, date=)`,
  `Unknown motor_power_unit '<unit>' on pumpId=`, `Pump value out of range: <parameter>=<value> on
  pumpId=`, `Skipping stale METER_READING_RECORDED for sourceReadingId=`, `Reading sourceReadingId=
  moved from schemeId= date= to ...`.
- telemetry: `elm_formula_unreadable`, `reading_republish_withheld`, `reading_backfill` /
  `reading_backfill_stopped`, `internal_auth_rejected reason=disabled|missing|invalid`.
- The WhatsApp manual-reading and update-previous-reading error logs now mask the contact id.

---

## 11. Behaviour table

| Situation | Result | Visible as |
| --- | --- | --- |
| ELM reading, formula and pumps configured | Day's kWh → litres | — |
| ELM reading, tenant has no formula | Stored; day has no total | `not_derivable{reason=MISSING_FORMULA}` |
| PDU or ELM reading, scheme has no active pump | Stored; day has no total | `not_derivable{reason=NO_ACTIVE_PUMP}` |
| A required pump value missing on every pump | Stored; day has no total | `not_derivable{reason=MISSING_PARAMETER}` |
| Efficiency above 1, a value ≤ 0, or `k_factor` ≤ 0 | Stored; day has no total | `not_derivable{reason=INVALID_PARAMETER}` + WARN |
| One of a day's PDU runs can't be calculated | Day has no total | `not_derivable` |
| Day has a total, then a correction makes it not calculable | Total removed | `day_total_removed` |
| Latest reading is ELM (not calculable), BFM reading same day | BFM total kept | `channel_fallback` |
| First ELM reading on a scheme that read BFM that day | Day keeps the BFM total | — |
| ELM or PDU photo with no value | 400 `IMAGE_NOT_SUPPORTED_FOR_CHANNEL`; WhatsApp gets it localised | — |
| Photo **and** value, any channel | Value stored; photo kept; no OCR | — |
| Unit the channel doesn't accept | 400 `READING_UNIT_NOT_SUPPORTED` | — |
| PDU run over 1,440 min, or the day's PDU minutes would be | 400 `ABNORMAL_READING`; nothing stored; no anomaly | — |
| A correction event arrives after a newer one | Dropped | `meter_reading.stale_event` |
| Event with an unparseable `sourceUpdatedAt` | Straight to `<topic>.DLT` | `meter_reading.source_updated_at.unparseable` |
| Event from an older telemetry (no `sourceReadingId`) | Inserted as a new row, as before | — |
| Correction moves a reading to another scheme or day | Both days recalculated | INFO log |
| IOT or MAN reading | Day's row left as it was | `calculator.missing` |
| Formula or pumps configured after the readings | Old days stay without a total | Until §7 is run |

---

## 12. Duplicated types

No shared module exists, so several types have a copy in each service. Change them together.

| Type / rule | Copies | Note |
| --- | --- | --- |
| `ReadingChannel` | telemetry, analytics | Same codes. Analytics adds `kind()`; telemetry adds `standardUnit()` and `supportsImageReading()` |
| `CalculationParameters` | telemetry (producer), analytics (consumer) | Same JSON; each keeps only what it needs |
| `MeterReadingEvent` new fields | telemetry, analytics | |
| ELM formula codes `F1`–`F3` | tenant `ElmFormula`, telemetry `CalculationParametersSnapshotter.ELM_FORMULAS`, analytics `ElmVolumeFormula.code()` | A new formula needs all three |
| The day-channel rule | analytics `WaterQuantityRecalculationService`, user-service `PersonSchemeRepository` | Latest by `reading_at`, then `id`, NULL as BFM |
| The BFM day rule | analytics Java, `recompute_water_quantity.sql` | Pinned by the parity test |

---

## 13. Rollout and rollback

**Deploy order**

1. Tenant migrations V56 and V57.
2. tenant-service.
3. analytics-service (runs its V56).
4. user-service and scheme-service. The SO/SDO filter must be live **no later than** telemetry, so the
   staff screens never see an ELM or PDU row.
5. telemetry-service. Set `TELEMETRY_INTERNAL_AUTH_TOKEN_HASH` if the backfill will be used.

Analytics goes before telemetry because the new event fields are optional to it, while a new
telemetry's events against an old analytics would be inserted as new rows on every correction.

**Enabling a tenant**

1. Set `ELM_WATER_QUANTITY_FORMULA` (ELM only).
2. Enter the schemes' pump ratings and, for ELM, `k_factor`, by SQL, in the units of §3.2.
3. Run the "before the first run" check (§7), then republish the dates that already have readings.
4. Watch `water_quantity.not_derivable` and `water_quantity.motor_power_unit.unknown`.

**Rollback.** Every migration is additive: nullable columns, a default and comments. Events without
`sourceReadingId` keep inserting as before, so an analytics-service ahead of telemetry is safe. A
telemetry rolled back after this release sends events without identity again, and corrections then
add fact rows, as they did before.

**Channel columns (V58–V60).** Released after this branch, in three steps, each live everywhere before
the next starts:

1. V58 and V59.
2. telemetry-, user- and scheme-service reading and writing only `channel_id`, once V59 has finished:
   `SELECT conrelid::regclass, convalidated FROM pg_constraint WHERE conname = 'fk_flow_channel'` is
   true for every tenant.
3. V60, once no pod from before step 2 serves.

Unlike the migrations above, V60 is not additive: it drops columns that services from before step 2
read, so those services can't be redeployed once it has run.

---

## 14. Effects on existing numbers (accepted)

- **One fact row per submission.** Counts that `COUNT(*)` `fact_meter_reading_table` drop where
  corrections exist, because a corrected submission no longer counts twice. New data only: no script
  dedupes old rows.
- **Some corrected BFM days change.** A correction with no earlier reading no longer gets the whole
  meter total as one day's water.
- **PDU adds rows to submission counts**, one per run. A PDU run has no OCR value, so it is never
  counted as compliant or anomalous, like an API-asserted reading.
- **The day after a correction** is credited to the operator of that day's own latest reading, and its
  `updated_at` moves only if something changed.
- **SO/SDO reporting rate and last submission time count BFM only.** An operator who submits only ELM
  or PDU looks as if they never submitted on those screens. Nudges and escalations count every channel.
- **`lastWaterSupplied` on the pump-operator list** is always litres, and no longer always NULL.
- **The day a scheme switches meters** keeps the old meter's total, but the SO/SDO pump-operator list
  picks a day's channel from its latest reading and skips it.
- **A WhatsApp manual reading on a meter-replacement day** now publishes, and gets a total measured
  against the old meter's last reading: analytics has no meter-replacement reset.

---

## 15. Testing

Per `CLAUDE.md`: Mockito for unit tests, Testcontainers for anything touching the database. The
branch adds 39 test files (fixtures and test schemas included), changes 54 and deletes 1, across five
services. The ones worth knowing about:

| Test | Covers |
| --- | --- |
| `WaterQuantityRecalculationServiceTest` | Day channel, both kinds, the starting-point and fallback rules, crediting, change-only writes, removal, metrics |
| `MeterReadingIngestionIntegrationTest` | End to end from telemetry's event JSON: an ELM day, a PDU day, a meter switch, a dual-meter day, a non-calculable day, readings corrected onto another day or scheme |
| `WaterQuantityBackfillParityIntegrationTest` | SQL recompute = live path, including mixed-channel days and out-of-order arrival |
| `FactIngestionRepositoryIntegrationTest` | Upsert: new, newer, older, same version, legacy; the scheme lock |
| `PumpParameterAggregatorTest`, `*FormulaTest`, `Elm…` / `PduWaterQuantityCalculatorTest` | The worked examples, unit conversion, averaging, range checks |
| `ImageReadingCaptureTest`, `OcrProviderRegistryTest`, `OcrProviderResolverTest` | OCR by channel; ELM never reaches the BFM endpoint |
| `SubmittedValueCaptureTest`, `ReadingUnitTest` | Units, spellings, exact conversion, the run limit |
| `PduDayLimitIntegrationTest` | Two concurrent runs: the second waits, then is refused |
| `ReadingRepublisherTest`, `CalculationParametersSnapshotterTest` | Event from the stored row, quarantine withholding, the snapshot |
| `ReadingBackfillServiceTest`, `…ControllerTest`, `InternalAuthFilterTest` | The backfill, its stop-on-no-ack, its auth |
| `PersonSchemeRepositoryIntegrationTest`, `SchemeDbRepositoryFixReadingsIntegrationTest`, `TelemetryTenantRepositoryBfmCorrectionIntegrationTest` | BFM-only staff screens, the `LAG` fix |
| `ElmFormulaConfigDTOTest`, `TenantManagementServiceImplTest` | Formula code in any case; unknown, `null` and `{}` refused |

```bash
export JAVA_HOME=<local JDK 21>
(cd backend/analytics-service && mvn test -Dtest='*WaterQuantity*Test,*Formula*Test,PumpParameterAggregatorTest,FactServiceImplTest,FactIngestionRepository*Test,FactMeterReadingRepositoryIntegrationTest,FactWaterQuantityRepositoryIntegrationTest,MeterReadingIngestionIntegrationTest,KafkaConfigTest')
(cd backend/telemetry-service && mvn test -Dtest='ReadingUnitTest,*Capture*Test,PduDayLimit*Test,OcrProvider*Test,OcrReadingsRetryService*Test,BfmReadingService*Test,MeterImageWorkflowService*Test,MeterReadingConversationService*Test,TelemetrySchemeReadingServiceTest,ReadingRepublisherTest,ReadingBackfill*Test,InternalAuth*Test,CalculationParametersSnapshotterTest,SchemeCalculationInputRepositoryIntegrationTest,TelemetryTenantRepository*Test,WebhookRouteCoverageTest,RouteParityTest')
(cd backend/tenant-service && mvn test -Dtest='ElmFormulaConfigDTOTest,TenantConfigKeyEnumTest,TenantManagementServiceImplTest')
(cd backend/user-service && mvn test -Dtest='PersonSchemeRepositoryIntegrationTest')
(cd backend/scheme-service && mvn test -Dtest='SchemeDbRepositoryFixReadingsIntegrationTest')
```

---

## 16. Known limitations

- **ELM photos are read only where configured.** `ocr.elm.default-provider` is blank by default, so
  an ELM photo from a tenant with no `ocr_elm_provider` is rejected until it is set.
- **ELM and PDU over WhatsApp (phase 6) are not built.** There is no PDU minutes flow, and a WhatsApp
  manual value from a PDU operator overwrites their latest row today instead of adding a run.
- **Pump ratings and `k_factor` are entered by SQL.** Nothing validates their units at entry.
- **Saving configuration doesn't recalculate.** Readings stored before it stay without a total until
  someone runs §7.
- **A retried PDU `POST` is counted twice.** The correlation id is generated per call, so there is no
  way to recognise a retry without a client `Idempotency-Key` header, which would change the API. The
  1,440-minute day limit bounds the damage.
- **Operator attendance isn't moved with a reading.** A correction that moves a reading to another
  day or scheme recalculates the day it left but keeps that day's attendance row.
- **`BfmReadingService` now handles every channel**, despite its name. Renaming it is out of scope.
- **A rejected WhatsApp ELM or PDU photo is still uploaded to storage** before the rejection.
