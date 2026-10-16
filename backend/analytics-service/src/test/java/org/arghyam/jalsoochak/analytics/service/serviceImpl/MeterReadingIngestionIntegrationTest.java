package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.analytics.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.repository.FactIngestionRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.arghyam.jalsoochak.analytics.repository.SubmissionAttemptRepository;
import org.arghyam.jalsoochak.analytics.service.FactService;
import org.arghyam.jalsoochak.analytics.service.water.BfmWaterQuantityCalculator;
import org.arghyam.jalsoochak.analytics.service.water.ConsumptionRateFormula;
import org.arghyam.jalsoochak.analytics.service.water.ElmWaterQuantityCalculator;
import org.arghyam.jalsoochak.analytics.service.water.HydraulicEnergyFormula;
import org.arghyam.jalsoochak.analytics.service.water.MotorPowerFormula;
import org.arghyam.jalsoochak.analytics.service.water.PduWaterQuantityCalculator;
import org.arghyam.jalsoochak.analytics.service.water.PumpParameterAggregator;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityCalculatorRegistry;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityRangeReporter;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityRecalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ELM or PDU reading from the event JSON telemetry publishes to the litres stored for its day,
 * through the production ingestion, recalculation, calculators and formulas.
 *
 * <p>Each case starts from the JSON as {@code TelemetryEventPublisherTest} pins it, and goes through
 * the application's own {@code ObjectMapper}, the JSONB snapshot column and back. A snapshot field
 * that silently failed to deserialise would change the litres: the ELM cases use a {@code kFactor}
 * other than 1 and a motor power in HP for that reason.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
        FactServiceImpl.class,
        FactIngestionRepository.class,
        SubmissionAttemptRepository.class,
        WaterQuantityRecalculationService.class,
        WaterQuantityCalculatorRegistry.class,
        BfmWaterQuantityCalculator.class,
        ElmWaterQuantityCalculator.class,
        PduWaterQuantityCalculator.class,
        ConsumptionRateFormula.class,
        MotorPowerFormula.class,
        HydraulicEnergyFormula.class,
        PumpParameterAggregator.class,
        WaterQuantityRangeReporter.class
})
class MeterReadingIngestionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_meter_reading_ingestion_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.schemas", () -> "analytics_schema");
    }

    @TestConfiguration
    static class Metrics {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private FactService factService;

    @Autowired
    private FactWaterQuantityRepository waterQuantityRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimpleMeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    private static final int TENANT = 1;
    private static final int SCHEME = 1;
    private static final int OTHER_SCHEME = 2;
    private static final int OPERATOR = 7;
    private static final int OTHER_OPERATOR = 8;

    private static final LocalDate D1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 3, 3);

    private static final int BFM = ReadingChannel.BFM.getCode();
    private static final int ELM = ReadingChannel.ELM.getCode();
    private static final int PDU = ReadingChannel.PDU.getCode();

    /** One pump with every value set, as telemetry snapshots {@code asset_pump_registry_table}. */
    private static final String PUMP = """
            {"pumpId": 12, "pumpDischargeCapacityLpm": 500, "pumpEfficiency": 0.7, "pumpHeadM": 40,
             "motorPower": 7.5, "motorPowerUnit": "HP", "motorEfficiency": 0.85, "unitsConsumedPerHour": 5,
             "powerFactor": 0.8}""";

    @BeforeEach
    void setUp() {
        meterRegistry.clear();
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW())
                """);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, parent_department_location_id,
                 operating_status, created_at, updated_at)
                VALUES (1, 1, 'Scheme A', 1001, 2001, 100, 200, 1, NOW(), NOW()),
                       (2, 1, 'Scheme B', 1002, 2002, 100, 200, 1, NOW(), NOW())
                """);
    }

    @Test
    void elmDay_isTheDaysKilowattHoursThroughTheTenantsFormulaTimesTheSchemesKFactor() throws Exception {
        String f2 = elmSnapshot("\"F2\"", "0.9");
        ingest(1, ELM, "2026-03-01T08:00", "100.0", OPERATOR, f2);
        ingest(2, ELM, "2026-03-02T08:00", "110.0", OPERATOR, f2);

        // The scheme's first reading has no starting point.
        assertThat(litres(D1)).contains(0L);
        // 10 kWh x 500 LPM x 60 / (7.5 HP x 0.7457 = 5.59275 kW) = 53,640.87 L; x 0.9 = 48,276.79 L
        assertThat(litres(D2)).contains(48_277L);
    }

    @Test
    void kvahDay_isItsIncreaseTimesThePowerFactor_andTheSwitchFromKwhStartsAfresh() throws Exception {
        String f1 = elmSnapshot("\"F1\"", "0.9");
        // A manual reading, which is kWh.
        ingest(1, SCHEME, ELM, "2026-03-01T08:00", "100.0", OPERATOR, f1, null);
        ingest(2, SCHEME, ELM, "2026-03-02T08:00", "300.0", OPERATOR, f1, "kV.A.h");
        ingest(3, SCHEME, ELM, "2026-03-03T08:00", "310.0", OPERATOR, f1, "kV.A.h");

        // kWh and kVAh are different running totals, so the first kVAh reading has no starting point.
        assertThat(litres(D2)).contains(0L);
        // 10 kVAh x 0.8 = 8 kWh; 8 x 500 LPM x 60 / 5 kWh per hour = 48,000 L; x 0.9
        assertThat(litres(D3)).contains(43_200L);
    }

    @Test
    void pduDay_isTheSumOfEachRunsMinutesTimesItsOwnSnapshotsDischargeRate() throws Exception {
        ingest(1, PDU, "2026-03-01T08:00", "90", OPERATOR, pduSnapshot("500"));
        // The pump was changed between the runs; each run keeps the rate it was submitted with.
        ingest(2, PDU, "2026-03-01T18:00", "30", OPERATOR, pduSnapshot("400"));

        // 90 min x 500 LPM + 30 min x 400 LPM
        assertThat(litres(D1)).contains(57_000L);
    }

    @Test
    void switchDay_keepsTheOldMetersTotalAndTheNewMeterStartsFromItsOwnFirstReading() throws Exception {
        String f1 = elmSnapshot("\"F1\"", "0.9");
        ingest(1, BFM, "2026-03-01T08:00", "100.0", OPERATOR, null);
        ingest(2, BFM, "2026-03-02T08:00", "110.0", OPERATOR, null);
        // The scheme's first ELM reading, later on D2: D2's latest reading, with no starting point.
        ingest(3, ELM, "2026-03-02T18:00", "500.0", OTHER_OPERATOR, f1);
        ingest(4, ELM, "2026-03-03T18:00", "510.0", OTHER_OPERATOR, f1);

        FactWaterQuantity switchDay = day(D2).orElseThrow();
        // The flow meter's 10 m3, credited to the reading it was worked out from.
        assertThat(switchDay.getWaterQuantity()).isEqualTo(10_000L);
        assertThat(switchDay.getUserId()).isEqualTo(OPERATOR);
        // 10 kWh x 500 LPM x 60 / 5 kWh per hour = 60,000 L; x 0.9
        assertThat(litres(D3)).contains(54_000L);
    }

    @Test
    void dualMeterDay_keepsTheFlowMetersTotalWhenTheLaterElmReadingCannotBeCalculated() throws Exception {
        String noFormula = elmSnapshot("null", "0.9");
        ingest(1, BFM, "2026-03-01T08:00", "100.0", OPERATOR, null);
        ingest(2, ELM, "2026-03-01T09:00", "500.0", OTHER_OPERATOR, noFormula);
        // Arrives before D2's flow meter reading, which must not matter.
        ingest(4, ELM, "2026-03-02T09:00", "510.0", OTHER_OPERATOR, noFormula);
        ingest(3, BFM, "2026-03-02T08:00", "110.0", OPERATOR, null);

        // D2's latest reading is ELM, measured from D1's, but the tenant has no ELM formula.
        FactWaterQuantity d2 = day(D2).orElseThrow();
        assertThat(d2.getWaterQuantity()).isEqualTo(10_000L);
        assertThat(d2.getUserId()).isEqualTo(OPERATOR);
    }

    @Test
    void aDayThatCannotBeCalculated_hasNoTotalAndIsCountedWithItsReason() throws Exception {
        String noFormula = elmSnapshot("null", "0.9");
        ingest(1, ELM, "2026-03-01T08:00", "100.0", OPERATOR, noFormula);
        ingest(2, ELM, "2026-03-02T08:00", "110.0", OPERATOR, noFormula);

        assertThat(day(D1)).isEmpty();
        assertThat(day(D2)).isEmpty();
        Counter notDerivable = meterRegistry.find("water_quantity.not_derivable")
                .tags("channel", String.valueOf(ELM), "reason", "MISSING_FORMULA")
                .counter();
        assertThat(notDerivable).isNotNull();
        // One for each day: a tenant with no formula has no ELM water quantity, even for a first reading.
        assertThat(notDerivable.count()).isEqualTo(2.0);
    }

    @Test
    void aReadingCorrectedOntoAnotherDay_leavesTheDayItLeftWithNoTotal() throws Exception {
        ingest(1, BFM, "2026-03-01T08:00", "100.0", OPERATOR, null);
        ingest(2, BFM, "2026-03-02T08:00", "110.0", OPERATOR, null);
        assertThat(litres(D2)).contains(10_000L);

        // A newer version of submission 2, now dated D3.
        ingest(2, BFM, "2026-03-03T08:00", "120.0", OPERATOR, null);

        assertThat(day(D2)).isEmpty();
        assertThat(litres(D3)).contains(20_000L);
    }

    @Test
    void aReadingCorrectedOntoAnotherScheme_isTakenOutOfTheDayItLeft() throws Exception {
        ingest(1, BFM, "2026-03-01T08:00", "100.0", OPERATOR, null);
        ingest(2, BFM, "2026-03-02T08:00", "110.0", OPERATOR, null);
        ingest(3, BFM, "2026-03-02T09:00", "112.0", OPERATOR, null);
        assertThat(litres(D2)).contains(12_000L);

        // A newer version of submission 3, now on the other scheme.
        ingest(3, OTHER_SCHEME, BFM, "2026-03-02T09:30", "112.0", OPERATOR, null);

        // Worked out from the reading the day still has.
        assertThat(litres(D2)).contains(10_000L);
        // The other scheme's first reading has no starting point.
        assertThat(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                TENANT, OTHER_SCHEME, D2)).get().extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(0L);
    }

    private void ingest(long sourceReadingId, int channel, String readingAt, String reading, int userId,
                        String calculationParameters) throws Exception {
        ingest(sourceReadingId, SCHEME, channel, readingAt, reading, userId, calculationParameters);
    }

    private void ingest(long sourceReadingId, int schemeId, int channel, String readingAt, String reading,
                        int userId, String calculationParameters) throws Exception {
        ingest(sourceReadingId, schemeId, channel, readingAt, reading, userId, calculationParameters, null);
    }

    /**
     * Deserialises the JSON telemetry publishes and ingests it as the Kafka consumer does, each event in
     * a persistence context of its own, as each event's own transaction would have. The version is
     * derived from {@code readingAt}, so a later {@code readingAt} is a newer version.
     *
     * @param submittedUnit the reading's UCUM code; null for the channel's standard unit
     */
    private void ingest(long sourceReadingId, int schemeId, int channel, String readingAt, String reading,
                        int userId, String calculationParameters, String submittedUnit) throws Exception {
        String json = """
                {"eventType": "METER_READING_RECORDED", "tenantId": %d, "schemeId": %d, "userId": %d,
                 "extractedReading": %s, "confirmedReading": %s, "confidence": null, "imageUrl": null,
                 "readingAt": "%s", "channel": %d, "readingDate": "%s", "submissionStatus": 1, "readingType": 0,
                 "correlationId": "corr-%d", "sourceReadingId": %d, "sourceUpdatedAt": "%s:05.123456",
                 "calculationParameters": %s, "submittedUnit": %s}
                """.formatted(TENANT, schemeId, userId, reading, reading, readingAt, channel, readingAt.substring(0, 10),
                sourceReadingId, sourceReadingId, readingAt, calculationParameters,
                submittedUnit == null ? "null" : '"' + submittedUnit + '"');
        factService.ingestMeterReading(objectMapper.readValue(json, MeterReadingEvent.class));
        entityManager.flush();
        entityManager.clear();
    }

    private static String elmSnapshot(String formula, String kFactor) {
        return """
                {"version": 1, "elmFormula": %s, "kFactor": %s, "pumps": [%s]}""".formatted(formula, kFactor, PUMP);
    }

    /** As telemetry sends it for PDU: no formula and no k-factor. */
    private static String pduSnapshot(String dischargeCapacityLpm) {
        return """
                {"version": 1, "elmFormula": null, "kFactor": null, "pumps": [
                  {"pumpId": 12, "pumpDischargeCapacityLpm": %s, "pumpEfficiency": null, "pumpHeadM": null,
                   "motorPower": null, "motorPowerUnit": null, "motorEfficiency": null, "unitsConsumedPerHour": null}]}"""
                .formatted(dischargeCapacityLpm);
    }

    private Optional<FactWaterQuantity> day(LocalDate date) {
        return waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, date);
    }

    private Optional<Long> litres(LocalDate date) {
        return day(date).map(FactWaterQuantity::getWaterQuantity);
    }
}
