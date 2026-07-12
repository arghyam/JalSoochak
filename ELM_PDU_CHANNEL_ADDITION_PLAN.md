# Plan: ELM (Electricity) & PDU (Pump-Duration) Water-Quantity Channels

## Context

Schemes without a Bulk Flow Meter (BFM) need water-quantity derived from other signals:

- **ELM (Electric Meter):** derive pumped volume from electricity consumed by the pump motor.
  `V = (η · E) / (ρ · g · H)` — using pump efficiency `η`, total dynamic head `H`, input energy `E`.
- **PDU (Pump Duration):** `Water (L) = Discharge Rate (L/hour) × Running Time (hours)`.

A pluggable per-channel architecture was just landed for BFM (`ReadingChannel` enum, `WaterQuantityCalculator` + registry in analytics; `ReadingChannelResolver` in telemetry). This plan extends it with **real ELM and PDU calculators** and finishes the decoupling so current BFM behavior is byte-for-byte unchanged while new channels are drop-in.

### Locked decisions

1. **Pump params sourced in analytics, on demand.** Analytics reads `efficiency / head / discharge_rate` directly from `tenant_<state>.pumps_scheme_mapping_table`. **The Kafka `MeterReadingEvent` and all of telemetry stay unchanged** → BFM path is identical. All new logic is localized in analytics-service (the designated pluggable point).
2. **Channel resolution unchanged.** Keep resolving channel from `common_schema.user_channel_preference` (default BFM). No change to `ReadingChannelResolver` or `scheme_master_table.channel`.
3. Per-channel **submission endpoints** are handled in the Glific flow (out of scope here — noted below).

## Ground truth (verified)

- Calculator interface today: `int calculate(Integer currentReading, Integer previousReading)` — has **no** tenant/scheme id, so ELM/PDU can't fetch params. **Must widen.**
  `backend/analytics-service/.../service/water/WaterQuantityCalculator.java`
- Call site: `FactServiceImpl.updateWaterQuantityFromReading(...)` resolves `registry.resolve(event.getChannel())` then calls `calculate(current, previous)`, persists to `FactWaterQuantity` (Integer `waterQuantity`, liters). `backend/analytics-service/.../service/serviceImpl/FactServiceImpl.java`
- Pump params live in `tenant_<state>.pumps_scheme_mapping_table`: `efficiency FLOAT, head FLOAT, discharge_rate FLOAT, scheme_id INT, status INT, deleted_at` (V30 `create_tenant_schema`, lines ~271–299). **No JPA/CRUD/events exist for it** — it is read-only reference data.
- Analytics already does dynamic cross-schema JDBC reads with schema-name validation (`TenantDepartmentBoundaryRepository.validateSchemaName`, regex `^[a-z_][a-z0-9_]*$`).
- `DimTenant.state_code` + `DimTenantRepository` resolve `tenantId → tenant_<state_code>` schema.
- `FactServiceImplTest` injects a real `@Spy WaterQuantityCalculatorRegistry(List.of(new BfmWaterQuantityCalculator()))` and asserts `registry.resolve(channel)` — keeps working after the signature widen.
- Telemetry already attaches the resolved channel code in `BfmReadingService.createReading` → `publishMeterReadingRecorded(...)`. `flow_reading_table.duration INTEGER` exists (natural home for PDU running time).

## Implementation (all in analytics-service unless noted)

### 0. Reading-value typing & precision — REQUIRED before adding the ELM/PDU calculators

Review gap #2 (deferred to this PR on 2026-06-30). The calculator seam (`WaterQuantityContext`) now
exists, but the reading is still a single `Integer` whose physical meaning is implicit and
channel-dependent (BFM = cumulative meter index, ELM = kWh, PDU = minutes). Resolve this **before**
the ELM/PDU calculators land, otherwise the input semantics stay implicit and lossy:

- **Typing:** give the reading an explicit per-channel type instead of a bare `Integer` (e.g. a
  `ReadingMeasure`/named value at the seam — BFM meter index, ELM energy, PDU duration) so each
  calculator's expected input is self-describing, not a convention.
- **Precision:** widen the reading from `Integer` to `BigDecimal` end-to-end — `MeterReadingEvent`
  (telemetry publisher **and** analytics consumer), `FactMeterReading`/`FactWaterQuantity` columns +
  a migration — because ELM kWh (and fractional PDU hours) lose data under integer truncation.
- Designed now-deferred (rather than done speculatively) so the per-channel types match the first
  real non-BFM channel; it is a prerequisite, not optional.

### 1. Widen the calculator seam

- New value object `service/water/WaterQuantityContext.java`: `tenantId, schemeId, readingDate, currentReading, previousReading, channelCode` (all that a calculator may need; immutable, `@Builder`).
- `WaterQuantityCalculator.calculate(...)` → `int calculate(WaterQuantityContext ctx)`. Keep `ReadingChannel channel()`.
- `BfmWaterQuantityCalculator`: body unchanged logic — `Math.max(0, current - previous)` reading from `ctx`. (Behavior identical.)
- `WaterQuantityCalculatorRegistry`: unchanged (still `resolve(Integer channelCode)`).

### 2. Pump-parameter provider (read-only, tenant-schema)

- `service/water/PumpSchemeParameters.java` — record/POJO: `Double efficiency, Double head, Double dischargeRate`.
- `repository/PumpSchemeParameterRepository.java` (`JdbcTemplate`): `Optional<PumpSchemeParameters> findActiveByScheme(Integer tenantId, Integer schemeId)`.
  - Resolve schema: `DimTenantRepository.findById(tenantId)` (or `findByTenantId`) → `state_code` → `"tenant_" + stateCode.toLowerCase()`; reuse the `validateSchemaName` regex guard before interpolating into SQL.
  - Query: `SELECT efficiency, head, discharge_rate FROM <schema>.pumps_scheme_mapping_table WHERE scheme_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1`.
  - Return `Optional.empty()` if no schema/row; never throw on missing data (log at DEBUG/WARN).

