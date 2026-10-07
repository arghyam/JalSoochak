# Plan: ELM and PDU reading channels

**Status:** phases 1–5 and the four lead-review fixes are implemented on `elm-pdu-mock-impl`
(section 5 lists the commits). Phase 6 is not started. What was built, and how, is described in
[docs/elm-pdu-reading-channels.md](docs/elm-pdu-reading-channels.md); this file keeps the decisions
and the questions answered on the way. Updated 2026-10-01 to match the code at `5feecc54`.

**History.** Written 2026-09-28 and checked against `dev` at `b99a8a7b`. Revised 2026-09-29, checked
against `13bd1fa7`. The revision covers five things: PDU is manual-only, the Section Officer screens
show BFM only, there is no per-submission litres column, Q11 is answered, and the phases are
renumbered. A second revision the same day, after a line-by-line check against `13bd1fa7`, covers:

- the OCR path for tenants with no override
- who a recalculated day is credited to
- which staff screens are filtered (Q12)
- WhatsApp photos on ELM and PDU (Q13)
- days with no calculator (Q14)
- corrections on every channel (Q15)
- smaller review findings

Q16 and Q17 were answered on 2026-09-30, before phase 5. A lead review after phase 5 added Q18–Q23,
also answered on 2026-09-30, and changed four decisions: the channel fallback (Q19), the PDU day
limit (Q20), PDU with several pumps (Q21) and a backfill endpoint (Q18).

Every question in section 11 is answered.

## 1. Goal

Some schemes have no bulk flow meter (BFM). Their water supply has to be worked out from other
readings:

- **ELM (electric meter):** the pump's electricity meter, a kWh total that only goes up.
- **PDU (pump duration):** how many minutes the pump ran, reported one run at a time.

This plan adds both channels from the moment a reading is submitted through to the analytics tables
and dashboards. The dashboards count every channel. BFM results stay the same except where section
10 says otherwise. For now, the screens that Section Officers and Sub-Divisional Officers log into
(the SO/SDO screens) show BFM readings only (2.9).

**Scope:** submissions through the state-IT API only (`POST /api/v1/telemetry/readings` and
`/readings/formats/{format}`, which both go through `MeterImageWorkflowService.processCanonicalReading`).
ELM and PDU over WhatsApp come later (phase 6). WhatsApp photos go through the same code, so from
phase 4 they follow the same OCR rules (2.7).

## 2. Decisions

All confirmed by the user. Section 11 records the answers to the questions raised while planning.

### 2.1 Channel behaviour

The two kinds are new, named in `ReadingKind`:

| Channel | What a reading is | Kind | Standard unit | Units accepted (UCUM) | Photo (OCR) | Water for the day |
| --- | --- | --- | --- | --- | --- | --- |
| BFM | Flow meter total | `METER_INDEX` | m³ | `m3`, `kL`, `L` (also `m³`, `litre`, `liter`) | Yes | latest reading on the day − latest reading before the day (as today) |
| ELM | Electricity meter total | `METER_INDEX` | kWh | `kW.h` (also `kWh`) | Yes, once an ELM model exists | the day's kWh, worked out the same way as BFM, then an ELM formula |
| PDU | Minutes the pump ran in one run | `PERIOD_AMOUNT` | min | `min`, `h` (also `hr`) | Never: always typed in | each submission's litres, added up (2.5) |

- The alternative spellings in brackets are accepted but stored as the UCUM code they stand for.
- Readings are stored in the channel's standard unit. Many queries do arithmetic on
  `confirmed_reading` directly, so a column holding mixed units would give them wrong answers.
- The unit the caller sent is stored in a new `flow_reading_table.submitted_unit` column. It is not
  called `reading_unit`, because that name suggests it is the unit of `confirmed_reading`. Only
  telemetry stores this column.
- The API gets an optional `reading_unit` field. When it is missing, the channel's standard unit is
  used. A unit that doesn't belong to the channel gets a 400.
- Nothing forces a scheme to use only one channel. As of 2026-09-28 there are no non-BFM rows in
  prod.

### 2.2 Formulas

All results are in litres. The three ELM formulas are multiplied by `scheme_master_table.k_factor`;
when `k_factor` is NULL it counts as 1. The PDU formula doesn't use `k_factor` (user's decision,
2026-09-30).

| Code | Formula | Pump table columns used |
| --- | --- | --- |
| ELM F1 | `kWh × LPM × 60 / units_consumed_per_hour` | `pump_discharge_capacity`, `units_consumed_per_hour` |
| ELM F2 | `kWh × LPM × 60 / motor_kW` | `pump_discharge_capacity`, `motor_power` + `motor_power_unit` |
| ELM F3 | `V(m³) = 366.97 × E × Np × Nm / H`, then × 1000 | `pump_efficiency` (Np), `motor_efficiency` (Nm), `pump_head` (H, m) |
| PDU | `minutes × LPM` | `pump_discharge_capacity` |

- **Which ELM formula** is set per tenant with the new config key `ELM_WATER_QUANTITY_FORMULA`
  (`F1` / `F2` / `F3`). There is no default: if a tenant hasn't set it, ELM water quantity is skipped
  and a metric is recorded.
