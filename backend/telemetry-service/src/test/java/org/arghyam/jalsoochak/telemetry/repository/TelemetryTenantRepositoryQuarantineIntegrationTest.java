package org.arghyam.jalsoochak.telemetry.repository;

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
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SUPPLY-PLAUSIBILITY: integration tests for the {@code quarantine_reason} marker (V40) against a
 * real PostgreSQL instance.
 *
 * <p>The whole point of the marker is which rows the <em>database</em> hands back, so the exclusion
 * has to hold in SQL rather than only in the generated text the sibling
 * {@code TelemetryTenantRepositoryReadTest} can assert. Three things are proven here:
 *
 * <ul>
 *   <li>a quarantined row never becomes a baseline, in any of the three queries that produce one;</li>
 *   <li>it stays reachable by the lookups the correction and placeholder-reuse paths depend on —
 *       otherwise a correction could not release it and a second same-day submission would insert a
 *       duplicate;</li>
 *   <li>a schema that predates V40 behaves exactly as it did before, since the guarded SQL omits the
 *       filter entirely there.</li>
 * </ul>
 */
@Testcontainers
class TelemetryTenantRepositoryQuarantineIntegrationTest {

    private static final String MIGRATED_SCHEMA = "tenant_as";
    private static final String PRE_MIGRATION_SCHEMA = "tenant_zz";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;

