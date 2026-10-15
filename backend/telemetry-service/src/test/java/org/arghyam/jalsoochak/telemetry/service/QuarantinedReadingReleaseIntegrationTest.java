package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SUPPLY-PLAUSIBILITY: the quarantine release against a real PostgreSQL instance, with the real
 * repository, guard and republisher; only Kafka and the tenant config are stubbed.
 *
 * <p>Every scheme has 100 connections x 5 persons, so the check allows 75 m&sup3; a day. Seeded:
 *
 * <ul>
 *   <li>scheme 1, stuck by the one-day ceiling: accepted 1000 on the 1st, then 1120 on the 3rd
 *       (60 m&sup3; a day over two days), 1180 and 1240 on the 4th and 5th, all quarantined;</li>
 *   <li>scheme 2, a genuine misread: accepted 500 on the 1st, 5000 quarantined on the 2nd, then 560
 *       accepted on the 3rd;</li>
 *   <li>scheme 3, where a release changes the next reading's verdict: accepted 1000 on the 1st, then
 *       1010 and 1140 quarantined on the 2nd and 3rd. 1140 is plausible measured from the 1st (140
 *       over two days) but not from 1010 once that is released (130 in one day).</li>
 * </ul>
 *
 * <p>Scheme 1's readings and scheme 3's 1010 are released; scheme 2's 5000 and scheme 3's 1140 are
 * not.
 */