- **F2 divides energy by motor power, so both must use the same units:** kWh divided by kW. The "HP"
  in the spec was a typo. `motor_power_unit` is free text (V53), so each pump's motor power is
  converted to kW first. The unit is matched case-insensitively after trimming:
  - `kW`: used as is
  - `HP`: multiplied by 0.7457 to get kW
  - `bHP`: multiplied by 0.9863 to get HP, then by 0.7457 to get kW (user's decision, 2026-09-30).
    0.9863 is the ratio of metric horsepower (PS, 735.5 W) to HP (745.7 W), so this treats `bHP`
    values as metric horsepower.
  - anything else: that pump's `motor_power` is treated as missing and a metric is recorded
- **F3's constant 366.97** is the spec's rounded value of 3.6×10⁶ / (1000 × 9.81).
- **Efficiencies are fractions.** A value above 1 is invalid: the day is skipped and a metric
  recorded. Zero or negative values for any parameter the formula uses are handled the same way, so
  0 LPM is invalid rather than 0 L. Each pump's own value is checked, not the average.
- **`k_factor` of 0 or less** is invalid too.
- **Rounding:** use `BigDecimal` arithmetic (`MathContext.DECIMAL64`) and round once, `HALF_UP`, to
  whole litres, the same way `WaterVolumeUnits` does.

**Pump parameters:**

- Only rows of `asset_pump_registry_table` with `status = 1 AND deleted_at IS NULL` are used
  (1 = active, 0 = inactive).
- `status` needs `DEFAULT 1`.
- When a scheme has several active pumps, they run at the same time (Q21).
  - ELM: each parameter the formula needs is averaged over the pumps that have a value for it. HP
    and bHP are converted to kW for each pump before averaging.
  - PDU: a run's minutes are the time all the active pumps ran together, so its litres are the
    minutes times the sum of their discharge rates. A pump with no rate counts at the others'
    average (the average rate times the number of pumps).
- If no active pump has a value for a parameter, that parameter is missing and the day can't be
  calculated.
- `pump_discharge_capacity` is always in L/min (Q22). V57 comments each rating column with its unit.
- Pump parameters and `k_factor` are entered by SQL for now. No API reads or writes them.

**Worked examples.** Use these as expected values in the tests. All use k = 1 unless stated.

| Case | Inputs | Litres |
| --- | --- | --- |
| F1 | 10 kWh, 500 LPM, 5 kWh/h | 60,000 |
| F2 | 10 kWh, 500 LPM, 7.5 HP (= 5.59275 kW) | 53,641 |
| F2 | 10 kWh, 500 LPM, 7.5 bHP (= 7.39725 HP = 5.516129325 kW) | 54,386 |
| F3 | 10 kWh, Np 0.70, Nm 0.85, H 40 m | 54,587 |
| PDU | 90 min, 500 LPM (k 0.9 is ignored) | 45,000 |

### 2.3 Where the calculation happens and what it reads

- **The calculation runs in analytics**, using the existing `WaterQuantityCalculator` and its
  registry. Analytics reads only `analytics_schema`, so it cannot look up pump data itself.
- **Telemetry sends the raw inputs.** `MeterReadingEvent` gets a `calculationParameters` snapshot
  containing one entry per active pump with its raw values and unit and, for ELM only, the ELM
  formula code and `k_factor`. Analytics does all the filtering, unit conversion, validation and averaging, so every
  calculation rule lives in one service.
- **Analytics stores the snapshot** as JSONB on `fact_meter_reading_table` (NULL for BFM). It needs
  it later, because it recalculates days outside the event that triggered them (next-day
  recalculation, PDU day totals).
- **Corrections take a new snapshot** when the reading is published again, so a corrected reading
  is calculated with the pump data current at the time of the correction.

### 2.4 One fact row per submission

- Each submission gets exactly one row in `fact_meter_reading_table`, identified by
  `(tenant_id, source_reading_id = flow_reading_table.id)`.
- An event updates that row only if its `sourceUpdatedAt` is newer than the one stored.
  `correlation_id` can't be used as the key, because the Glific flows reuse it across rows (V48).
- Telemetry writes `flow_reading_table.updated_at` with `clock_timestamp()`, not `NOW()`. `NOW()`
  is the time the transaction started. Two concurrent corrections to one row could then store
  versions in the opposite order to their commits, and analytics would keep the losing value.
- This applies to new data only. No scripts will dedupe or backfill old rows.
- Events that don't carry `sourceReadingId` (sent by a telemetry version from before phase 4) are
  inserted as new rows, as today.

### 2.5 Recalculation

- **After a reading is written for day D**, analytics recalculates D. Then, for each `METER_INDEX`
  channel (BFM and ELM), it recalculates the next day that has a reading on that channel, whatever
  the written reading's channel and D's own result were. On the written reading's channel, that
  day may now start from D. On any other channel, D may now lie between that day and its starting
  point (next bullet). This goes one step only, because no later day is affected.
- **A starting point across another channel's reading isn't used (Q16).** A `METER_INDEX` day's
  starting point is the latest reading on its channel before the day. If any reading on another
  channel is dated strictly between the two, the day has no starting point, as for a scheme's
  first reading (next bullet). The dates in between were counted on the other channel, so
  measuring across them would count their water twice. Readings on the two dates themselves don't
  count.
- **A `METER_INDEX` day with no starting point is worked out from another channel (Q17).** The
  day's other channels are tried in the order of their latest reading that day, latest first. The
  first one that gives the day a total of its own decides: a `METER_INDEX` channel with a starting
  point, or a `PERIOD_AMOUNT` channel. A channel with no calculator, or an unconfirmed reading,
  gives none. A total that can be calculated wins (Q19); failing that, the first that can't stands,
  as the day's own channel's would. Only when no channel gives a total is the amount 0. So the day a scheme
  starts reading a new meter keeps what the old one measured, instead of counting as a day with no
  supply (`water_quantity > 0` defines a supply day). No water is counted twice: the old meter
  stops at its reading and the new one starts from its own. The follow-up above already covers
  every reading this depends on, so the result still doesn't depend on arrival order.
- **Who the day is credited to.** The day's row takes `user_id` and `submission_status` from the
  reading the day was worked out from in `fact_meter_reading_table`, not from the event: the day's
  latest reading, or under Q17 another channel's latest reading that day. The next-day
  recalculation has no event for its day. The SO/SDO pump-operator list matches operators on
  `fact_water_quantity_table.user_id`, so taking them from the event would credit the next day to
  the wrong operator.
- **The next-day recalculation writes only when something changes**: the total, the user, the
  status, or a reason to clear. The SO/SDO pump-operator list shows the row's `updated_at` as the
  operator's last submission time, so a day nobody touched must keep it. The day the reading was
  written for is always written, as today.
- **A day with readings on more than one channel** is worked out from the channel of that day's
  latest reading, with legacy NULL counted as BFM, except as in Q17 above. This makes the result
  the same whatever order the events arrive in.
- **A total that can be calculated is never replaced by one that can't (Q19).** When the day's own
  channel can't be calculated but another channel's latest reading that day gives a total that can,
  that total is used and credited to that reading, and `water_quantity.channel_fallback` is counted.
- **A correction can move a reading to another scheme or day.** Analytics then recalculates the day
  it left as well: written only if it changed, or its reading-derived total removed when no reading
  is left, followed by the next `METER_INDEX` day on each channel.
- **PDU day total:** each of the day's PDU submissions is turned into litres from its own minutes
  and its own snapshot, and the results are added up. This happens in memory whenever the day is
  recalculated. Litres are only stored for the whole day, not per submission. If any submission
  can't be calculated, neither can the day (next bullet), so the total is never silently too low.
- **A day that can't be calculated has no total.** This only happens for ELM and PDU: BFM always
  can be calculated.
  - A metric is recorded.
  - If the day already has a total worked out from readings, that row of
    `fact_water_quantity_table` is removed. Its `outage_reason` and `non_submission_reason` are
    both NULL, because the reason events always set one of them.
  - A row that holds an outage, non-submission or meter-change reason is left alone, as today.
  - The result is the same whatever order the readings arrive in: the day ends up with no total
    either way.
- **A day whose channel has no calculator** keeps its existing row untouched, and
  `water_quantity.calculator.missing` is counted, as today (Q14). This covers IOT and MAN, and
  ELM and PDU until phase 5. It is not the same as a day that can't be calculated.
- **A total too large to store** (past `BIGINT` litres) is handled as today, on every channel: no
  total is written, the existing row is left alone, and `water_quantity.unstorable` is counted.
- **Concurrency:** ingestion takes a transaction-scoped advisory lock for the scheme before any
  recalculation. Telemetry publishes events with no key and with `@Async`, so events for the same
  scheme can arrive in any order. With more than one analytics instance, they can also be
  processed at the same time.
  - The lock is `pg_advisory_xact_lock(namespace, key)`. The namespace is a constant, as in
    tenant-service's `TenantProviderSecretRepository`, and the key is derived from
    `(tenant_id, scheme_id)`.
  - A bare `(tenant_id, scheme_id)` pair would share key space with tenant-service's two-int locks
    in the shared database.
  - Two schemes whose keys collide only make one of them wait.
  - Both writers of `fact_water_quantity_table` take it: the reading path and `ingestWaterQuantity`
    (reason events).
  - A reading that moved schemes locks both, in ascending key order, then reads where it is stored
    again. A reading moved meanwhile to a scheme not locked throws `ConcurrencyFailureException`, and
    Kafka retries.

### 2.6 Corrections

**Which rows each path can change (Q15).** All endpoints are under `/api/v1/telemetry`.

| Path | Endpoint | Channels |
| --- | --- | --- |
| WhatsApp manual reading (`MeterReadingConversationService.manualReadingMessage`) | `POST /manual-reading` | any |
| WhatsApp update previous reading (`MeterReadingConversationService.updatePreviousReadingMessage`) | `POST /update-previous-reading` | any |
| State-IT correction (`BfmReadingService.updateConfirmedReading`) | `PUT /readings` | any |
| State-IT reset (`BfmReadingService.resetLatestConfirmedReadingByPhone`) | `POST /readings/reset-latest` | any |
| SO/SDO Fix-readings save (`TelemetrySchemeReadingService.updateYesterdayFinalReadingBySchemeId`) | `PATCH /schemes/{schemeId}/yesterday-final-reading` | BFM only (2.9, Q8) |

For PDU, a correction changes the one run the path targets, not the day's total.

**A correction follows the same rules as a submission:**

- The value is in the standard unit of the target row's channel. `PUT /readings` also accepts the
  optional `reading_unit`, checked against the target row's channel the same way as on
  `POST /readings`. The other paths have no unit field.
- `submitted_unit` is set to the unit the value came in. On a path with no unit field, that is the
  standard unit.
- The PDU limits apply (4.3).
- The checks that only make sense for BFM stay BFM-only:
  - the supply-plausibility guard, as today
  - the WhatsApp manual reading's maximum check (4.3)

**Publishing:**

- Two paths calculate the difference themselves and publish `WATER_QUANTITY_RECORDED`. Both switch
  to publishing `METER_READING_RECORDED` again for the corrected row:
  - the PATCH `yesterday-final-reading`
  - WhatsApp `updatePreviousReadingMessage`
- Analytics' next-day recalculation replaces the "day after" events these paths send today.
- This also fixes a bug: today, when there is no earlier reading, these paths publish the whole
  meter total as one day's water (`orElse(BigDecimal.ZERO)`). BFM numbers change in those cases;
  the user accepted that.
- `PUT /readings` already publishes again through `publishConfirmedReadingUpdate`. The reset also
  publishes again, but builds its own event. All paths will share one helper (`ReadingRepublisher`,
  4.3).

### 2.7 OCR

- **Only some channels read photos.** `ReadingChannel.supportsImageReading()` is true for BFM and
  ELM and false for every other channel.
- **PDU never uses OCR.** Its readings are always typed in.
  - A PDU submission with only a photo gets a 400 `IMAGE_NOT_SUPPORTED_FOR_CHANNEL`. No OCR config
    is read.
  - A PDU submission with a photo and a typed value is accepted. The photo is kept and OCR doesn't
    run, the same as for BFM today when a value is sent.
- **The OCR extractor is always chosen by channel**, including for tenants with no override. Each
  `MeterReadingExtractor` declares which channel it handles.
  - Today a tenant with no override skips the registry and gets FlowVision directly (3).
  - That shortcut goes. Otherwise an ELM photo would be read by the BFM model.
- The per-tenant OCR override (`ocr_provider`, `ocr_url`, `ocr_api_key`, `ocr_auth_header`) works
  for every channel that reads photos. One set of keys points at one model endpoint, which reads one
  kind of meter, so each channel has its own set:
  - BFM keeps the existing `ocr_*` keys, so current tenant config keeps working.
  - ELM uses the same four keys with `elm` added: `ocr_elm_provider`, `ocr_elm_url`,
    `ocr_elm_api_key`, `ocr_elm_auth_header`.
  - The global `ocr.*` settings (`ocr.url`, `ocr.api-key`, `ocr.auth-header`,
    `ocr.default-provider`) belong to BFM.
  - ELM settings never fall back to them, so an ELM photo is never sent to the BFM endpoint or
    with the BFM key.
  - The keys are raw rows in `tenant_config_master_table`, as today. tenant-service needs no change.
- **An ELM photo is rejected until an ELM extractor exists** (Q13). It gets the same 400, and the
  `ocr_elm_*` keys have no effect until then.
- **WhatsApp photos follow the same rules from phase 4.** `processImage` goes through the same
  `createReading` and takes the channel from the operator's stored preference.
  - An operator whose preference is ELM or PDU has the photo rejected.
  - The reply is localised like the other WhatsApp replies (4.3).
  - No such operator exists in prod.
  - PDU over WhatsApp works once phase 6 adds its flow for typing in minutes.

### 2.8 Manual readings (typed-in values)

- Don't add a `ManualReadingExtractor`. `MeterReadingExtractor` is designed for images.
- Instead add a `ReadingCapture` step with two implementations:
  - `ImageReadingCapture`
  - `SubmittedValueCapture`, whose source is `MANUAL` or `EXTERNALLY_ASSERTED`
- Both return a `CapturedReading` to the one shared submission pipeline.
- PDU always goes through `SubmittedValueCapture`.

### 2.9 SO/SDO screens

- **BFM only, for now.** Section Officers and Sub-Divisional Officers log into the same staff
  screens. Those screens show and act on BFM readings only, as if ELM and PDU readings didn't exist.
  Legacy rows with a NULL channel count as BFM. This covers the queries that belong to the staff
  screens:
  - every query in user-service `PersonSchemeRepository` that reads `flow_reading_table`:
    - This includes readings, water supplied and reading lists, and also reporting rate and last
      submission time.
    - Nothing else calls this repository. It backs the scheme list, the scheme view, the
      pump-operator list and the pump-operator readings. These are all under
      `/api/v1/pumpoperator`: `/person/{personId}/…`, `/schemes/{schemeId}/…` and
      `/pump-operators/{operatorId}/readings`.
  - the analytics branch of `listPumpOperatorsByPerson` and `countPumpOperatorsByPerson`, which
    reads `fact_water_quantity_table` day totals:
    - It leaves out days whose latest reading in `analytics_schema.fact_meter_reading_table` is not
      BFM.
    - That is the same rule analytics uses to pick a day's channel (2.5). No column is added for
      this.
  - scheme-service's fix-readings list
  - telemetry's PATCH `yesterday-final-reading` (the Fix-readings save):
    - It corrects the latest BFM reading.
    - A scheme with no BFM reading gets the existing 404.
- **Dashboard APIs don't change, even where a staff screen calls them** (Q12). They keep counting
  every channel:
  - `GET /api/v1/pumpoperator/pump-operators/{id}` (`PublicPumpOperatorRepository`). The
    pump-operator view page shares it with the village dashboard.
  - every `/api/v1/analytics/…` endpoint the staff screens call: the overview tiles, operator
    attendance, anomalies and escalations.

  So on the pump-operator view page, the header counts every channel, while the readings list
  below it counts BFM only. The header shows last submission, reporting rate and missed days. This
  was accepted.
- The filter is applied before `LAG`, so a non-BFM reading is never used as a BFM reading's
  previous value.
- A scheme or operator with only ELM or PDU readings looks like one with no readings on the
  filtered queries: no reading, no reporting rate and no last submission time.
- **Units stay as they are.** `water_supplied` stays in m³ on the three screens that use the `LAG`
  SQL. The exception is the pump-operator list's `lastWaterSupplied`. Its analytics branch is in
  litres, but its `fl` fallback is in m³, in the same field. The fallback is converted to litres
  (`ROUND((confirmed_reading − prev_confirmed) × 1000)`, like `WaterVolumeUnits`), so that field is
  always litres.
- The responses get no new fields.
- The analytics dashboards are not filtered.

## 3. How the code worked before this work

Checked on `b99a8a7b`, and all of it again on `13bd1fa7`. This is the starting point the design
was written against; the line numbers refer to that code, and much of it has since changed.

**Analytics:**

- `service/water/WaterQuantityCalculator.calculate(WaterQuantityContext)` returns `long` litres.
  `WaterQuantityContext` carries `currentReading` and `previousReading`. `BfmWaterQuantityCalculator`
  works out the difference itself.
- `WaterQuantityCalculatorRegistry.resolve` returns empty for a channel that was named but has no
  calculator. `FactServiceImpl.updateWaterQuantityFromReading` then skips the day and increments
  `water_quantity.calculator.missing`. Until phase 5 lands, this is what happens to ELM and PDU
  readings.
- `FactServiceImpl.ingestMeterReading` always inserts a new row.
  - The day's current reading comes from
    `FactMeterReadingRepository.findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc`.
  - The starting point comes from `findLatestBefore`.
  - Neither query filters by channel.
- `db/scripts/recompute_water_quantity.sql` is the SQL version of the same rule.
  `WaterQuantityBackfillParityIntegrationTest` checks that it and the Java code give identical
  results.
- `fact_meter_reading_table` has `correlation_id` (V48, not unique) and a nullable `channel INT`.
  Its indexes are single-column ones plus `(tenant_id, scheme_id)` (V37). None covers "latest
  reading on a date".
- `fact_water_quantity_table` has no channel column. It is kept at one row per scheme and day by a
  find-and-update, with no unique constraint behind it (V43). Two paths write it:
  - the reading path (`updateWaterQuantityFromReading`):
    - It stores a derived total and sets `outage_reason` and `non_submission_reason` to NULL.
    - It takes `user_id` and `submission_status` from the event, and always saves, so `updated_at`
      moves on every reading.
  - `ingestWaterQuantity` (`WATER_QUANTITY_RECORDED`)
- A total too large for `BIGINT` throws `WaterVolumeOutOfRangeException`, which only knows m³. The
  day is left alone and `water_quantity.unstorable` is counted.
- `water_quantity.calculator.missing` is tagged with `tenantId` and `schemeId`. No dashboard or
  alert in the repo uses the counter.
- `db/scripts/recompute_water_quantity.sql` is also read by `scripts/water_quantity_units_fix.py`.
  That script classifies a day by whether `current_reading` is NULL.
- Analytics runs Flyway inside Spring Boot. V43 records that `CREATE INDEX CONCURRENTLY` hangs there.
- The newest analytics migration on dev is `V55` (V50–V55 came with the KPI pre-aggregation merge).

**Telemetry:**

- **Declared channel:** `CanonicalReadingRequest.channel` already exists. An unsupported value gets
  a 400 `CHANNEL_NOT_SUPPORTED`. `MeterImageWorkflowService` maps it to
  `CreateReadingRequest.declaredChannel`.
- **Order of steps in `BfmReadingService.createReading`:**
  - OCR runs first, around line 125, and only when no value was sent. It calls `extractReading`
    (line 768).
  - The channel is worked out only after that, at line 435.
  - After the insert, the channel is written with a separate, non-transactional `UPDATE`
    (`updateFlowReadingChannel`, at lines 601 and 659).
- **WhatsApp photos go through the same `createReading`.** `MeterImageWorkflowService.processImage`
  calls it, and the channel comes from `user_channel_preference`, which `/selected/channel` writes
  (`ConversationSelectionService`, line 286).
- **OCR for a tenant with no override bypasses the registry.**
  - `OcrProviderResolver.resolve` returns NULL for such a tenant.
  - `BfmReadingService.extractReading` and `OcrReadingsRetryService.resolveExtractor` (line 100)
    then call the `@Primary` `FlowVisionOcrExtractor` directly.
  - With an override, fields the tenant didn't set fall back to the global `ocr.*` values (line 77).
  - Only these two classes inject a single `MeterReadingExtractor`.
- **Lookups that ignore the channel.** `findLatestConfirmedReadingSnapshot` doesn't filter by
  channel. It feeds:
  - the duplicate-image guard (line 365)
  - the "last confirmed reading" in the response (line 648)
  - the WhatsApp manual reading's baseline (lines 1112–1114)

  The rollover history (`findRecentDailyConfirmedReadings`) doesn't filter by channel either.
- **The WhatsApp manual reading has its own maximum check.** It rejects a value above the latest
  reading plus the water norm plus the oversupply threshold (around line 1225). That check only
  makes sense for a BFM meter total.
- **WhatsApp replies are localised** in `ConversationLocalizationService.localizeMessage`, which
  maps known English texts to Hindi.
- **`publishConfirmedReadingUpdate`** (line 1105) publishes an existing row again, keeping its
  channel. `PUT /readings` uses it.
  - The reset (`resetLatestConfirmedReadingByPhone`, line 1160) builds its own event (line 1194)
    with `operator.id()` as the user. That is the row's `created_by`, because the reset picks the
    operator's own latest row.
  - Rows are found by `correlation_id`, which isn't unique, or through `TelemetryCompletedFlowReading`,
    which doesn't carry every field the event needs.
- **`MeterReadingConversationService.manualReadingMessage`** (line 1051) never publishes
  `METER_READING_RECORDED` in any of its three branches: the pending row (around line 1314), today's
  row (around line 1345) and a new insert (around line 1376). The event was added in `830f8ea3`, but
  only for `createReading`.
- **Paths that calculate the difference themselves:** `TelemetrySchemeReadingService` (lines 144–167)
  and `MeterReadingConversationService` (lines 1758–1781). Nothing else calls
  `publishWaterQuantityRecorded`.
- **`WATER_QUANTITY_RECORDED` also carries reasons.** `publishOutageOrNonSubmissionReason` and
  `publishMeterChangeReason` send it with water 0, `NOT_SUBMITTED` and exactly one reason set.
- **PATCH target:** `updateYesterdayFinalReadingBySchemeId` picks its row with
  `TelemetryTenantRepository.findLatestCompletedFlowReadingOnDate` or
  `findLatestCompletedFlowReadingForScheme`. Neither filters by channel, and nothing else calls them.
  - The PATCH is an API-key route (`ReadingIngestController`, line 88). It is also the save action
    of the SO/SDO Fix-readings screen.
  - It works out the "day before" and "day after" values with `findPreviousFlowReadingForScheme`
    and `findEarliestCompletedFlowReadingAfterDateForScheme`. Neither filters by channel.
- **The other correction paths don't filter by channel either.**
  - WhatsApp `updatePreviousReadingMessage` targets the operator's latest row before today
    (`findLatestCompletedFlowReadingBeforeDate`).
  - `PUT /readings` targets the row with that correlation id, or the operator's latest row.
- **Kafka publishing:** every `TelemetryEventPublisher` method is `@Async("kafkaPublisherExecutor")`
  and sends without a key.

**Tenant schema:**

- `flow_reading_table` has `channel VARCHAR(50)`, which holds the enum name, and `updated_at`.
  - Every telemetry write except `applyIngestionTracking` sets `updated_at = NOW()`, which is the
    time the transaction started.
  - Two call sites besides `createReading` insert rows: the WhatsApp manual reading
    (`MeterReadingConversationService`, line 1376) and the `/location` placeholder (line 1584).
- `asset_pump_registry_table` (renamed in V52, columns renamed or added in V53) has
  `status INTEGER NOT NULL` with no default.
- `scheme_master_table.k_factor FLOAT` was added in V54.
- The newest tenant migration is `V55`.
- `scheme_master_table.channel` stores the position of the option in a list, not a channel code
  (`ConversationSelectionService`, line 281).

**Display:**

- **user-service `PersonSchemeRepository`:**
  - `listSchemesByPerson`, `listSchemeReadings`, `listPumpOperatorReadings` and the fallback in
    `listPumpOperatorsByPerson` work out `water_supplied` as `confirmed_reading − LAG(...)` over
    `flow_reading_table`. That gives m³ for BFM.
  - `getSchemeDetails` and `listPumpOperatorsByPerson` also work out reporting rate and last
    submission time from `flow_reading_table`.
  - The analytics branch of `listPumpOperatorsByPerson` returns `fact_water_quantity_table` litres,
    and `tableExists` guards it (line 486).
    - It takes the operator's latest day with `COALESCE(fwq.submission_status, 1) = 1`.
    - A day that only holds an outage or non-submission reason has status 0, so it never appears
      there.
    - It shows `fwq.updated_at` as the last submission time.
  - None of these queries filters by channel.
- **user-service `PublicPumpOperatorRepository.findPumpOperatorById`** backs
  `GET /api/v1/pumpoperator/pump-operators/{id}`, which the SO/SDO pump-operator view and the
  village dashboard both call. It works out last submission, reporting rate and missed days from
  `flow_reading_table`, with no channel filter.
- **scheme-service** `SchemeDbRepository.listSchemesWithYesterdayFinalReadingForUser` (line 400) is
  the fix-readings list. Its count query doesn't read `flow_reading_table`.

## 4. Design

### 4.1 Event contract: `MeterReadingEvent`

All new fields are additive. The analytics copy already has `@JsonIgnoreProperties(ignoreUnknown = true)`.

| Field | Type | Notes |
| --- | --- | --- |
| `sourceReadingId` | `Long` | `flow_reading_table.id` |
| `sourceUpdatedAt` | `String` (ISO-8601 local date-time) | `flow_reading_table.updated_at` read back from the database (`RETURNING` or read the row), written with `clock_timestamp()` (2.4). Never the Java clock. |
| `calculationParameters` | `CalculationParameters` | Only for ELM and PDU, otherwise NULL |

`CalculationParameters` has version 1. The producer (telemetry) and the consumer (analytics) each
keep their own minimal copy:

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

- `elmFormula` is NULL when the tenant hasn't set a formula and for PDU.
- `kFactor` is NULL when the scheme hasn't set one and for PDU.
- `pumps` lists only active pumps. The values are exactly what is stored in the table.

### 4.2 Analytics

**Kinds:** add the `enums/ReadingKind` enum (`METER_INDEX`, `PERIOD_AMOUNT`). `ReadingChannel.kind()`
returns `Optional`: BFM and ELM are `METER_INDEX`, PDU is `PERIOD_AMOUNT`, and IOT and MAN have no
kind.

**Changing the calculator interface.** The kind decides how much of the channel's quantity a day or
a submission has. The channel's calculator only turns that amount into litres.

- `WaterQuantityContext(tenantId, schemeId, readingDate, channel, BigDecimal amount, CalculationParameters parameters)`.
  The amount is in the channel's standard unit.
- `WaterQuantityCalculator.calculate` returns a sealed `WaterQuantityOutcome`, which is either
  `Derived(long litres)` or `NotDerivable(Reason)`. The reasons are `MISSING_FORMULA`,
  `NO_ACTIVE_PUMP`, `MISSING_PARAMETER` and `INVALID_PARAMETER`.
- A result too large for `BIGINT` still throws `WaterVolumeOutOfRangeException`, handled as in
  2.5.
  - The exception is generalised to carry the value and its unit, because today it only knows m³.
  - The PDU day total adds with `Math.addExact`, and an overflow is handled the same way.
- `BfmWaterQuantityCalculator` becomes `WaterVolumeUnits.cubicMetresToLitres(amount)`. The
  difference and "no starting point → 0" move into the `METER_INDEX` amount rule, and BFM results
  stay exactly the same.

**New classes in `service/water/`:**

- **`PumpParameterAggregator`** takes the snapshot's pumps and does the following:
  - converts HP and bHP to kW
  - checks each pump's own value: an efficiency above 1, or a value of 0 or less, is
    `INVALID_PARAMETER`
  - averages each parameter over the pumps that have a value
  - checks the parameters in `PumpParameter` order and returns the first failure's reason
  - returns `AveragedPumpParameters` or a `NotDerivable` reason
- **`ElmWaterQuantityCalculator`** chooses an `ElmVolumeFormula` by `CalculationParameters.elmFormula`.
  The three formulas are `ConsumptionRateFormula` (F1), `MotorPowerFormula` (F2) and
  `HydraulicEnergyFormula` (F3), and each one lists the parameters it needs. The calculator then
  multiplies by `k_factor`.
- **`PduWaterQuantityCalculator`**: `minutes × average LPM × number of active pumps` for one
  submission (Q21). It ignores `kFactor`.
- **`WaterQuantityRecalculationService`**, taken out of `FactServiceImpl`, does all the
  recalculation. **`recalculateDay(tenant, scheme, date)`**:
  - finds the day's channel (the channel of the latest reading, with NULL counted as BFM), falling
    back to another channel's total as in 2.5 (Q17, Q19)
  - when that channel has no kind or no calculator, leaves the day's row alone and counts
    `water_quantity.calculator.missing` (2.5, Q14)
  - for `METER_INDEX`, works out the day's amount with the rule used today plus a channel filter,
    then calls the calculator with the snapshot of the day's latest reading
  - for `PERIOD_AMOUNT`, calls the calculator once for each of the day's submissions on that
    channel, with that submission's amount and snapshot, and adds up the litres. If any result is
    `NotDerivable`, so is the day.
  - writes `fact_water_quantity_table` using the same find-and-update as today:
    - `user_id` and `submission_status` come from the day's latest reading (2.5).
    - When called as the follow-up, it skips the write if nothing would change.
  - when the day can't be calculated, records the metric and removes the day's row if it holds a
    total worked out from readings (2.5)
  - **The follow-up:** the ingestion then recalculates, for each `METER_INDEX` channel, the next
    date that has a reading on that channel, once per date (2.5).

**Repository:**

- `FactMeterReadingRepository` gets a channel filter on the day-reading and starting-point queries:
  `COALESCE(channel, 1) = :channel`, so legacy NULL counts as BFM.
- It also gets:
  - `findNextReadingDate`
  - `existsOnAnotherChannelBetween`: whether a reading on another channel is dated strictly between
    a starting point and the day (2.5, Q16)
  - `findLatestChannelOnDate`
  - `findByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc`: every reading on the day,
    on any channel, latest first, for a day with no starting point (2.5, Q17)
  - `findDayReadings`: the day's rows on one channel, each with its amount and snapshot, for
    `PERIOD_AMOUNT`
- `FactWaterQuantityRepository` gets `deleteReadingDerivedDay(tenant, scheme, date)`, which deletes
  only where `outage_reason IS NULL AND non_submission_reason IS NULL`.
- Upserts use native SQL:

```sql
INSERT INTO analytics_schema.fact_meter_reading_table (...) VALUES (...)
ON CONFLICT (tenant_id, source_reading_id) WHERE source_reading_id IS NOT NULL
DO UPDATE SET ...
WHERE fact_meter_reading_table.source_updated_at IS NULL
   OR fact_meter_reading_table.source_updated_at <= EXCLUDED.source_updated_at
RETURNING id
```

  If no row comes back, the event is older than what is stored. Recalculation is skipped and
  `meter_reading.stale_event` is incremented. `<=` is used so that replaying the same version is
  harmless. The upsert runs through `JdbcTemplate` in `FactIngestionRepository`, because a Spring
  Data `@Modifying` query can't return the `RETURNING` row. It joins the same transaction as the JPA
  writes.

**Ingestion order** in `FactServiceImpl.ingestMeterReading`, all in one transaction:

1. parse `sourceUpdatedAt`. An unparseable value throws `MalformedEventException`, which the Kafka
   error handler doesn't retry, so the event goes to the DLT at once (Q23).
2. advisory lock, on both schemes when the reading moved (2.5)
3. upsert (or insert, for legacy events)
4. `ensureDateExists` and `updateOperatorAttendance`, as today
5. recalculate the day, plus the `METER_INDEX` follow-up
6. recalculate the day the reading moved off, if it moved

**Metrics:**

- `water_quantity.not_derivable{channel, reason}`
- `water_quantity.day_total_removed{channel}`
- `water_quantity.channel_fallback{channel, reason}` (Q19)
- `water_quantity.motor_power_unit.unknown` (no tags)
- `meter_reading.stale_event`
- `meter_reading.source_updated_at.unparseable` (Q23)

Tenant and scheme go in the WARN log line, not in metric tags, because there are too many values
for tags. The existing `water_quantity.calculator.missing` counter drops its `tenantId` and
`schemeId` tags for the same reason. Its WARN line already carries both, and no dashboard or alert in the
repo uses the tags.

### 4.3 Telemetry

**Channel first.** In `createReading`, work out `resolvedChannel` (the declared channel, otherwise
the preference) before capturing the reading. Remove the later lookup at line 435.

**Units:**

- `channel/ReadingUnit` enum with the UCUM code, channel and factor to the standard unit:
  - `m3` = 1, `kL` = 1, `L` = 0.001 (BFM)
  - `kW.h` = 1 (ELM)
  - `min` = 1, `h` = 60 (PDU)
- Matching is case-insensitive, and the UCUM spelling is what gets stored.
- `ReadingChannel.standardUnit()`.
- Conversion is exact (`BigDecimal`).
- `reading_unit` only applies to `confirmed_reading`. On an image-only submission, any unit other
  than the standard one gets a 400.

**Capture:** in the `service/capture/` package.

- `ReadingCapture` has `CaptureOutcome capture(CaptureInput)`. `CaptureOutcome` is either
  `Captured(CapturedReading)` or `Rejected(errorCode, message)` / `Retry`, matching what
  `createReading` returns today.
- `CapturedReading` contains:
  - the value in the standard unit
  - the submitted unit
  - the extracted value
  - the confidence
  - the provenance (the existing `RolloverResolutionService.SOURCE_*` values)
  - the `OcrReadingResult`, which may be absent
- **`ImageReadingCapture`** covers the current OCR block: unreadable image, RETRY and the
  unreadable-image anomaly. It rejects a channel whose `supportsImageReading()` is false before
  reading any OCR config.
- **`SubmittedValueCapture`** covers `MANUAL` and `EXTERNALLY_ASSERTED`, plus unit conversion.

**OCR by channel:**

- Add `ReadingChannel.supportsImageReading()` (2.7).
- Add `MeterReadingExtractor.channel()`. `FlowVisionOcrExtractor` returns BFM.
- `OcrProviderResolver.resolve(tenantId, channel)` reads that channel's keys (2.7):
  - BFM reads `ocr_*`, and a field the tenant didn't set falls back to the global `ocr.*` value, as
    today.
  - ELM reads `ocr_elm_*` and never falls back to `ocr.*`. A field the tenant didn't set is left
    to the ELM extractor's own configuration.
  - No keys means NULL, which now means "the channel's default provider", not "FlowVision".
- `OcrProviderRegistry` indexes extractors by channel and provider. `get(channel, providerId)`
  returns `Optional`:
  - the tenant's provider, if it is registered for that channel
  - otherwise the channel's default provider, logged at WARN when a named provider wasn't found.
    BFM's default is `ocr.default-provider`, as today. Other channels have none until an extractor
    exists.
  - otherwise empty
- **Every extraction goes through the registry**, including when the settings are NULL:
  - `BfmReadingService.extractReading` and `OcrReadingsRetryService` (`resolveExtractor`,
    `invokeExtractor`) stop injecting a single default `MeterReadingExtractor`.
  - Once nothing injects a single extractor, `@Primary` comes off `FlowVisionOcrExtractor`.
  - The retry service keeps its resilience instances per provider, as today.
- Empty means the request gets a 400 with the new code `IMAGE_NOT_SUPPORTED_FOR_CHANNEL`.
- **WhatsApp:** `processImage` returns the same rejection, localised in
  `ConversationLocalizationService.localizeMessage` like the other rejection texts (Q13).

**Insert:**

- Add overloads that take `channel` and `submittedUnit`, as the repository already does for new
  arguments, and have each return `(id, updatedAt)`:
  - `TelemetryTenantRepository.createFlowReading`
  - `persistFlowReadingWithTracking`
  - `updateFlowReadingFromIngestion`
- What each caller passes:
  - `createReading`: the resolved channel and the submitted unit
  - the WhatsApp manual insert: the resolved channel and the standard unit
  - the `/location` placeholder: NULL and NULL. It holds no reading, and NULL counts as BFM, as
    today.
- Remove both `updateFlowReadingChannel` calls from `createReading`.
- Every `flow_reading_table` write sets `updated_at = clock_timestamp()` instead of `NOW()` (2.4).

**Snapshot:**

- `CalculationParametersSnapshotter` reads:
  - `ELM_WATER_QUANTITY_FORMULA` through `TenantConfigRepository`. The stored value is
    `{"formula": "F1" | "F2" | "F3"}`. A missing or unreadable value becomes NULL, which analytics
    treats as `MISSING_FORMULA`.
  - `scheme_master_table.k_factor`
  - the active pumps
- It only runs for ELM and PDU. For PDU it reads only the active pumps.

**Publishing again:**

- Move `publishConfirmedReadingUpdate` into a `ReadingRepublisher` component. It builds the event
  from the stored row, including `sourceReadingId`, `sourceUpdatedAt` and a fresh snapshot.
  - It reads the row by id with a new `findFlowReadingById`. `correlation_id` isn't unique, and
    `TelemetryCompletedFlowReading` doesn't carry every field the event needs.
  - The reset moves onto it too. Its user stays the same, because `operator.id()` is the row's
    `created_by`.
- These use it:
  - `BfmReadingService`
  - `TelemetrySchemeReadingService`
  - `MeterReadingConversationService`

**Lookups by channel:**

- `findLatestConfirmedReadingSnapshot` and `findRecentDailyConfirmedReadings` take the channel,
  with NULL counted as BFM. This applies to their uses in:
  - the duplicate-image guard
  - the rollover history
  - the response's `lastConfirmedReading`
  - the WhatsApp manual reading's baseline
- A PDU response carries no `lastConfirmedReading`, because a run has no earlier total to compare
  with.
- BFM results don't change, because no non-BFM rows exist yet.

**Corrections** (2.6):

- `PUT /readings` accepts the optional `reading_unit`, validated against the target row's channel.
- Every correction path writes `submitted_unit`.
- The WhatsApp manual reading's maximum check runs for BFM only. It measures the water norm and
  oversupply threshold against a meter total, which means nothing on ELM or PDU.

**PDU limits** (the API as well as WhatsApp, on submissions and on corrections):

- A submission can't be more than 1,440 minutes (`SubmittedValueCapture`).
- The scheme's PDU total for the day can't be more than 1,440 minutes (`PduDayLimit`, Q20). It
  includes the new value; a correction replaces its row's old value, and so does the row a WhatsApp
  manual value overwrites. The limit is per scheme, not per pump (Q21).
- Breaking either rule gets a 400 with `ABNORMAL_READING`, or a localised rejection on WhatsApp.
  Nothing is stored and no anomaly is raised.
- The day check and the write run in one transaction under
  `pg_advisory_xact_lock(hashtextextended('pdu_day:<schema>:<scheme>:<date>', 0))`, so two runs sent
  at the same moment can't both pass. `PUT /readings` joins its own transaction.

**Republishing stored readings (Q18):** `ReadingBackfillController` and `ReadingBackfillService`
serve `POST /api/v1/telemetry/internal/readings/republish`, behind `InternalAuthFilter`
(`X-Internal-Token`, configured as a SHA-256 hash in `TELEMETRY_INTERNAL_AUTH_TOKEN_HASH`). The
tenant comes from `X-Tenant-Code`. Readings are sent one at a time on the request thread through
`ReadingRepublisher.republishAndAwait`, each waiting for Kafka's acknowledgement, so a long run can't
fill the shared `kafkaPublisherExecutor` queue.

## 5. Phases

Analytics is deployed before telemetry at every step. Each phase is a separate PR, and each one
leaves the system in a working state. The user plans to release phases 1–5 and the review fixes
together, which is fine: phase 3 only has to be live no later than phase 4.

| Phase | Status | Commits |
| --- | --- | --- |
| 1. WhatsApp manual readings publish | Done | `d893e347` |
| 2. Analytics groundwork | Done | `eb43e3f7` |
| 3. SO/SDO screens BFM only | Done | `e5548edd`, plus the `LAG` fix `606b4c0c` |
| 4. Telemetry pipeline | Done | 4a `0191fff5`, 4b `e1ca6936`, 4c `f9590a3f`, 4d `86e5eb2c`, 4e `9741fe20`; follow-ups `27474504`, `d177768f`, `26499d3b` |
| Q16, Q17 (before phase 5) | Done | `09d9cd4f`, `41574be9` |
| 5. ELM and PDU calculations | Done | 5a `95920447`, 5b `526542b7`, 5c `26660f6b` |
| Lead-review fixes | Done | see "Lead-review fixes" below |
| 6. WhatsApp ELM and PDU | Not started | — |

### Phase 1: WhatsApp manual readings publish their event

Telemetry only.

**Changes:**

- Add `ReadingRepublisher` (moved out of `BfmReadingService.publishConfirmedReadingUpdate`, with no
  change in behaviour). It reads the row by id through a new `findFlowReadingById` (4.3).
- In all three branches of `manualReadingMessage`, write the resolved channel (for now through
  `updateFlowReadingChannel`, as `createReading` does), then publish from the stored row.
  - The extracted reading follows `publishableExtractedReading`.
  - On a newly inserted row it is NULL, the same as for asserted readings.
- `manualReadingMessage`'s catch block logs the raw `contactId` at ERROR. Mask it, as the other
  telemetry logs do (CLAUDE.md privacy rule).

**Tests (`MeterReadingConversationServiceManualReadingTest`):**

- each branch publishes exactly once, with the row's id, reading and channel
- no publish when the method throws before the write

**Notes:**

- Until phases 2 and 4 are deployed, a manual update to a row that was already published adds a
  second fact row. `PUT /readings` does the same today.
- A manual reading on a meter-replacement day now publishes, as a photo on that day already does.
  Analytics then replaces the day's meter-change reason with a total, measured against the old
  meter's last reading (section 7).

**Done when:** a manual reading made over WhatsApp creates a `fact_meter_reading_table` row and a
water quantity for the day.

### Phase 2: analytics groundwork

Analytics only. No telemetry change is needed, because the new fields are optional.

**Migration `V56__add_submission_identity_and_calculation_inputs_to_fact_meter_reading.sql`:**

- `source_reading_id BIGINT`
- `source_updated_at TIMESTAMP`
- `calculation_parameters JSONB`
- partial unique index `(tenant_id, source_reading_id) WHERE source_reading_id IS NOT NULL`
- index `(tenant_id, scheme_id, reading_date, reading_at, id)`, which serves:
  - the latest reading on a date
  - the starting point
  - the next date
  - the SO lookup in phase 3

  `channel` is left out on purpose. `COALESCE(channel, 1) = :channel` can't use a plain index
  column, and a day has only a few rows to filter.

  Use a plain `CREATE INDEX IF NOT EXISTS`, as V43 does. Analytics runs Flyway inside Spring Boot,
  where V43 found that `CONCURRENTLY` hangs. If prod size calls for it, create the index
  `CONCURRENTLY` by hand before the deploy, and the migration then does nothing. V55's `.conf`
  approach applies only to the tenant Flyway CLI.

**Code:**

- Event fields and entity fields. `calculationParameters` uses `@JdbcTypeCode(SqlTypes.JSON)` on
  the typed record.
- `ReadingKind`.
- The calculator interface change from 4.2, with BFM results unchanged.
- `WaterQuantityRecalculationService`, including the `PERIOD_AMOUNT` day total (no calculator is
  registered for PDU until phase 5).
- Upsert with version check.
- Advisory lock, keyed with a namespace (2.5).
- Channel-filtered queries.
- Next-day recalculation, keyed to the written reading's channel, and writing only when something
  changes (2.5).
- The day's `user_id` and `submission_status` from its latest reading (2.5).
- A day whose channel has no calculator keeps its row (Q14).
- Removing a day total worked out from readings when the day can't be calculated (Q11).
- `WaterVolumeOutOfRangeException` carries the value and its unit (4.2).
- `water_quantity.calculator.missing` drops its `tenantId` and `schemeId` tags (4.2).

**`recompute_water_quantity.sql`:**

- `cur` and `prev` are filtered with `COALESCE(r.channel, 1) = 1`.
- When the day's latest reading is not BFM, the result is `new_qty = NULL`, so the script doesn't
  touch that day.
- `scripts/water_quantity_units_fix.py` reads this file and classifies a day by whether
  `current_reading` is NULL. Give it its own class for "latest reading not BFM", so those days are
  skipped rather than reported as exceptions.

**Tests:**

- **Unit tests:**
  - `BfmWaterQuantityCalculatorTest` (rewritten for the new interface, same expected values)
  - `WaterQuantityCalculatorRegistryTest` (updated for the new interface)
  - `WaterQuantityRecalculationServiceTest` (Mockito):
    - a `PERIOD_AMOUNT` day adds up its submissions
    - one `NotDerivable` submission means the day can't be calculated
    - a day that can't be calculated removes its reading-derived total and records both metrics
    - a day whose channel has no calculator keeps its row, and the metric is counted
    - the day's row takes its user and status from the day's latest reading. A reading by operator
      A on day D doesn't credit day D+1, whose reading is by operator B, to A.
    - the next-day recalculation doesn't write, or move `updated_at`, when nothing changed
    - the follow-up runs after a BFM reading even when D's latest reading is ELM
    - a total too large to store leaves the row alone and counts `water_quantity.unstorable`
  - `FactServiceImplTest` (upsert, stale, legacy insert)
- **`FactMeterReadingRepositoryIntegrationTest`** (Testcontainers):
  - the upsert: new, newer, older, same version
  - channel filters: NULL is BFM, ELM rows are not a BFM starting point
  - next date, `findDayReadings`
- **`FactWaterQuantityRepositoryIntegrationTest`** (Testcontainers): `deleteReadingDerivedDay`
  removes a reading-derived row and leaves a row with an outage or non-submission reason.
- **`WaterQuantityBackfillParityIntegrationTest`:**
  - Its live side calls `BfmWaterQuantityCalculator` with two readings, which the new interface
    removes. It now runs the recalculation service's `METER_INDEX` rule.
  - new cases:
    - legacy NULL channel
    - a mixed-channel day
    - an ELM row sitting between BFM days
    - the next-day recalculation matches the script after an out-of-order arrival

**Done when:** replaying a correction event updates the same fact row. Events arriving out of order
end up with the same totals as events in order. The parity test passes.

### Phase 3: SO/SDO screens show BFM only

user-service, scheme-service and telemetry. It must be deployed before phase 4, so the SO/SDO
screens never show an ELM or PDU reading. Its analytics lookup uses phase 2's index. Dashboard APIs
don't change, including the ones the staff screens call (2.9, Q12).

**user-service `PersonSchemeRepository`:**

- Every `flow_reading_table` read in the staff queries gets `COALESCE(fr.channel, 'BFM') = 'BFM'`,
  inside the CTE, before `LAG`. That covers `listSchemesByPerson` (last and yesterday reading),
  `getSchemeDetails`, `countSchemeReadings` / `listSchemeReadings`, `countPumpOperatorsByPerson` /
  `listPumpOperatorsByPerson` (`fl` and the reporting rate) and `countPumpOperatorReadings` /
  `listPumpOperatorReadings`.
- The analytics branch (`aw`) of `countPumpOperatorsByPerson` / `listPumpOperatorsByPerson` leaves
  out days whose latest analytics reading is not BFM:

  ```sql
  AND COALESCE((SELECT fmr.channel
                FROM analytics_schema.fact_meter_reading_table fmr
                WHERE fmr.tenant_id = fwq.tenant_id
                  AND fmr.scheme_id = fwq.scheme_id
                  AND fmr.reading_date = fwq.date
                ORDER BY fmr.reading_at DESC, fmr.id DESC
                LIMIT 1), 1) = 1
  ```

  - The `COALESCE(…, 1) = 1` keeps a status-1 day that has no reading in
    `fact_meter_reading_table`, such as a legacy row. That day counts as BFM and is still shown, as
    today.
  - A day that only holds an outage or non-submission reason has status 0. `aw` already leaves it
    out, and still does.
- The `fl` fallback of `listPumpOperatorsByPerson` returns litres:
  `ROUND((confirmed_reading − prev_confirmed) × 1000)`. Its `COALESCE(aw..., fl...)` then no longer
  mixes units.
- Nothing else in the output changes.

**user-service `PublicPumpOperatorRepository`:** no change. `GET /pump-operators/{id}` is shared
with the village dashboard (2.9, Q12).

**scheme-service `SchemeDbRepository`:** the fix-readings list reads BFM readings only. Its count
query doesn't read `flow_reading_table` and doesn't change.

**telemetry:**

- `findLatestCompletedFlowReadingOnDate` and `findLatestCompletedFlowReadingForScheme` return the
  latest BFM reading, so the PATCH `yesterday-final-reading` corrects a BFM row only.
- The PATCH's "day before" and "day after" lookups (`findPreviousFlowReadingForScheme`,
  `findEarliestCompletedFlowReadingAfterDateForScheme`) get the same filter.
  - They only live until phase 4 removes the `WATER_QUANTITY_RECORDED` calls.
  - The filter stops them pairing a BFM reading with an ELM or PDU value in the meantime.