    private static final LocalDate DAY_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 3, 2);
    private static final LocalDate DAY_3 = LocalDate.of(2026, 3, 3);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static final PiiEncryptionService PII = newPiiEncryptionService();

    private static JdbcTemplate jdbcTemplate;

    private static PiiEncryptionService newPiiEncryptionService() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new PiiEncryptionService(key, key);
    }

    @BeforeAll
    static void seed() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);

        insertScheme(MIGRATED_SCHEMA, SCHEME, 120, 150, 200);
        insertScheme(PRE_MIGRATION_SCHEMA, SCHEME, 10, 20, 30);

        // Day 1 and day 2 are ordinary readings; day 3 — the most recent — is quarantined, so every
        // baseline must fall back to day 2's 220.
        insertReading(MIGRATED_SCHEMA, 1, DAY_1, "100", "corr-day1", 0);
        insertReading(MIGRATED_SCHEMA, 2, DAY_2, "220", "corr-day2", 0);
        insertReading(MIGRATED_SCHEMA, 3, DAY_3, "99999", "corr-day3", 1);

        // The pre-V40 schema has the same shape minus the column.
        insertPreMigrationReading(4, DAY_1, "100", "legacy-day1");
        insertPreMigrationReading(5, DAY_2, "220", "legacy-day2");
    }

    private static void insertScheme(String schemaName, long id, int fhtc, int planned, int households) {
        jdbcTemplate.update("INSERT INTO " + schemaName + ".scheme_master_table "
                        + "(id, fhtc_count, planned_fhtc, house_hold_count) VALUES (?, ?, ?, ?)",
                id, fhtc, planned, households);
    }

    private static void insertReading(String schemaName,
                                      long id,
                                      LocalDate readingDate,
                                      String confirmedReading,
                                      String correlationId,
                                      int quarantineReason) {
        jdbcTemplate.update("INSERT INTO " + schemaName + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel, created_by, quarantine_reason) "
                        + "VALUES (?, ?, ?, ?, 0, ?, ?, 'BFM', ?, ?)",
                id, SCHEME, readingDate.atTime(6, 0), readingDate,
                new BigDecimal(confirmedReading), correlationId, OPERATOR, quarantineReason);
    }

    private static void insertPreMigrationReading(long id,
                                                  LocalDate readingDate,
                                                  String confirmedReading,
                                                  String correlationId) {
        jdbcTemplate.update("INSERT INTO " + PRE_MIGRATION_SCHEMA + ".flow_reading_table "
                        + "(id, scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel, created_by) "
                        + "VALUES (?, ?, ?, ?, 0, ?, ?, 'BFM', ?)",
                id, SCHEME, readingDate.atTime(6, 0), readingDate,
                new BigDecimal(confirmedReading), correlationId, OPERATOR);
    }

    private TelemetryTenantRepository repository() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, PII);
        // Each test builds a fresh repository, so the per-instance metadata cache starts empty and the
        // column probes run against the container rather than a stale answer.
        repository.invalidateMetadataCaches();
        return repository;
    }

    @Test
    void reportsThatTheMigratedSchemaSupportsQuarantine() {
        assertTrue(repository().supportsQuarantine(MIGRATED_SCHEMA));
        assertTrue(!repository().supportsQuarantine(PRE_MIGRATION_SCHEMA),
                "a pre-V40 schema must report no support, so callers skip the check instead of "
                        + "storing an unmarked quarantined row");
    }

    @Test
    void quarantinedRowIsNotTheLatestBaseline() {
        Optional<TelemetryConfirmedReadingSnapshot> snapshot =
                repository().findLatestConfirmedReadingSnapshot(MIGRATED_SCHEMA, SCHEME, null);

        assertTrue(snapshot.isPresent());
        assertEquals(0, snapshot.get().confirmedReading().compareTo(new BigDecimal("220")),
                "day 3 is quarantined, so day 2 remains the baseline");
    }

    @Test
    void quarantinedRowIsNotTheBaselineBeforeALaterDate() {
        Optional<TelemetryConfirmedReadingSnapshot> snapshot = repository()
                .findLatestConfirmedReadingSnapshotBeforeDate(MIGRATED_SCHEMA, SCHEME, DAY_3.plusDays(1), null);

        assertTrue(snapshot.isPresent());
        assertEquals(0, snapshot.get().confirmedReading().compareTo(new BigDecimal("220")));
    }

    @Test
    void quarantinedRowIsNotInTheConsumptionBand() {
        // The band drives rollover resolution: a quarantined value must not shape which candidate the
        // resolver picks. The window is relative to today, so seed dates are reached with a wide span.
        List<DailyConfirmedReading> band = repository()
                .findRecentDailyConfirmedReadings(MIGRATED_SCHEMA, SCHEME, null, 20000);

        assertTrue(band.stream().noneMatch(r -> r.day().equals(DAY_3)),
                "the quarantined day must not appear in the consumption band");
        assertTrue(band.stream().anyMatch(r -> r.day().equals(DAY_2)));
    }

    @Test
    void quarantinedRowStaysReachableByCorrelationId() {
        // The correction endpoint has to be able to reach the quarantined row — that is the release path.
        Optional<TelemetryLatestFlowReadingRecord> reading =
                repository().findFlowReadingDetailsByCorrelationId(MIGRATED_SCHEMA, "corr-day3");

        assertTrue(reading.isPresent());
        assertEquals(3L, reading.get().id());
        assertEquals(1, reading.get().quarantineReason(),
                "the marker rides along so a refused correction can name the right reason");
    }

    @Test
    void quarantinedRowStaysReachableAsTheOperatorsLatestReading() {
        Optional<TelemetryLatestFlowReadingRecord> reading =
                repository().findLatestFlowReadingByOperator(MIGRATED_SCHEMA, OPERATOR);

        assertTrue(reading.isPresent());
        assertEquals(3L, reading.get().id(),
                "the phone-resolved correction path must still find the quarantined row");
    }

    @Test
    void applyQuarantineReasonSetsAndClearsTheMarker() {
        TelemetryTenantRepository repository = repository();
        insertReading(MIGRATED_SCHEMA, 10, LocalDate.of(2026, 4, 1), "500", "corr-release", 1);

        assertEquals(1, storedQuarantineReason(10L));
        assertTrue(repository.findLatestConfirmedReadingSnapshot(MIGRATED_SCHEMA, SCHEME, null)
                .filter(s -> s.confirmedReading().compareTo(new BigDecimal("500")) == 0)
                .isEmpty(), "while quarantined the row must not be a baseline");

        repository.applyQuarantineReason(MIGRATED_SCHEMA, 10L, 0);

        assertEquals(0, storedQuarantineReason(10L));
        assertEquals(0, repository.findLatestConfirmedReadingSnapshot(MIGRATED_SCHEMA, SCHEME, null)
                        .orElseThrow().confirmedReading().compareTo(new BigDecimal("500")),
                "clearing the marker releases the row into the baseline — this is what a passing "
                        + "correction does");

        jdbcTemplate.update("DELETE FROM " + MIGRATED_SCHEMA + ".flow_reading_table WHERE id = 10");
    }

    @Test
    void preMigrationSchemaKeepsItsLegacyBehaviour() {
        TelemetryTenantRepository repository = repository();

        assertEquals(0, repository.findLatestConfirmedReadingSnapshot(PRE_MIGRATION_SCHEMA, SCHEME, null)
                .orElseThrow().confirmedReading().compareTo(new BigDecimal("220")));
        assertTrue(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                        PRE_MIGRATION_SCHEMA, SCHEME, DAY_3, null)
                .isPresent());
        assertEquals(null,
                repository.findFlowReadingDetailsByCorrelationId(PRE_MIGRATION_SCHEMA, "legacy-day2")
                        .orElseThrow().quarantineReason(),
                "a schema without the column reads NULL rather than failing the lookup");
    }

    @Test
    void findSchemeSupplyCountsReadsTheConnectionCounts() {
        assertEquals(new TelemetrySchemeSupplyCounts(120, 150, 200),
                repository().findSchemeSupplyCounts(MIGRATED_SCHEMA, SCHEME).orElseThrow());
        assertTrue(repository().findSchemeSupplyCounts(MIGRATED_SCHEMA, 4242L).isEmpty());
    }

    private static int storedQuarantineReason(long readingId) {
        Integer reason = jdbcTemplate.queryForObject(
                "SELECT quarantine_reason FROM " + MIGRATED_SCHEMA + ".flow_reading_table WHERE id = ?",
                Integer.class, readingId);
        return reason == null ? -1 : reason;
    }
}
