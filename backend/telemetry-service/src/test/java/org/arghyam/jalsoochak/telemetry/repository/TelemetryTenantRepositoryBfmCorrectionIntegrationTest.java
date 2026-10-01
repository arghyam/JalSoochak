package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
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
 * The lookups behind the SO/SDO correction (PATCH {@code yesterday-final-reading}) against a real
 * PostgreSQL instance: the officer screens show BFM readings only, so the correction targets BFM rows
 * only.
 */
@Testcontainers
class TelemetryTenantRepositoryBfmCorrectionIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    private TelemetryTenantRepository repository;

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
    }

    private long insertReading(String reading, LocalDateTime readingAt, ReadingChannel channel) {
        return jdbcTemplate.queryForObject("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'corr-1', ?, ?) RETURNING id",
                Long.class,
                SCHEME, readingAt, readingAt.toLocalDate(),
                new BigDecimal(reading), new BigDecimal(reading),
                channel == null ? null : channel.getCode(), OPERATOR);
    }

    @Test
    void targetsTheLatestBfmReadingWhenALaterElmReadingExists() {
        long bfm = insertReading("100", DAY.atTime(6, 0), ReadingChannel.BFM);
        insertReading("5000", DAY.atTime(8, 0), ReadingChannel.ELM);

        assertThat(repository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME))
                .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo(bfm));
        assertThat(repository.findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME, DAY))
                .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo(bfm));
    }

    @Test
    void targetsALegacyNullChannelReadingAsBfm() {
        long legacy = insertReading("100", DAY.atTime(6, 0), null);

        assertThat(repository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME))
                .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo(legacy));
    }

    @Test
    void findsNoTargetOnASchemeWithOnlyElmReadings() {
        insertReading("5000", DAY.atTime(8, 0), ReadingChannel.ELM);

        assertThat(repository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME)).isEmpty();
        assertThat(repository.findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME, DAY)).isEmpty();
    }
}