### 3. ELM and PDU calculators

- `service/water/ElmWaterQuantityCalculator.java` (`@Component`, channel `ELM`, injects `PumpSchemeParameterRepository`):
  - Input `currentReading` = **energy consumed in kWh** for the day (absolute, not a delta).
  - `Water_liters = round( efficiency × kWh × 3_600_000 / (9.81 × head) )`.
  - Guards: missing params / `head <= 0` / null reading → return `0` + WARN. If `efficiency > 1`, treat as percentage → `/100`.
- `service/water/PduWaterQuantityCalculator.java` (`@Component`, channel `PDU`, injects repository):
  - Input `currentReading` = **running duration in minutes** (matches `flow_reading_table.duration`).
  - `Water_liters = round( discharge_rate × (minutes / 60.0) )`.
  - Guards: missing `discharge_rate` / null reading → return `0` + WARN.
- Both are non-cumulative (ignore `previousReading`); BFM stays delta-based. Output is non-negative liters (Integer) to match `FactWaterQuantity.waterQuantity`.
- Registry auto-discovers both as `@Component` beans — no registry edit needed.

### 4. Wire the call site

- In `FactServiceImpl.updateWaterQuantityFromReading`, build a `WaterQuantityContext` (tenantId, schemeId, readingDate, current, previous, channel) and call `registry.resolve(event.getChannel()).calculate(ctx)`. No other logic changes; persistence/upsert untouched.

### 5. Telemetry / submission (mostly documentation)

- **No telemetry code change required** for analytics to work (params read in analytics; channel already attached).
- **Note for the Glific flow (out of scope):** per-channel submission endpoints populate the single reading value with the channel-appropriate input — ELM = kWh consumed, PDU = running duration (minutes) — and PDU should persist `flow_reading_table.duration`. The existing single-value reading field is reused; no event-schema change.

## Formulas & unit conventions (document in calculator Javadoc)

- ρ = 1000 kg/m³, g = 9.81 m/s². ELM liters: `η·kWh·3.6e6 / (9.81·H)` (H in metres). PDU liters: `discharge_rate(L/h)·hours`.
- Reading value transported as Integer (current event type). PDU duration in **minutes**; ELM energy in **whole kWh**. Sub-unit precision would require widening the reading field to `BigDecimal` end-to-end — deferred (would touch the BFM path).
- ⚠️ Confirm against domain spec: unit of `efficiency` (fraction vs %) and `discharge_rate` (L/hour assumed). Guards above are defensive but the canonical units should be validated with the product owner.

## Tests (TDD — write first; analytics test conventions)

- `BfmWaterQuantityCalculatorTest` — update to `WaterQuantityContext` signature; assert identical results.
- `ElmWaterQuantityCalculatorTest` (Mockito) — mock repository: nominal formula, `head=0`/null params → 0, efficiency-as-percentage path, null reading.
- `PduWaterQuantityCalculatorTest` (Mockito) — nominal, null/zero discharge_rate → 0, minutes→hours conversion.
- `WaterQuantityCalculatorRegistryTest` — update inline stub(s) to new signature; assert ELM/PDU resolve and BFM remains default fallback.
- `PumpSchemeParameterRepositoryIntegrationTest` (**Testcontainers**, per repo rule #2) — seed a `tenant_xx` schema + `pumps_scheme_mapping_table` + a `dim_tenant_table` row; verify read, soft-delete exclusion, and empty-on-missing.
- `FactServiceImplTest` — update to widened signature; keep the existing `resolve(ELM.getCode())` assertion; the `@Spy` real registry still drives the BFM delta path.

## Prerequisite (in-progress channel work)

The interrupted telemetry compile error (`BfmReadingService` constructor / 7-arg) was from a missed `super(null×6)` in `SingleTenantTelemetryControllerUnitTest`; the 7th `null` was added. Before starting: confirm both services compile and the existing channel tests are green:
`mvn -o test -pl backend/telemetry-service -Dtest='ReadingChannel*Test,BfmReadingService*Test,SingleTenantTelemetryControllerUnitTest'`.

## Out of scope / follow-ups

- The two telemetry correction flows that pre-compute `WATER_QUANTITY_RECORDED` (`TelemetrySchemeReadingService:115`, `GlificMeterWorkflowService:1538`) remain BFM-only delta adjustments — unchanged, noted as follow-up.
- Per-channel Glific submission endpoints (owned by submission/Glific flow).
- Optional later: replicate pump params into an analytics dimension + cache, if on-demand reads become a hotspot.

## Verification

1. `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`
2. Analytics unit + new calculator tests: `mvn -o test -pl backend/analytics-service -Dtest='*WaterQuantityCalculator*Test,WaterQuantityCalculatorRegistryTest,FactServiceImplTest'`
3. Pump repo integration test (Docker required): `mvn -o test -pl backend/analytics-service -Dtest=PumpSchemeParameterRepositoryIntegrationTest`
4. Full builds: `mvn -o clean package -pl backend/analytics-service` and `-pl backend/telemetry-service`.
5. Manual sanity: emit a `METER_READING_RECORDED` with `channel=2` (ELM) and `channel=3` (PDU) for a scheme that has pump params, confirm `fact_water_quantity_table.water_quantity` matches the formula; emit `channel=1`/null and confirm BFM delta is unchanged.
