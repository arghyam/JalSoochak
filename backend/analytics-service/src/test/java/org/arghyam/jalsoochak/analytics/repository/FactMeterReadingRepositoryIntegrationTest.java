package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the reading lookups the water-quantity recalculation depends on: the day's own reading, the
 * starting point strictly before it, the next date and a day's submissions. All are ordering-
 * sensitive against real SQL, which is why this is an integration test rather than a mocked one.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FactMeterReadingRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_meter_reading_test")
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
    private FactMeterReadingRepository repository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final int SCHEME = 1;
    private static final int OTHER_SCHEME = 2;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);
    private static final LocalDate D4 = LocalDate.of(2026, 1, 4);

    private static final Integer BFM = ReadingChannel.BFM.getCode();
    private static final Integer ELM = ReadingChannel.ELM.getCode();
    private static final Integer PDU = ReadingChannel.PDU.getCode();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
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
    void findLatestBefore_ignoresSameDayAndReturnsTheMostRecentEarlierReading() {
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00");
        insertReading(SCHEME, D2, "140", "2026-01-02T08:00:00");
        insertReading(SCHEME, D4, "175", "2026-01-04T08:00:00");

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.BFM))).isEqualByComparingTo("140");
        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D2, ReadingChannel.BFM))).isEqualByComparingTo("100");
    }

    @Test
    void findLatestBefore_afterAGapReturnsTheLastActualReadingNotTheMissingPreviousDay() {
        // Only D1 exists; D2 and D3 have no reading at all.
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00");

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.BFM))).isEqualByComparingTo("100");
    }

    @Test
    void findLatestBefore_whenNothingPrecedesTheDate_returnsEmpty() {
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00");

        assertThat(repository.findLatestBefore(TENANT, SCHEME, D1, ReadingChannel.BFM)).isEmpty();
    }

    @Test
    void findLatestBefore_skipsZeroReadingsAsBaselines() {
        // resetLatestConfirmedReadingByPhone (telemetry-service) writes genuine 0 readings. Taking one
        // as the baseline would make the next day's delta the entire cumulative meter index.
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00");
        insertReading(SCHEME, D2, "0", "2026-01-02T08:00:00");

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.BFM))).isEqualByComparingTo("100");
    }

    @Test
    void findLatestBefore_acceptsASubCubicMetreReadingAsABaseline() {
        // The > 0 filter exists to skip the genuine zeros that resetLatestConfirmedReadingByPhone
        // writes, not to skip small readings. 0.4 m3 used to round to 0 at publish time and get
        // skipped here, silently promoting an older reading to baseline.
        insertReading(SCHEME, D1, "0.4", "2026-01-01T08:00:00");

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D2, ReadingChannel.BFM)))
                .isEqualByComparingTo("0.4");
    }

    @Test
    void findLatestBefore_isScopedToTheScheme() {
        insertReading(OTHER_SCHEME, D2, "900", "2026-01-02T08:00:00");
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00");

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.BFM))).isEqualByComparingTo("100");
    }

    @Test
    void findLatestBefore_breaksTiesOnIdWhenTimestampsAreIdentical() {
        // A correction re-publishes with the *original* readingAt, so the corrected day holds two rows
        // with identical timestamps. The later write (highest id) is the one that counts.
        insertReading(SCHEME, D2, "140", "2026-01-02T08:00:00");
        insertReading(SCHEME, D2, "145", "2026-01-02T08:00:00");

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.BFM))).isEqualByComparingTo("145");
    }

    @Test
    void findTopByReadingDate_returnsTheLastRowOfTheDayAndBreaksTiesOnId() {
        insertReading(SCHEME, D2, "140", "2026-01-02T08:00:00");
        insertReading(SCHEME, D2, "150", "2026-01-02T17:30:00");
        // Corrected re-publish of the 08:00 reading: same timestamp, higher id, but an earlier
        // timestamp than the 17:30 row — which must still win.
        insertReading(SCHEME, D2, "141", "2026-01-02T08:00:00");

        Optional<FactMeterReading> latest = repository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(TENANT, SCHEME, D2);

        assertThat(readingAt(latest)).isEqualByComparingTo("150");
    }

    @Test
    void findTopByReadingDate_withIdenticalTimestampsPrefersTheLaterWrite() {
        insertReading(SCHEME, D2, "140", "2026-01-02T08:00:00");
        insertReading(SCHEME, D2, "141", "2026-01-02T08:00:00");

        Optional<FactMeterReading> latest = repository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(TENANT, SCHEME, D2);

        assertThat(readingAt(latest)).isEqualByComparingTo("141");
    }


    // ---- channel filters ---------------------------------------------------------------------

    @Test
    void findLatestBefore_countsALegacyNullChannelAsBfm() {
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00", null);

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D2, ReadingChannel.BFM)))
                .isEqualByComparingTo("100");
    }

    @Test
    void findLatestBefore_neverTakesAnotherChannelsReadingAsTheStartingPoint() {
        // An ELM kWh total sits between two BFM days: the BFM day after it still measures from BFM.
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00", BFM);
        insertReading(SCHEME, D2, "40", "2026-01-02T08:00:00", ELM);

        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.BFM)))
                .isEqualByComparingTo("100");
        assertThat(readingAt(repository.findLatestBefore(TENANT, SCHEME, D4, ReadingChannel.ELM)))
                .isEqualByComparingTo("40");
    }

    @Test
    void findNextReadingDate_returnsTheFirstLaterDateOnTheChannel() {
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00", BFM);
        insertReading(SCHEME, D2, "40", "2026-01-02T08:00:00", ELM);
        insertReading(SCHEME, D3, "0", "2026-01-03T08:00:00", null);
        insertReading(SCHEME, D4, "175", "2026-01-04T08:00:00", BFM);
        insertReading(OTHER_SCHEME, D2, "900", "2026-01-02T08:00:00", BFM);

        // D2 is ELM and OTHER_SCHEME is another scheme; D3's legacy NULL is BFM, zero reading or not.
        assertThat(repository.findNextReadingDate(TENANT, SCHEME, D1, BFM)).contains(D3);
        assertThat(repository.findNextReadingDate(TENANT, SCHEME, D3, BFM)).contains(D4);
        assertThat(repository.findNextReadingDate(TENANT, SCHEME, D1, ELM)).contains(D2);
        assertThat(repository.findNextReadingDate(TENANT, SCHEME, D4, BFM)).isEmpty();
    }

    @Test
    void existsOnAnotherChannelBetween_looksOnlyStrictlyBetweenTheDatesAndOnlyAtOtherChannels() {
        insertReading(SCHEME, D1, "40", "2026-01-01T17:00:00", ELM);
        insertReading(SCHEME, D2, "100", "2026-01-02T08:00:00", null);
        insertReading(SCHEME, D3, "30", "2026-01-03T08:00:00", PDU);
        insertReading(SCHEME, D4, "45", "2026-01-04T08:00:00", ELM);
        insertReading(OTHER_SCHEME, D2, "20", "2026-01-02T08:00:00", PDU);

        // D3's PDU run is another channel to both meter-index channels.
        assertThat(repository.existsOnAnotherChannelBetween(TENANT, SCHEME, D2, D4, BFM)).isTrue();
        assertThat(repository.existsOnAnotherChannelBetween(TENANT, SCHEME, D2, D4, ELM)).isTrue();
        // Between D1 and D3 there is only D2's legacy NULL, which is BFM. The ELM and PDU readings on
        // D1 and D3 themselves don't count, and neither does OTHER_SCHEME's run on D2.
        assertThat(repository.existsOnAnotherChannelBetween(TENANT, SCHEME, D1, D3, BFM)).isFalse();
        assertThat(repository.existsOnAnotherChannelBetween(TENANT, SCHEME, D1, D3, ELM)).isTrue();
    }

    @Test
    void findDayReadings_returnsTheDaysSubmissionsOnTheChannelOldestFirst() {
        insertReading(SCHEME, D2, "30", "2026-01-02T17:00:00", PDU);
        insertReading(SCHEME, D2, "90", "2026-01-02T06:00:00", PDU);
        insertReading(SCHEME, D2, "150", "2026-01-02T08:00:00", BFM);
        insertReading(SCHEME, D1, "45", "2026-01-01T06:00:00", PDU);

        assertThat(repository.findDayReadings(TENANT, SCHEME, D2, PDU))
                .extracting(FactMeterReading::getConfirmedReading)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("90"), new BigDecimal("30"));
    }

    @Test
    void findByReadingDate_returnsTheDaysReadingsOnEveryChannelLatestFirstAndBreaksTiesOnId() {
        insertReading(SCHEME, D2, "130", "2026-01-02T08:00:00", BFM);
        insertReading(SCHEME, D2, "40", "2026-01-02T17:00:00", ELM);
        insertReading(SCHEME, D2, "30", "2026-01-02T12:00:00", PDU);
        // A corrected re-publish of the 08:00 reading: same timestamp, higher id, so the later write.
        insertReading(SCHEME, D2, "131", "2026-01-02T08:00:00", null);
        insertReading(SCHEME, D1, "100", "2026-01-01T08:00:00", BFM);
        insertReading(OTHER_SCHEME, D2, "900", "2026-01-02T09:00:00", BFM);

        assertThat(repository.findByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(TENANT, SCHEME, D2))
                .extracting(FactMeterReading::getConfirmedReading)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("131"),
                        new BigDecimal("130"));
    }

    private void insertReading(int schemeId, LocalDate readingDate, String confirmedReading, String readingAt) {
        insertReading(schemeId, readingDate, confirmedReading, readingAt, null);
    }

    /** {@code confirmedReading} is text so the fixture states an exact NUMERIC rather than a double. */
    private void insertReading(int schemeId, LocalDate readingDate, String confirmedReading, String readingAt,
                               Integer channel) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading,
                 reading_at, reading_date, channel, submission_status, reading_type, created_at)
                VALUES (?, ?, 11, ?, ?, ?, ?, ?, 1, 0, NOW())
                """, TENANT, schemeId, new BigDecimal(confirmedReading), new BigDecimal(confirmedReading),
                LocalDateTime.parse(readingAt), readingDate, channel);
    }

    private static BigDecimal readingAt(Optional<FactMeterReading> reading) {
        return reading.map(FactMeterReading::getConfirmedReading).orElse(null);
    }
}
