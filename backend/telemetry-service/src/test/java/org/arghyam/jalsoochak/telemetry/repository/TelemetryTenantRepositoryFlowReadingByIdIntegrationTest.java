package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TelemetryTenantRepository#findFlowReadingById} against a real PostgreSQL instance: the row a
 * republish reads back must carry every field the published event is built from.
 */
@Testcontainers
class TelemetryTenantRepositoryFlowReadingByIdIntegrationTest {

    private static final String MIGRATED_SCHEMA = "tenant_as";
    private static final String PRE_MIGRATION_SCHEMA = "tenant_zz";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);
    private static final LocalDateTime READING_AT = DAY.atTime(6, 30);

    private static final long LIVE_ROW = 1L;
    private static final long DELETED_ROW = 2L;
    private static final long LEGACY_ROW = 3L;

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void seed() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update("INSERT INTO " + MIGRATED_SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, image_url, created_by, quarantine_reason) "
                        + "VALUES (?, ?, ?, ?, 118.5, 120.25, 'corr-live', 2, 'https://img/1.jpg', ?, 1)",
                LIVE_ROW, SCHEME, READING_AT, DAY, OPERATOR);
        jdbcTemplate.update("INSERT INTO " + MIGRATED_SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 0, 130, 'corr-deleted', 1, ?, NOW())",
                DELETED_ROW, SCHEME, READING_AT, DAY, OPERATOR);
        jdbcTemplate.update("INSERT INTO " + PRE_MIGRATION_SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, created_by) "
                        + "VALUES (?, ?, ?, ?, 0, 140, 'corr-legacy', ?)",
                LEGACY_ROW, SCHEME, READING_AT, DAY, OPERATOR);
    }

    private static TelemetryTenantRepository repository() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository repository =
                new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        return repository;
    }

    @Test
    void mapsEveryFieldOfTheRow() {
        TelemetryLatestFlowReadingRecord row =
                repository().findFlowReadingById(MIGRATED_SCHEMA, LIVE_ROW).orElseThrow();

        assertEquals(LIVE_ROW, row.id());
        assertEquals(SCHEME, row.schemeId());
        assertEquals(OPERATOR, row.createdBy());
        assertEquals("corr-live", row.correlationId());
        assertEquals(0, row.extractedReading().compareTo(new BigDecimal("118.5")));
        assertEquals(0, row.confirmedReading().compareTo(new BigDecimal("120.25")));
        assertEquals("https://img/1.jpg", row.imageUrl());
        assertEquals(DAY, row.readingDate());
        assertEquals(READING_AT, row.readingAt());
        assertEquals(ReadingChannel.ELM.getCode(), row.channel());
        assertEquals(1, row.quarantineReason());
        assertEquals(jdbcTemplate.queryForObject("SELECT updated_at FROM " + MIGRATED_SCHEMA
                + ".flow_reading_table WHERE id = ?", LocalDateTime.class, LIVE_ROW), row.updatedAt());
    }

    @Test
    void ignoresASoftDeletedRow() {
        assertTrue(repository().findFlowReadingById(MIGRATED_SCHEMA, DELETED_ROW).isEmpty());
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertTrue(repository().findFlowReadingById(MIGRATED_SCHEMA, 404L).isEmpty());
    }

    /** A pre-V40 schema has no quarantine column; the lookup must still work there. */
    @Test
    void readsARowFromAPreQuarantineSchema() {
        Optional<TelemetryLatestFlowReadingRecord> row =
                repository().findFlowReadingById(PRE_MIGRATION_SCHEMA, LEGACY_ROW);

        assertTrue(row.isPresent());
        assertEquals(0, row.get().confirmedReading().compareTo(new BigDecimal("140")));
        assertNull(row.get().channel());
        assertNull(row.get().quarantineReason());
    }
}
