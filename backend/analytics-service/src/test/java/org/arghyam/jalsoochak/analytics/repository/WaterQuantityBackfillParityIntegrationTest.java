package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.service.water.BfmWaterQuantityCalculator;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the history backfill and live ingestion agree on every value.
 *
 * <p>{@code scripts/water_quantity_units_fix.py} repairs historical rows by recomputing them from the
 * readings. That is only safe if the recompute lands on exactly what {@code FactServiceImpl} would
 * have written for the same day — otherwise the backfill introduces a third set of wrong numbers, and
 * a reading arriving mid-run would flip its day between two different "correct" answers.
 *
 * <p>Neither side is re-implemented here. The live side runs the production units the ingestion path
 * uses to derive the value — {@link FactMeterReadingRepository#findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc}
 * for the day's reading, {@link FactMeterReadingRepository#findLatestBefore} for the baseline, and
 * {@link BfmWaterQuantityCalculator} for the arithmetic. The backfill side runs
 * {@code db/scripts/recompute_water_quantity.sql} verbatim, which is the same file the Python script
 * reads. The surrounding save/find-existing plumbing is out of scope: it chooses which row to write,
 * not what value goes in it.
 *
 * <p>The fixture is the set of cases the two defects turned on — a scheme's first-ever reading, a gap
 * in submissions, two readings on one day, a zero reading, and a meter that went backwards.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WaterQuantityBackfillParityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_backfill_parity_test")
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

    @Autowired
    private FactMeterReadingRepository meterReadingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final BfmWaterQuantityCalculator calculator = new BfmWaterQuantityCalculator();

    private static final int TENANT = 1;
    private static final int SCHEME = 1;
    private static final int GAP_SCHEME = 2;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);
    private static final LocalDate D4 = LocalDate.of(2026, 1, 4);
    private static final LocalDate D5 = LocalDate.of(2026, 1, 5);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_tenant_table,
                    analytics_schema.dim_date_table
                RESTART IDENTITY CASCADE
                """);
        // fact_water_quantity_table.date is FK-constrained to dim_date_table.full_date (V8).
        for (LocalDate date : List.of(D1, D2, D3, D4, D5)) {
            insertDate(date);
        }
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
    void theRecomputeLandsOnExactlyWhatLiveIngestionDerives() {
        seedReadings();
        // Legacy values: cubic metres, and a first-ever reading that stored the whole meter index.
        // Their content is irrelevant to the recompute — it is a function of the readings alone — but
        // seeding them wrong is what makes an accidental "keep the old value" pass fail this test.
        seedLegacyQuantityRow(SCHEME, D1, 100L);
        seedLegacyQuantityRow(SCHEME, D2, 40L);
        seedLegacyQuantityRow(SCHEME, D3, 0L);
        seedLegacyQuantityRow(SCHEME, D4, 25L);
        seedLegacyQuantityRow(GAP_SCHEME, D1, 500L);
        seedLegacyQuantityRow(GAP_SCHEME, D4, 70L);

        Map<Long, Long> recomputed = runRecompute();

        assertThat(recomputed).isNotEmpty();
        recomputed.forEach((id, newQty) ->
                assertThat(newQty)
                        .as("row %d must match what live ingestion would derive", id)
                        .isEqualTo(liveValueFor(id)));
    }

    @Test
    void aDayWithNoReadingIsLeftForTheCallerToReportRatherThanRecomputed() {
        seedReadings();
        // An outage / non-submission day: live ingestion never writes one, so the recompute has no
        // reading to derive from and must decline instead of inventing a 0.
        seedLegacyQuantityRow(SCHEME, D5, 900L);

        Long newQty = runRecompute().get(idOf(SCHEME, D5));

        assertThat(newQty).isNull();
    }

    @Test
    void aSchemesFirstEverReadingRecomputesToZeroNotTheWholeMeterIndex() {
        seedReadings();
        seedLegacyQuantityRow(SCHEME, D1, 100L);

        assertThat(runRecompute().get(idOf(SCHEME, D1))).isZero();
    }

    @Test
    void aReadingAfterAGapIsMeasuredAgainstTheLastActualReading() {
        seedReadings();
        // GAP_SCHEME read 500 on D1 and 560 on D4, with nothing in between. Against the previous
        // calendar day the delta was the whole index; against the last actual reading it is 60 m3.
        seedLegacyQuantityRow(GAP_SCHEME, D4, 70L);

        assertThat(runRecompute().get(idOf(GAP_SCHEME, D4))).isEqualTo(60_000L);
    }

    @Test
    void everyRowOfADuplicatedDayIsRepairedSoNoneIsLeftInCubicMetres() {
        seedReadings();
        // No uniqueness on (tenant_id, scheme_id, date); consumers read the latest by updated_at, id.
        long stale = seedLegacyQuantityRow(SCHEME, D2, 40L);
        long latest = seedLegacyQuantityRow(SCHEME, D2, 40L);

        Map<Long, Long> recomputed = runRecompute();

        assertThat(recomputed.get(stale)).isEqualTo(recomputed.get(latest));
        assertThat(isLatest(stale)).isFalse();
        assertThat(isLatest(latest)).isTrue();
    }

    /**
     * Readings covering every case the previous-day baseline got wrong.
     *
     * <pre>
     * SCHEME      D1 100          first ever, no baseline          -> 0
     *             D2 140 (08:00)  two readings on one day; the
     *                150 (17:30)  later one is the day's reading   -> (150-100) * 1000
     *             D3 150          meter did not move               -> 0
     *             D4 120          meter went backwards             -> 0, never negative
     *             D5 (no reading) outage day                       -> not recomputable
     * GAP_SCHEME  D1 500          first ever                       -> 0
     *             D2 0            a genuine zero, not a baseline
     *             D4 560          three-day gap, baseline is D1    -> (560-500) * 1000
     * </pre>
     */
    private void seedReadings() {
        insertReading(SCHEME, D1, 100, "2026-01-01T08:00:00");
        insertReading(SCHEME, D2, 140, "2026-01-02T08:00:00");
        insertReading(SCHEME, D2, 150, "2026-01-02T17:30:00");
        insertReading(SCHEME, D3, 150, "2026-01-03T08:00:00");
        insertReading(SCHEME, D4, 120, "2026-01-04T08:00:00");

        insertReading(GAP_SCHEME, D1, 500, "2026-01-01T08:00:00");
        insertReading(GAP_SCHEME, D2, 0, "2026-01-02T08:00:00");
        insertReading(GAP_SCHEME, D4, 560, "2026-01-04T08:00:00");
    }

    /** The value {@code FactServiceImpl.updateWaterQuantityFromReading} would derive for that row. */
    private Long liveValueFor(long factId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT tenant_id, scheme_id, date FROM analytics_schema.fact_water_quantity_table WHERE id = ?",
                factId);
        Integer tenantId = (Integer) row.get("tenant_id");
        Integer schemeId = (Integer) row.get("scheme_id");
        LocalDate date = ((java.sql.Date) row.get("date")).toLocalDate();

        Integer current = meterReadingRepository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(tenantId, schemeId, date)
                .map(FactMeterReading::getConfirmedReading)
                .orElse(null);
        if (current == null) {
            return null;
        }
        Integer previous = meterReadingRepository
                .findLatestBefore(tenantId, schemeId, date)
                .map(FactMeterReading::getConfirmedReading)
                .orElse(null);

        return calculator.calculate(WaterQuantityContext.builder()
                .tenantId(tenantId)
                .schemeId(schemeId)
                .readingDate(date)
                .currentReading(current)
                .previousReading(previous)
                .channel(ReadingChannel.BFM.getCode())
                .build());
    }

    /** Runs the shipped recompute definition verbatim and returns {@code id -> new_qty}. */
    private Map<Long, Long> runRecompute() {
        Map<Long, Long> byId = new HashMap<>();
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("WITH recompute AS (%s) SELECT id, new_qty FROM recompute"
                        .formatted(recomputeSql()));
        for (Map<String, Object> row : rows) {
            byId.put(((Number) row.get("id")).longValue(),
                    row.get("new_qty") == null ? null : ((Number) row.get("new_qty")).longValue());
        }
        return byId;
    }

    private boolean isLatest(long factId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "WITH recompute AS (%s) SELECT is_latest FROM recompute WHERE id = ?"
                        .formatted(recomputeSql()), Boolean.class, factId));
    }

    private static String recomputeSql() {
        try {
            return new String(new ClassPathResource("db/scripts/recompute_water_quantity.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("db/scripts/recompute_water_quantity.sql is missing", e);
        }
    }

    private void insertDate(LocalDate date) {
        int dateKey = Integer.parseInt(date.toString().replace("-", ""));
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, EXTRACT(DAY FROM ?::date), EXTRACT(MONTH FROM ?::date), TO_CHAR(?::date, 'FMMonth'),
                        EXTRACT(QUARTER FROM ?::date), EXTRACT(YEAR FROM ?::date), EXTRACT(WEEK FROM ?::date),
                        EXTRACT(ISODOW FROM ?::date) IN (6,7), EXTRACT(YEAR FROM ?::date))
                """, dateKey, date, date, date, date, date, date, date, date, date);
    }

    private void insertReading(int schemeId, LocalDate readingDate, int confirmedReading, String readingAt) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading,
                 reading_at, reading_date, submission_status, reading_type, created_at)
                VALUES (?, ?, 11, ?, ?, ?, ?, 1, 0, NOW())
                """, TENANT, schemeId, confirmedReading, confirmedReading,
                LocalDateTime.parse(readingAt), readingDate);
    }

    private long seedLegacyQuantityRow(int schemeId, LocalDate date, long waterQuantity) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, submission_status,
                 created_at, updated_at)
                VALUES (?, ?, 11, ?, ?, 1, NOW(), NOW())
                RETURNING id
                """, Long.class, TENANT, schemeId, waterQuantity, date);
    }

    private long idOf(int schemeId, LocalDate date) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM analytics_schema.fact_water_quantity_table
                WHERE tenant_id = ? AND scheme_id = ? AND date = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, Long.class, TENANT, schemeId, date);
    }
}
