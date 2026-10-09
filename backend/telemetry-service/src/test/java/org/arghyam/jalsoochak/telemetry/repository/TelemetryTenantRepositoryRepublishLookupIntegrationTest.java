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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TelemetryTenantRepository#findFlowReadingIdsForRepublish} against a real PostgreSQL instance:
 * the readings a backfill sends again, and the order it sends them in.
 */
@Testcontainers
class TelemetryTenantRepositoryRepublishLookupIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME = 1L;
    private static final long OTHER_SCHEME = 2L;
    private static final long OPERATOR = 9L;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 2);
    private static final Set<ReadingChannel> ELM_AND_PDU = EnumSet.of(ReadingChannel.ELM, ReadingChannel.PDU);

    private static final long ELM_TO_DAY_MORNING = 1L;
    private static final long PDU_FROM_DAY = 2L;
    private static final long PDU_TO_DAY_DAWN = 3L;
    private static final long OTHER_SCHEME_PDU = 4L;

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

        // Returned, and inserted out of order so the ORDER BY is what sorts them.
        insert(ELM_TO_DAY_MORNING, SCHEME, TO.atTime(9, 0), ReadingChannel.ELM, false);
        insert(PDU_FROM_DAY, SCHEME, FROM.atTime(18, 0), ReadingChannel.PDU, false);
        insert(PDU_TO_DAY_DAWN, SCHEME, TO.atTime(7, 0), ReadingChannel.PDU, false);
        insert(OTHER_SCHEME_PDU, OTHER_SCHEME, TO.atTime(12, 0), ReadingChannel.PDU, false);
        // Never returned: another channel, a row with no reading, a deleted row, and both edges.
        insert(5L, SCHEME, TO.atTime(10, 0), ReadingChannel.BFM, false);
        insert(6L, SCHEME, TO.atTime(11, 0), null, false);
        insert(7L, SCHEME, TO.atTime(13, 0), ReadingChannel.ELM, true);
        insert(8L, SCHEME, TO.plusDays(1).atTime(6, 0), ReadingChannel.ELM, false);
        insert(9L, SCHEME, FROM.minusDays(1).atTime(23, 59), ReadingChannel.PDU, false);
    }

    private static void insert(long id, long schemeId, LocalDateTime readingAt, ReadingChannel channel,
                               boolean deleted) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 0, 42, ?, ?, ?, ?)",
                id, schemeId, readingAt, readingAt.toLocalDate(), "corr-" + id,
                channel == null ? null : channel.getCode(), OPERATOR,
                deleted ? LocalDateTime.of(2026, 9, 3, 0, 0) : null);
    }

    private static TelemetryTenantRepository repository() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository repository =
                new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        return repository;
    }

    @Test
    void returnsTheRangesReadingsOldestFirst() {
        assertEquals(List.of(PDU_FROM_DAY, PDU_TO_DAY_DAWN, ELM_TO_DAY_MORNING, OTHER_SCHEME_PDU),
                repository().findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, null, ELM_AND_PDU));
    }

    @Test
    void narrowsToOneScheme() {
        assertEquals(List.of(PDU_FROM_DAY, PDU_TO_DAY_DAWN, ELM_TO_DAY_MORNING),
                repository().findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, SCHEME, ELM_AND_PDU));
    }

    @Test
    void narrowsToOneChannel() {
        assertEquals(List.of(ELM_TO_DAY_MORNING),
                repository().findFlowReadingIdsForRepublish(
                        SCHEMA, FROM, TO, null, EnumSet.of(ReadingChannel.ELM)));
    }

    @Test
    void returnsNothingForNoChannel() {
        assertEquals(List.of(),
                repository().findFlowReadingIdsForRepublish(
                        SCHEMA, FROM, TO, null, EnumSet.noneOf(ReadingChannel.class)));
    }
}
