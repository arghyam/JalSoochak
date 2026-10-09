package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.MeterRegister;
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
 * readings on its own channel and register, a row with no channel is a legacy BFM row, a row with no
 * unit is on the standard register, and a PDU day's minutes add up the scheme's PDU runs that day.
 */
@Testcontainers
class TelemetryTenantRepositoryChannelLookupIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    /** Has no {@code submitted_unit} column (pre-V56). */
    private static final String PRE_UNIT_SCHEMA = "tenant_zz";
    private static final String KWH = "kW.h";
    private static final String KVAH = "kV.A.h";
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
        jdbcTemplate.execute("DELETE FROM " + PRE_UNIT_SCHEMA + ".flow_reading_table");
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        repository = new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        today = ReadingTime.today();
    }

    private void insertReading(String reading, LocalDate day, ReadingChannel channel) {
        insertReading(reading, day, channel, null);
    }

    private void insertReading(String reading, LocalDate day, ReadingChannel channel, String submittedUnit) {
        LocalDateTime readingAt = day.atTime(6, 0);
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, submitted_unit, created_by) "
                        + "VALUES (?, ?, ?, 0, ?, 'corr-1', ?, ?, ?)",
                SCHEME, readingAt, day, new BigDecimal(reading), code(channel), submittedUnit, OPERATOR);
    }

    private BigDecimal latest(ReadingChannel channel) {
        return latest(SCHEMA, channel, MeterRegister.STANDARD);
    }

    private BigDecimal latest(String schema, ReadingChannel channel, MeterRegister register) {
        return repository.findLatestConfirmedReadingSnapshot(schema, SCHEME, channel, register, null)
                .map(TelemetryConfirmedReadingSnapshot::confirmedReading)
                .orElse(null);
    }

    @Test
    void theLatestReadingComesFromTheSameChannelOnly() {
        insertReading("100", today.minusDays(2), ReadingChannel.BFM);
        insertReading("5000", today.minusDays(1), ReadingChannel.ELM);

        assertThat(latest(ReadingChannel.BFM)).isEqualByComparingTo("100");
        assertThat(latest(ReadingChannel.ELM)).isEqualByComparingTo("5000");
        assertThat(repository.findLatestConfirmedReadingSnapshot(
                        SCHEMA, SCHEME, ReadingChannel.PDU, MeterRegister.STANDARD, null))
                .isEmpty();
        assertThat(repository.findLastConfirmedReading(
                        SCHEMA, SCHEME, ReadingChannel.BFM, MeterRegister.STANDARD, null))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("100"));
    }

    @Test
    void aRowWithNoChannelCountsAsBfm() {
        insertReading("100", today.minusDays(2), null);
        insertReading("5000", today.minusDays(1), ReadingChannel.ELM);

        assertThat(latest(ReadingChannel.BFM)).isEqualByComparingTo("100");
        assertThat(latest(ReadingChannel.ELM)).isEqualByComparingTo("5000");
    }

    @Test
    void theBaselineBeforeADateComesFromTheSameChannelOnly() {
        insertReading("100", today.minusDays(3), ReadingChannel.BFM);
        insertReading("5000", today.minusDays(2), ReadingChannel.ELM);

        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        SCHEMA, SCHEME, ReadingChannel.BFM, MeterRegister.STANDARD, today.minusDays(1), null))
                .hasValueSatisfying(s -> assertThat(s.confirmedReading()).isEqualByComparingTo("100"));
        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.STANDARD, today.minusDays(1), null))
                .hasValueSatisfying(s -> assertThat(s.confirmedReading()).isEqualByComparingTo("5000"));
    }

    @Test
    void theConsumptionBandHoldsOnlyTheSameChannelsDays() {
        insertReading("100", today.minusDays(3), ReadingChannel.BFM);
        insertReading("5000", today.minusDays(2), ReadingChannel.ELM);
        insertReading("101", today.minusDays(1), null);

        assertThat(repository.findRecentDailyConfirmedReadings(
                        SCHEMA, SCHEME, ReadingChannel.BFM, MeterRegister.STANDARD, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(1), today.minusDays(3));
        assertThat(repository.findRecentDailyConfirmedReadings(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.STANDARD, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(2));
    }

    @Test
    void theLatestReadingComesFromTheSameRegisterOnlySkippingBackPastTheOther() {
        insertReading("4800", today.minusDays(3), ReadingChannel.ELM, KWH);
        insertReading("5300", today.minusDays(2), ReadingChannel.ELM, KVAH);
        insertReading("4810", today.minusDays(1), ReadingChannel.ELM, null);
        insertReading("5320", today, ReadingChannel.ELM, KVAH);

        assertThat(latest(SCHEMA, ReadingChannel.ELM, MeterRegister.STANDARD)).isEqualByComparingTo("4810");
        assertThat(latest(SCHEMA, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY)).isEqualByComparingTo("5320");
        assertThat(repository.findLastConfirmedReading(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, null))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("5320"));
    }

    @Test
    void theBaselineBeforeADateComesFromTheSameRegisterOnly() {
        insertReading("4800", today.minusDays(3), ReadingChannel.ELM, KWH);
        insertReading("5300", today.minusDays(2), ReadingChannel.ELM, KVAH);
        insertReading("4810", today.minusDays(1), ReadingChannel.ELM, KWH);

        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, today, null))
                .hasValueSatisfying(s -> assertThat(s.confirmedReading()).isEqualByComparingTo("5300"));
        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.STANDARD, today.minusDays(1), null))
                .hasValueSatisfying(s -> assertThat(s.confirmedReading()).isEqualByComparingTo("4800"));
    }

    @Test
    void theConsumptionBandHoldsOnlyTheSameRegistersDays() {
        insertReading("4800", today.minusDays(3), ReadingChannel.ELM, KWH);
        insertReading("5300", today.minusDays(2), ReadingChannel.ELM, KVAH);
        insertReading("4810", today.minusDays(1), ReadingChannel.ELM, null);

        assertThat(repository.findRecentDailyConfirmedReadings(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.STANDARD, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(1), today.minusDays(3));
        assertThat(repository.findRecentDailyConfirmedReadings(
                        SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(2));
    }

    @Test
    void aSchemaWithNoUnitColumnHoldsStandardRegisterReadingsOnly() {
        jdbcTemplate.update("INSERT INTO " + PRE_UNIT_SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by) "
                        + "VALUES (?, ?, ?, 0, 4800, 'corr-1', ?, ?)",
                SCHEME, today.minusDays(1).atTime(6, 0), today.minusDays(1), ReadingChannel.ELM.getCode(), OPERATOR);

        assertThat(latest(PRE_UNIT_SCHEMA, ReadingChannel.ELM, MeterRegister.STANDARD)).isEqualByComparingTo("4800");
        assertThat(latest(PRE_UNIT_SCHEMA, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY)).isNull();
        assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        PRE_UNIT_SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, today, null))
                .isEmpty();
        assertThat(repository.findRecentDailyConfirmedReadings(
                        PRE_UNIT_SCHEMA, SCHEME, ReadingChannel.ELM, MeterRegister.STANDARD, null, 18))
                .extracting(DailyConfirmedReading::day)
                .containsExactly(today.minusDays(1));
    }

    private long insertRun(String minutes, LocalDate day, long scheme, ReadingChannel channel) {
        return jdbcTemplate.queryForObject("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by) "
                        + "VALUES (?, ?, ?, 0, ?, 'corr-1', ?, ?) RETURNING id",
                Long.class, scheme, day.atTime(6, 0), day, new BigDecimal(minutes), code(channel), OPERATOR);
    }

    private static Integer code(ReadingChannel channel) {
        return channel == null ? null : channel.getCode();
    }

    @Test
    void theDaysPduMinutesAddUpTheSchemesPduRunsThatDayOnly() {
        insertRun("300", today, SCHEME, ReadingChannel.PDU);
        insertRun("200.5", today, SCHEME, ReadingChannel.PDU);
        insertRun("900", today.minusDays(1), SCHEME, ReadingChannel.PDU);
        insertRun("900", today, 2L, ReadingChannel.PDU);
        insertRun("900", today, SCHEME, ReadingChannel.ELM);
        insertRun("900", today, SCHEME, null);
        long deleted = insertRun("900", today, SCHEME, ReadingChannel.PDU);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".flow_reading_table SET deleted_at = NOW() WHERE id = ?", deleted);

        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, today, null)).isEqualByComparingTo("500.5");
    }

    @Test
    void theDaysPduMinutesLeaveOutTheExcludedRow() {
        insertRun("300", today, SCHEME, ReadingChannel.PDU);
        long corrected = insertRun("200", today, SCHEME, ReadingChannel.PDU);

        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, today, corrected)).isEqualByComparingTo("300");
    }

    @Test
    void aDayWithNoPduRunsHasNoMinutes() {
        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, today, null)).isEqualByComparingTo("0");
    }
}