@Testcontainers
class QuarantinedReadingReleaseIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 22;
    private static final long OPERATOR = 9L;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactionTemplate;

    private TelemetryEventPublisher telemetryEventPublisher;
    private QuarantinedReadingReleaseService releaseService;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // What the release resolves the tenant and a named scheme by, beyond the shared test schema.
        jdbcTemplate.execute("CREATE TABLE common_schema.tenant_master_table (id INTEGER PRIMARY KEY, state_code TEXT)");
        jdbcTemplate.update("INSERT INTO common_schema.tenant_master_table (id, state_code) VALUES (?, 'AS')", TENANT_ID);
        jdbcTemplate.execute("ALTER TABLE " + SCHEMA + ".scheme_master_table "
                + "ADD COLUMN state_scheme_id TEXT, ADD COLUMN deleted_at TIMESTAMP");
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM " + SCHEMA + ".flow_reading_table");
        jdbcTemplate.update("DELETE FROM " + SCHEMA + ".scheme_master_table");
        for (long scheme = 1; scheme <= 3; scheme++) {
            jdbcTemplate.update("INSERT INTO " + SCHEMA + ".scheme_master_table "
                    + "(id, fhtc_count, planned_fhtc, house_hold_count) VALUES (?, 100, 0, 0)", scheme);
        }

        reading(11, 1, 1, "1000", 0);
        reading(12, 1, 3, "1120", 1);
        reading(13, 1, 4, "1180", 1);
        reading(14, 1, 5, "1240", 1);

        reading(21, 2, 1, "500", 0);
        reading(22, 2, 2, "5000", 1);
        reading(23, 2, 3, "560", 0);

        reading(31, 3, 1, "1000", 0);
        reading(32, 3, 2, "1010", 1);
        reading(33, 3, 3, "1140", 1);

        // Never picked up: deleted, on another channel, or outside the range.
        reading(41, 1, 6, "1300", 1);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".flow_reading_table SET deleted_at = NOW() WHERE id = 41");
        reading(42, 1, 7, "1360", 1);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".flow_reading_table SET channel_id = 2 WHERE id = 42");
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by, quarantine_reason) "
                        + "VALUES (43, 1, ?, ?, 0, 1420, 'corr-43', 1, ?, 1)",
                LocalDate.of(2026, 10, 1).atTime(9, 30), LocalDate.of(2026, 10, 1), OPERATOR);

        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, newPiiEncryptionService());
        repository.invalidateMetadataCaches();
        telemetryEventPublisher = mock(TelemetryEventPublisher.class);
        when(telemetryEventPublisher.publishMeterReadingRecordedAndAwait(any())).thenReturn(true);
        TenantConfigRepository tenantConfigRepository = mock(TenantConfigRepository.class);
        releaseService = new QuarantinedReadingReleaseService(
                repository,
                tenantConfigRepository,
                SupplyPlausibilityFixtures.guard(SupplyPlausibilityProperties.Mode.AUDIT, repository, tenantConfigRepository),
                new ReadingRepublisher(repository, telemetryEventPublisher, mock(CalculationParametersSnapshotter.class)),
                transactionTemplate);
    }

    private static PiiEncryptionService newPiiEncryptionService() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new PiiEncryptionService(key, key);
    }

    private static void reading(long id, long scheme, int dayOfSeptember, String confirmed, int quarantineReason) {
        LocalDate day = LocalDate.of(2026, 9, dayOfSeptember);
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by, quarantine_reason) "
                        + "VALUES (?, ?, ?, ?, 0, ?, ?, 1, ?, ?)",
                id, scheme, day.atTime(9, 30), day, new BigDecimal(confirmed), "corr-" + id, OPERATOR,
                quarantineReason);
    }

    private static List<Long> quarantinedIds() {
        return jdbcTemplate.queryForList("SELECT id FROM " + SCHEMA + ".flow_reading_table "
                + "WHERE quarantine_reason <> 0 ORDER BY id", Long.class);
    }

    private List<Long> publishedIds() {
        ArgumentCaptor<MeterReadingEvent> events = ArgumentCaptor.forClass(MeterReadingEvent.class);
        verify(telemetryEventPublisher, atLeastOnce()).publishMeterReadingRecordedAndAwait(events.capture());
        return events.getAllValues().stream().map(MeterReadingEvent::getSourceReadingId).toList();
    }

    @Test
    void releasesWhatTheCheckNowAcceptsOldestFirstAndKeepsTheRest() {
        QuarantinedReadingReleaseService.Outcome outcome =
                releaseService.release(TENANT_ID, FROM, TO, null, null, false);

        assertThat(outcome).isEqualTo(new QuarantinedReadingReleaseService.Outcome(4, 2, 0));
        assertThat(quarantinedIds()).containsExactly(22L, 33L, 41L, 42L, 43L);
        assertThat(publishedIds()).containsExactly(32L, 12L, 13L, 14L);
    }

    @Test
    void narrowsToTheOneSchemeNamed() {
        jdbcTemplate.update("UPDATE " + SCHEMA + ".scheme_master_table SET state_scheme_id = 'S-3' WHERE id = 3");

        QuarantinedReadingReleaseService.Outcome outcome =
                releaseService.release(TENANT_ID, FROM, TO, "S-3", null, false);

        assertThat(outcome).isEqualTo(new QuarantinedReadingReleaseService.Outcome(1, 1, 0));
        assertThat(publishedIds()).containsExactly(32L);
    }

    /** The counts are the release's own — 1140 is judged after 1010 is released — and nothing is kept. */
    @Test
    void dryRunGivesTheReleasesCountsAndChangesNothing() {
        QuarantinedReadingReleaseService.Outcome outcome =
                releaseService.release(TENANT_ID, FROM, TO, null, null, true);

        assertThat(outcome).isEqualTo(new QuarantinedReadingReleaseService.Outcome(4, 2, 0));
        assertThat(quarantinedIds()).containsExactly(12L, 13L, 14L, 22L, 32L, 33L, 41L, 42L, 43L);
        verify(telemetryEventPublisher, never()).publishMeterReadingRecordedAndAwait(any());

        assertThat(releaseService.release(TENANT_ID, FROM, TO, null, null, false)).isEqualTo(outcome);
    }

    @Test
    void releasingTheSameRangeAgainFindsNothingNewToRelease() {
        releaseService.release(TENANT_ID, FROM, TO, null, null, false);

        assertThat(releaseService.release(TENANT_ID, FROM, TO, null, null, false))
                .isEqualTo(new QuarantinedReadingReleaseService.Outcome(0, 2, 0));
    }
}