**Tests:**

- **Repository integration tests** (Testcontainers) in user-service and scheme-service.
  - user-service already has `PersonSchemeRepositoryIntegrationTest`.
  - scheme-service has no Testcontainers dependency, and its `SchemeDbRepositoryTest` uses Mockito.
    Add the dependency and a minimal test schema, following user-service.
  - Cover:
    - a BFM scheme
    - an ELM-only scheme and a PDU-only scheme, which both look like schemes with no readings
    - a scheme where an ELM reading sits between two BFM readings: `LAG` uses the earlier BFM
      reading
    - a scheme with no readings
    - on the pump-operator list:
      - an analytics day with only ELM readings is skipped
      - a day with a BFM reading followed by an ELM reading is skipped
      - a status-1 day with no reading in `fact_meter_reading_table` is shown
      - a day that only holds an outage reason (status 0) is still not shown
      - the `fl` fallback gives litres (a 1.25 m³ difference gives 1,250)
- **Telemetry** repository integration test and `TelemetrySchemeReadingServiceTest`:
  - the PATCH picks the latest BFM reading when a later ELM reading exists
  - a scheme with only ELM readings gets 404

**Done when:** no staff-screen query in scope (2.9) and no PATCH is affected by ELM or PDU rows,
and the pump-operator `lastWaterSupplied` is always in litres. Nothing else in the BFM output
changes, and no dashboard API changes.

