package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lookups by channel against a real PostgreSQL instance. A reading is only ever compared with
 * readings on its own channel, a row with no channel is a legacy BFM row, and a PDU day's minutes add
 * up the scheme's PDU runs that day.
 */
@Testcontainers
class TelemetryTenantRepositoryChannelLookupIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    private TelemetryTenantRepository repository;

    /** Relative to today, because the consumption band is a window back from today. */
    private LocalDate today;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM " + SCHEMA + ".flow_reading_table");
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        repository = new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        today = ReadingTime.today();
    }

    private void insertReading(String reading, LocalDate day, String channel) {
        LocalDateTime readingAt = day.atTime(6, 0);
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel, created_by) "
                        + "VALUES (?, ?, ?, 0, ?, 'corr-1', ?, ?)",
                SCHEME, readingAt, day, new BigDecimal(reading), channel, OPERATOR);
    }

    private BigDecimal latest(ReadingChannel channel) {
        return repository.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME, channel, null)
                .map(TelemetryConfirmedReadingSnapshot::confirmedReading)
                .orElse(null);
    }

    @Test
    void theLatestReadingComesFromTheSameChannelOnly() {
        insertReading("100", today.minusDays(2), "BFM");
        insertReading("5000", today.minusDays(1), "ELM");

        assertThat(latest(ReadingChannel.BFM)).isEqualByComparingTo("100");
        assertThat(latest(ReadingChannel.ELM)).isEqualByComparingTo("5000");
        assertThat(repository.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME, ReadingChannel.PDU, null))
                .isEmpty();
        assertThat(repository.findLastConfirmedReading(SCHEMA, SCHEME, ReadingChannel.BFM, null))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("100"));
    }

    @Test
    void aRowWithNoChannelCountsAsBfm() {
        insertReading("100", today.minusDays(2), null);
        insertReading("5000", today.minusDays(1), "ELM");

        assertThat(latest(ReadingChannel.BFM)).isEqualByComparingTo("100");
        assertThat(latest(ReadingChannel.ELM)).isEqualByComparingTo("5000");
    }

    @Test
    void theBaselineBeforeADateComesFromTheSameChannelOnly() {
        insertReading("100", today.minusDays(3), "BFM");
        insertReading("5000", today.minusDays(2), "ELM");

        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        SCHEMA, SCHEME, ReadingChannel.BFM, today.minusDays(1), null))
                .hasValueSatisfying(s -> assertThat(s.confirmedReading()).isEqualByComparingTo("100"));
        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        SCHEMA, SCHEME, ReadingChannel.ELM, today.minusDays(1), null))
                .hasValueSatisfying(s -> assertThat(s.confirmedReading()).isEqualByComparingTo("5000"));
    }

    @Test
    void theConsumptionBandHoldsOnlyTheSameChannelsDays() {
        insertReading("100", today.minusDays(3), "BFM");
        insertReading("5000", today.minusDays(2), "ELM");
        insertReading("101", today.minusDays(1), null);

        assertThat(repository.findRecentDailyConfirmedReadings(SCHEMA, SCHEME, ReadingChannel.BFM, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(1), today.minusDays(3));
        assertThat(repository.findRecentDailyConfirmedReadings(SCHEMA, SCHEME, ReadingChannel.ELM, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(2));
    }

    private long insertRun(String minutes, LocalDate day, long scheme, String channel) {
        return jdbcTemplate.queryForObject("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel, created_by) "
                        + "VALUES (?, ?, ?, 0, ?, 'corr-1', ?, ?) RETURNING id",
                Long.class, scheme, day.atTime(6, 0), day, new BigDecimal(minutes), channel, OPERATOR);
    }

    @Test
    void theDaysPduMinutesAddUpTheSchemesPduRunsThatDayOnly() {
        insertRun("300", today, SCHEME, "PDU");
        insertRun("200.5", today, SCHEME, "PDU");
        insertRun("900", today.minusDays(1), SCHEME, "PDU");
        insertRun("900", today, 2L, "PDU");
        insertRun("900", today, SCHEME, "ELM");
        insertRun("900", today, SCHEME, null);
        long deleted = insertRun("900", today, SCHEME, "PDU");
        jdbcTemplate.update("UPDATE " + SCHEMA + ".flow_reading_table SET deleted_at = NOW() WHERE id = ?", deleted);

        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, today, null)).isEqualByComparingTo("500.5");
    }

    @Test
    void theDaysPduMinutesLeaveOutTheExcludedRow() {
        insertRun("300", today, SCHEME, "PDU");
        long corrected = insertRun("200", today, SCHEME, "PDU");

        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, today, corrected)).isEqualByComparingTo("300");
    }

    @Test
    void aDayWithNoPduRunsHasNoMinutes() {
        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, today, null)).isEqualByComparingTo("0");
    }
}