### Phase 4: telemetry pipeline

Deployment order: tenant migration, then analytics (already deployed in phase 2), then telemetry.

**Tenant migration `V56__add_submitted_unit_to_flow_reading_table.sql`:**

- `submitted_unit VARCHAR(16)`, nullable, because old rows have no unit
- written in the V54 style: existing tenant schemas first, then the `create_tenant_schema` wrapper
- checked on a scratch DB only, with no migration test (this is the project's rule for simple
  column adds)

**Code:**

- Channel resolved first, `ReadingUnit`, `reading_unit` on `CanonicalReadingRequest`.
- The format mappers in `ingest/` pass the unit through where their format has one.
- `ReadingCapture`, `ImageReadingCapture` and `SubmittedValueCapture`.
- `ReadingChannel.supportsImageReading()`.
- OCR is chosen by channel through the registry for every call, with no FlowVision shortcut, and
  each photo channel has its own per-tenant override (4.3).
- The WhatsApp photo rejection is localised (Q13).
- `channel` and `submitted_unit` written in the insert, through overloads (4.3).
- `updated_at = clock_timestamp()` on every `flow_reading_table` write (2.4).
- `sourceReadingId` and `sourceUpdatedAt` on every `METER_READING_RECORDED`.
- `CalculationParametersSnapshotter` (reads `status = 1 AND deleted_at IS NULL`).
- Lookups by channel (4.3).
- The PDU limits, on submissions and corrections. Phase 4 shipped the 1,440-minute limit on one
  run only; the day limit came with lead-review fix 3 (Q20).
- Corrections on every channel (2.6, Q15):
  - `reading_unit` on `PUT /readings`
  - `submitted_unit` written by every correction path
  - the WhatsApp manual reading's maximum check limited to BFM
- Correction paths: `updateYesterdayFinalReadingBySchemeId` and `updatePreviousReadingMessage`
  publish again through `ReadingRepublisher`. Remove their `publishWaterQuantityRecorded` calls and
  the "day after" branches, then remove the now-unused publisher method.
- The reset publishes through `ReadingRepublisher`.
- `updatePreviousReadingMessage`'s catch block logs the raw `contactId` at ERROR. Mask it.

**New error codes:**

- `READING_UNIT_NOT_SUPPORTED`
- `IMAGE_NOT_SUPPORTED_FOR_CHANNEL`

Both come back as a `CreateReadingResponse` with the error code set, not as an exception. The
catch-all in `processCanonicalReading` would turn an exception into `PROCESSING_FAILED`.

**Tests:**

- **Unit tests:**
  - `ReadingUnitTest` (every code, wrong-channel unit, exact conversion)
  - `SubmittedValueCaptureTest`
  - `ImageReadingCaptureTest`:
    - the current OCR outcomes, carried over
    - a PDU photo is rejected without reading any OCR config
  - `OcrProviderRegistryTest` (by channel, tenant provider for the channel, fallback to the
    channel's default, no extractor)
  - `OcrProviderResolverTest`:
    - BFM reads `ocr_*` and falls back to `ocr.*`
    - ELM reads only `ocr_elm_*` and never falls back to `ocr.*`
    - no keys → NULL
  - `OcrReadingsRetryServiceTest`: NULL settings resolve through the registry by channel. An ELM
    call with no ELM extractor never reaches FlowVision.
  - `BfmReadingService*Test`:
    - the channel is resolved before OCR
    - the insert carries the channel and unit
    - a PDU photo with a typed value is accepted without OCR
    - an ELM photo is rejected when the tenant has no `ocr_elm_*` keys
    - a PDU response has no `lastConfirmedReading`, and a BFM response ignores ELM rows
  - `MeterImageWorkflowService*Test`: a WhatsApp photo from an operator whose preference is ELM or
    PDU gets the localised rejection
  - `ReadingIngestControllerUnitTest` and `MultiFormatReadingControllerTest` (the 400s, including
    `reading_unit` on `PUT /readings`)
  - `TelemetrySchemeReadingServiceTest`, `MeterReadingConversationServiceUpdatePreviousReadingTest`
    and `MeterReadingConversationServiceManualReadingTest`:
    - publish again, with no water-quantity event
    - a correction on a PDU row obeys the limits and writes `submitted_unit`
    - the manual maximum check is skipped for ELM and PDU
- **Integration tests (Testcontainers):**
  - `TelemetryTenantRepository` inserts, and `findFlowReadingById`
  - the lookups by channel (NULL counts as BFM)
  - `CalculationParametersSnapshotter` (active or inactive pumps, soft-deleted pumps, NULL k)

**Done when:** an ELM or PDU submission through the API is stored in the standard unit with its
submitted unit. The event carries the row's identity and a snapshot. Corrections update the
existing fact row.

### Phase 5: ELM and PDU calculations

**Tenant migration `V57__default_asset_pump_registry_status_to_active.sql`:**

- `ALTER COLUMN status SET DEFAULT 1` on existing tenant schemas
- a wrapper for new tenants
- scratch-DB check only

**tenant-service:**

- Add `TenantConfigKeyEnum.ELM_WATER_QUANTITY_FORMULA`: `GENERIC`, not public, not managed, not
  mandatory.
- Add `ElmFormulaConfigDTO`, stored as `{"formula": "F1" | "F2" | "F3"}`, and add it to the sealed
  `ConfigValueDTO` permits. tenant-service has no `ConfigValueConverter`; that and the enum entry are
  the whole registration.
  - The field is the new `enums/ElmFormula`, whose `@JsonCreator` accepts the code in any case
    (`f1` is stored as `F1`). An unknown value gets a 400.
  - Bean validation doesn't run on `treeToValue`, so `{}` or a null formula would still bind.
  - Add a `validatedFormula()` method and call it in `TenantManagementServiceImpl`, as
    `RegularityThresholdConfigDTO` does. A JSON `null` value is a 400 as well.
- No Kafka event is needed, because telemetry reads the key when it builds the snapshot.

**Analytics:**

- `PumpParameterAggregator`
- `ElmVolumeFormula` and its three implementations
- `ElmWaterQuantityCalculator`
- `PduWaterQuantityCalculator`

These register themselves through the existing registry.

**Tests:**

- Unit tests for each formula, using the worked examples in 2.2.
- HP and bHP conversion (7.5 HP gives 53,641 L and 7.5 bHP gives 54,386 L), case-insensitive
  matching, and an unknown unit.
- Averaging, including pumps with gaps.
- Efficiency above 1, and zero or negative divisors.
- A missing formula, and no active pump.
- NULL k counting as 1 for ELM, and PDU ignoring k.
- A result past `BIGINT` litres.
- `ElmFormulaConfigDTO`: an unknown value and an empty value both get a 400.
- `WaterQuantityCalculatorRegistryTest` covers ELM and PDU.
- An end-to-end ingestion integration test for one ELM day and one PDU day.

**Done when:** ELM and PDU days have the expected litres, and a day that can't be calculated is
visible through its metric.

### Lead-review fixes

A lead review after phase 5 raised Q18–Q23 (section 11). Each fix is its own commit group:

| Fix | Commits | What |
| --- | --- | --- |
| 1 | `01bdd715` | Keep a day's calculable total (Q19); PDU sums the rates of pumps that run together (Q21); V57 comments each pump rating with its unit (Q22) |
| 2 | `728435c9`, `4a1b0382` | Recalculate the day a corrected reading moves off; an unparseable `sourceUpdatedAt` goes straight to the DLT (Q23) |
| 3 | `b11003ed`, `4b7369f7` | The scheme's PDU minutes on one day can't pass 1,440, checked and written under a lock (Q20) |
| 4 | `ddd704f7`, `74a786d5`, `9190c90b`; docs `e0db17f5`, `5feecc54` | The republish endpoint (Q18): one reading at a time with Kafka acknowledgement, behind an operations token |

V56's column comments (`3570acb2`, `a3af46f1`) were edited in place, since V56 had not run anywhere.

### Phase 6: WhatsApp ELM and PDU (follow-up, outline only)

- `/selected/channel` returns a channel code and an `isPdu` flag. Stop writing a list position into
  `scheme_master_table.channel` (`ConversationSelectionService`, line 281).
- The PDU flow asks for the minutes only, with no photo step.
- A PDU submission adds a new row instead of overwriting the day's row, keeping the 1,440-minute
  limits. Today a WhatsApp manual value from a PDU operator overwrites their latest row that day.
- Manual ELM and PDU entry goes through `SubmittedValueCapture`. Already done for the WhatsApp
  manual reading in phase 4 (4d).
- ELM photos stay rejected until an ELM extractor exists (Q13).

## 6. Rollout

- **Deployment order within each phase:** tenant migrations, then tenant-service, then analytics,
  then the other services, then telemetry.
- **Phase order:** phase 3 must be live before phase 4, so the SO/SDO screens and the PATCH never
  see an ELM or PDU row.
- **Old events** (no `sourceReadingId`) are still inserted as new rows, so a rollback or a
  half-finished deploy is safe.
- **Keep `ingestWaterQuantity`.** Analytics' `WATER_QUANTITY_RECORDED` handler stays: outage,
  non-submission and meter-change reasons travel on that event. After phase 4, those reasons are
  the only thing sent on it.
- **Monitor after each deploy:**
  - `water_quantity.not_derivable`
  - `water_quantity.day_total_removed`
  - `water_quantity.channel_fallback`
  - `water_quantity.motor_power_unit.unknown`
  - `water_quantity.calculator.missing`
  - `meter_reading.stale_event`
  - `meter_reading.source_updated_at.unparseable`
  - `water_quantity.implausible`
  - `water_quantity.unstorable`
- **Backfill (Q18):** set `TELEMETRY_INTERNAL_AUTH_TOKEN_HASH` on telemetry to use the republish
  endpoint; while it is unset the endpoint answers 401. Before a tenant's first run, delete its ELM
  and PDU fact rows that have no `source_reading_id` (the query is in `backend/API_ENDPOINTS.md`). With
  `ANALYTICS_READ_FROM_AGGREGATES` on, re-aggregate republished dates older than the nightly lookback.

## 7. Effects on existing numbers (accepted)

- **Compliance counts will drop where corrections exist.** These counts `COUNT(*)` rows of
  `fact_meter_reading_table` (for example `SchemeRegularityRepository.getSubmissionStatusCountByUser`).
  With one row per submission, a corrected submission no longer counts twice. This applies only to
  new data.
- **Some BFM days change.** A BFM day corrected when there was no earlier reading no longer gets
  the whole meter total as that day's water.
- **PDU adds rows to those counts.** Each PDU run is its own submission, so a day with three runs
  adds three rows. A PDU submission has no OCR value, so it is never counted as compliant or
  anomalous, the same as an API-asserted reading today.
- **The day a scheme switches meters is skipped by the SO/SDO pump-operator list (Q17).** The
  day's total comes from the old meter, but the list's analytics branch picks a day's channel from
  its latest reading, which is the new meter's. It shows the previous BFM day instead.
- **SO/SDO reporting rate and last submission time count BFM submissions only** on the filtered
  screens (phase 3). An operator who submits only ELM or PDU readings looks as if they have never
  submitted.
  - The pump-operator view's header comes from a dashboard API and counts every channel (2.9).
  - Nudges and escalations (`NudgeRepository`) also count every channel.
- **Pump-operator `lastWaterSupplied`** is always in litres. Before, the fallback value was in m³
  (phase 3). It is also no longer always NULL: `606b4c0c` fixed the descending `LAG` window that
  made the latest reading's previous value NULL on the scheme list and the fallback.
- **Meter-replacement days with a manual reading** (phase 1) get a total instead of their
  meter-change reason, as they already do when a photo is sent.
  - The total is measured against the old meter's last reading, because analytics has no
    meter-replacement reset.
  - A new meter whose reading is above the old meter's last reading gives that day a wrong total.
- **WhatsApp photos from operators whose preference is ELM or PDU are rejected** from phase 4. No
  such operator exists in prod.

## 8. Out of scope

- Scripts to dedupe old fact rows, or to backfill past WhatsApp manual readings.
- APIs for pump parameters or `k_factor`.
- Enforcing one channel per scheme.
- Showing ELM and PDU readings on the SO/SDO screens.
- Filtering the dashboard APIs that the SO/SDO screens call (2.9).
- An ELM OCR model.
- Resetting the analytics starting point when a meter is replaced.
- Storing litres per submission.
- ELM and PDU over WhatsApp (phase 6).
- The IOT and MAN channels.
- Renaming `BfmReadingService`, which now handles every channel.
- Recalculating automatically when a tenant's formula or a scheme's pumps are saved. The backfill
  endpoint is run by hand (Q18).

**Known gaps, accepted:**

- **A retried PDU `POST` is counted twice.** The correlation id is generated per call, so a retry
  can't be recognised without a client `Idempotency-Key` header, which changes the API contract. The
  1,440-minute day limit bounds it.
- **Operator attendance isn't moved with a reading.** A correction that moves a reading to another
  day or scheme recalculates the day it left, but that day's `fact_operator_attendance_table` row
  stays.
- **A rejected WhatsApp ELM or PDU photo is still uploaded to storage** before it is rejected.
- **An ELM default OCR provider** is needed in `OcrProviderRegistry` once an ELM extractor exists.

## 9. Gotchas

- **ELM is a running meter total, not "kWh for the day".**
- **Readings are already `BigDecimal` from end to end.** The old plan's "section 0" was done in
  earlier work.
- **`user_channel_preference`** has been in the tenant schema since V47.
- **Mixed units on the pump-operator screen.** `listPumpOperatorsByPerson` already combines
  analytics litres with an m³ fallback in one field (`COALESCE(aw..., fl...)`). Phase 3 fixes it
  by converting the fallback to litres.
- **New SO/SDO queries must filter to BFM** until the staff screens are extended to ELM and PDU
  (2.9). Dashboard APIs don't, even when a staff screen calls them.
- **The day-channel rule exists in two places.** Analytics' `findLatestChannelOnDate` and the
  SO/SDO pump-operator lookup (phase 3) both take the latest reading by `reading_at`, then `id`,
  with NULL counted as BFM. Change them together.
- **NULL OCR settings used to mean FlowVision.** After phase 4 they mean "the channel's default
  provider". Nothing may call an extractor directly again, or ELM photos reach the BFM model.
- **The PATCH `yesterday-final-reading` is both a State-IT route and a staff-screen action.** It
  requires an API key and is also the Fix-readings save. It stays BFM-only (Q8). A State-IT caller
  correcting an ELM or PDU reading uses `PUT /readings`.
- **Two differently named analytics settings.** `SINGLE_TENANT_MODE` is bound as
  `analytics.single-tenant-mode` in analytics. It doesn't affect this work, but keep that in mind
  when adding analytics properties.

## 10. What changes for BFM

- **Forward-only behaviour changes:**
  - one fact row per submission
  - the no-earlier-reading fix for corrections
  - the next-day recalculation, which replaces telemetry's "day after" events. It credits the day to
    the operator of that day's latest reading, and doesn't move `updated_at` when nothing changed.
- **Display change:** the pump-operator list's fallback `lastWaterSupplied` is in litres instead of
  m³. Every other SO/SDO value keeps its unit.
- **Additions only:**
  - the new `fact_meter_reading_table` columns (2.4), which no screen shows
  - `submitted_unit` on corrected rows
- **Invisible changes:**
  - `updated_at` is written with `clock_timestamp()`
  - `water_quantity.calculator.missing` loses its `tenantId` and `schemeId` tags
- **Everything else**, including the day-total rule, rounding and the recompute script's BFM
  results, stays the same, and the parity test checks it.

## 11. Questions to answer before the phase they block

Q1–Q10 were answered by the user on 2026-09-28. Q1, Q8 and Q9 were revised and Q11–Q15 answered
on 2026-09-29. Q16 and Q17 were answered on 2026-09-30. Q18–Q23 came from the lead review and were
answered on 2026-09-30, after phase 5. "Review fix N" is the Nth group of commits that fixed the
review's findings.

| # | Question | Answer | Blocks |
| --- | --- | --- | --- |
| Q1 | What unit are `water_supplied` / `lastWaterSupplied` in? | Revised: the SO screens show BFM only, so units stay as they are (m³). Only the pump-operator list's m³ fallback is converted to litres, to match its analytics branch (2.9). | Phase 3 |
| Q2 | Which channel decides a day that has readings on more than one channel? | The channel of the day's latest reading. Revised by Q19: a total that can be calculated is never replaced by one that can't. | Phase 2 |
| Q3 | Several pumps where some have a value missing | Average over the pumps that have it. If none has it, the day can't be calculated. For PDU, replaced by Q21. | Phase 5 |
| Q4 | `motor_power_unit` other than kW or HP | bHP is accepted: × 0.9863 to HP, then × 0.7457 to kW (revised 2026-09-30; it was converted like HP). Any other unit is treated as missing, with a metric. | Phase 5 |
| Q5 | A PDU scheme has three runs on one day, and one of them can't be turned into litres (for example it was submitted before the pump's discharge capacity was entered). What is the day's total? | No total for that day, with a metric, instead of a total that is silently too low. | Phase 5 |
| Q6 | A reading is corrected after the pump data changed. Is the corrected reading calculated with the pump data at the time of the correction, or at the time of the original submission? | At the time of the correction (a new snapshot). | Phase 4 |
| Q7 | Apply the 1,440-minute PDU limits to the API as well, not only WhatsApp | Yes | Phase 4 |
| Q8 | PATCH `yesterday-final-reading` on a PDU row | Revised: the PATCH only targets BFM readings. A scheme with no BFM reading gets the existing 404 (2.9). | Phase 3 |
| Q9 | Keep the per-tenant `ocr_*` override for BFM | Yes. The override works for every channel that reads photos, and each has its own keys: `ocr_*` for BFM, `ocr_elm_*` for ELM. PDU never uses OCR (2.7). | Phase 4 |
| Q10 | Manual readings through a `ReadingCapture` step instead of a `ManualReadingExtractor` | Yes | Phase 4 |
| Q11 | A day that already has a total can later become impossible to calculate. For example, a PDU run is corrected and its new snapshot has no active pump. Should the day's existing `fact_water_quantity_table` row be left as it is, or removed? | Removed, with a metric, if the row holds a total worked out from readings. A row holding an outage, non-submission or meter-change reason is left alone. The day ends up with no total whatever order its readings arrive in (2.5). | Phase 2 |
| Q12 | Some staff screens call dashboard APIs: `GET /pump-operators/{id}` (shared with the village dashboard) and the analytics tiles. Filter them to BFM too? | No. Only the staff screens' own queries change. Dashboard APIs stay unfiltered, so the pump-operator view's header counts every channel (2.9). | Phase 3 |
| Q13 | What happens to a WhatsApp photo from an operator whose preference is ELM or PDU? | ELM is rejected until an ELM OCR model exists. PDU photos are rejected, and PDU works once phase 6 adds its flow. The rejection is localised. No such operator exists in prod (2.7). | Phase 4 |
| Q14 | A day whose channel has no calculator (IOT, MAN, or ELM/PDU before phase 5): keep or remove its existing row? | Keep it, and count `water_quantity.calculator.missing` (2.5). | Phase 2 |
| Q15 | Which correction paths can change ELM and PDU readings? | Every WhatsApp path and every State-IT correction, with the same rules as a submission. The PATCH `yesterday-final-reading` stays BFM-only (Q8) (2.6). | Phase 4 |
| Q16 | A scheme read on BFM for days 72–100 and on ELM on days 71 and 101. Day 101's ELM amount would be measured from day 71, counting days 72–100's water a second time. | A starting point with another channel's reading dated strictly between it and the day isn't used, so the day has no starting point and Q17 applies. The follow-up recalculates the next day on every `METER_INDEX` channel, so the result doesn't depend on arrival order (2.5). | Phase 5 |
| Q17 | A scheme's first ELM reading is the latest reading of a day that also has a BFM reading. ELM has no starting point, so the day would get 0 L, dropping the BFM total. | The day is worked out from the latest reading on another channel that gives it a total: a `METER_INDEX` channel with a starting point, or `PERIOD_AMOUNT`. Only with none is it 0 L. A channel whose total can be calculated wins over one whose total can't (Q19). The day is credited to that reading. `recompute_water_quantity.sql` declines such BFM days (backfill case B3). The SO pump-operator list still picks a day's channel from its latest reading, so it skips that day's BFM total (accepted, section 7) (2.5). | Phase 5 |
| Q18 | ELM and PDU readings stored before the tenant's formula or the scheme's pumps are set up have no total. Should such submissions be rejected, or the readings backfilled later? | Backfilled. Submissions are still accepted. `POST /api/v1/telemetry/internal/readings/republish` sends stored readings to analytics again. It takes an operations token (`X-Internal-Token`) and the tenant's `X-Tenant-Code` rather than the tenant's API key, which the tenant's integrator also holds (`9190c90b`), a date range of at most 31 days, an optional scheme by state or centre scheme id, and an optional channel. It runs only when called; saving the configuration doesn't trigger it. BFM is refused, because BFM fact rows from before V56 have no `source_reading_id` and would be duplicated. | Review fix 4 |
| Q19 | Q2 lets the channel of the day's latest reading decide. What if that channel's total can't be calculated but another channel's can? | Keep Q2, but never replace a total that can be calculated with one that can't. The latest reading on another channel whose total can be calculated gives the day's total, credited to that reading, counted in `water_quantity.channel_fallback`. Q17's fallback also prefers such a channel. | Review fix 1 |
| Q20 | Bring back the PDU day limit? Phase 4 limited each submission only. | Yes, as a reject: a scheme's PDU minutes on one day can't be more than 1,440 in total. The limit applies on `POST` and `PUT /readings` and both WhatsApp paths. A correction's own row is left out of the day's total. A run over the limit gets `ABNORMAL_READING`: nothing is stored and no anomaly is raised. The check and the write run under a lock on the scheme's day (4.3). | Review fix 3 |
| Q21 | A PDU scheme has more than one active pump. Is a run's time per pump, or the time they ran together? | The time all active pumps ran together, so litres = minutes × the sum of their discharge rates. A pump with no rate counts at the others' average. This replaces Q3's average for PDU; ELM still averages. The day limit stays 1,440 minutes, not 1,440 per pump. | Review fix 1 |
| Q22 | What unit is `pump_discharge_capacity` in? | Always L/min. V57 comments each pump rating column with the unit the formulas assume. | Review fix 1 |
| Q23 | What happens to a meter-reading event whose `sourceUpdatedAt` can't be parsed? It used to be stored with no version, and the upsert then dropped it as stale. | It goes straight to the DLT without retries, counted in `meter_reading.source_updated_at.unparseable`. | Review fix 2 |

## 12. How to check the work

There is no root `pom.xml`, so each service is built from its own directory.

```bash
export JAVA_HOME=<local JDK 21>   # Lombok breaks under newer JDKs
(cd backend/analytics-service && mvn test -Dtest='*WaterQuantity*Test,*Formula*Test,PumpParameterAggregatorTest,FactServiceImplTest,FactIngestionRepository*Test,FactMeterReadingRepositoryIntegrationTest,FactWaterQuantityRepositoryIntegrationTest,MeterReadingIngestionIntegrationTest,KafkaConfigTest')
(cd backend/user-service && mvn test -Dtest='PersonSchemeRepository*Test')
(cd backend/scheme-service && mvn test -Dtest='SchemeDbRepository*Test')
(cd backend/telemetry-service && mvn test -Dtest='ReadingUnitTest,*Capture*Test,PduDayLimit*Test,OcrProvider*Test,OcrReadingsRetryService*Test,BfmReadingService*Test,MeterImageWorkflowService*Test,ReadingIngestController*Test,MultiFormatReadingControllerTest,TelemetrySchemeReadingServiceTest,MeterReadingConversationService*Test,ReadingRepublisherTest,ReadingBackfill*Test,InternalAuth*Test,CalculationParametersSnapshotterTest,SchemeCalculationInputRepositoryIntegrationTest,TelemetryTenantRepository*Test,WebhookRouteCoverageTest,RouteParityTest')
(cd backend/tenant-service && mvn test -Dtest='*ElmFormula*Test,TenantConfigKeyEnumTest,TenantManagementServiceImpl*Test')
```

The integration tests need Docker. To check the migrations:

- Tenant: apply V56 and V57 to a scratch database containing two tenant schemas, then provision a
  new tenant and confirm its columns and defaults.
- Analytics: apply V56 to a scratch analytics schema.
