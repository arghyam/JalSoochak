package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the one-row-per-submission upsert against the V56 partial unique index, and the per-scheme
 * advisory lock. {@code @DataJpaTest} rather than {@code @JdbcTest} so the snapshot can be read back
 * through the JPA entity, which is how the recalculation reads it.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FactIngestionRepository.class)
class FactIngestionRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_fact_ingestion_test")
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
    private FactIngestionRepository repository;

    @Autowired
    private FactMeterReadingRepository meterReadingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final int SCHEME = 1;
    private static final int OTHER_SCHEME = 2;
    private static final int OTHER_TENANT = 2;
    private static final int OTHER_TENANT_SCHEME = 3;

    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);

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
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW()),
                       (2, 'tr', 'Tripura', 'IN', 1, NOW(), NOW())
                """);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, parent_department_location_id,
                 operating_status, created_at, updated_at)
                VALUES (1, 1, 'Scheme A', 1001, 2001, 100, 200, 1, NOW(), NOW()),
                       (2, 1, 'Scheme B', 1002, 2002, 100, 200, 1, NOW(), NOW()),
                       (3, 2, 'Scheme C', 1003, 2003, 100, 200, 1, NOW(), NOW())
                """);
    }

    // ---- upsertMeterReading: one row per submission -----------------------------------------

    @Test
    void upsert_insertsANewSubmission() {
        Optional<Long> id = repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));

        assertThat(id).isPresent();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(confirmedReading(id.get())).isEqualByComparingTo("140");
    }

    @Test
    void upsert_aNewerVersionOverwritesTheSameRow() {
        long id = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140")).orElseThrow();

        Optional<Long> updated = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T09:30:00.000001", "145"));

        assertThat(updated).contains(id);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(confirmedReading(id)).isEqualByComparingTo("145");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_updated_at FROM analytics_schema.fact_meter_reading_table WHERE id = ?",
                LocalDateTime.class, id)).isEqualTo(LocalDateTime.parse("2026-01-02T09:30:00.000001"));
    }

    @Test
    void upsert_anOlderVersionIsRefusedAndChangesNothing() {
        long id = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T09:30:00", "145")).orElseThrow();

        Optional<Long> stale = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));

        assertThat(stale).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(confirmedReading(id)).isEqualByComparingTo("145");
    }

    @Test
    void upsert_replayingTheSameVersionIsHarmless() {
        long id = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140")).orElseThrow();

        Optional<Long> replay = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));

        assertThat(replay).contains(id);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void upsert_anEventWithoutAVersionNeverOverwritesAVersionedRow() {
        long id = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140")).orElseThrow();

        Optional<Long> unversioned = repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, null, "999"));

        assertThat(unversioned).isEmpty();
        assertThat(confirmedReading(id)).isEqualByComparingTo("140");
    }

    @Test
    void upsert_aRowStoredWithoutAVersionTakesTheNextVersion() {
        long id = repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, null, "140")).orElseThrow();

        Optional<Long> versioned = repository.upsertMeterReading(
                submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "141"));

        assertThat(versioned).contains(id);
        assertThat(confirmedReading(id)).isEqualByComparingTo("141");
    }

    @Test
    void upsert_aLegacyEventWithoutASourceIdIsAlwaysInserted() {
        repository.upsertMeterReading(submission(TENANT, SCHEME, null, null, "140"));
        repository.upsertMeterReading(submission(TENANT, SCHEME, null, null, "140"));

        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void upsert_theSourceIdIsScopedToTheTenant() {
        // flow_reading_table ids are per tenant schema, so two tenants can share one.
        repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));
        repository.upsertMeterReading(submission(OTHER_TENANT, OTHER_TENANT_SCHEME, 501L, "2026-01-02T08:00:00", "77"));

        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void upsert_storesTheSnapshotSoTheEntityReadsItBack() {
        CalculationParameters snapshot = new CalculationParameters(1, "F2", new BigDecimal("0.95"), List.of(
                new CalculationParameters.Pump(12L, new BigDecimal("500"), new BigDecimal("0.7"),
                        new BigDecimal("40"), new BigDecimal("7.5"), "HP", new BigDecimal("0.85"),
                        new BigDecimal("5"))));
        FactMeterReading reading = submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "40");
        reading.setChannel(ReadingChannel.ELM.getCode());
        reading.setCalculationParameters(snapshot);

        long id = repository.upsertMeterReading(reading).orElseThrow();

        assertThat(meterReadingRepository.findById(id)).get()
                .extracting(FactMeterReading::getCalculationParameters)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(snapshot);
    }

    // ---- findSchemeDay ----------------------------------------------------------------------

    @Test
    void findSchemeDay_isWhereTheSubmissionIsStored() {
        repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));

        assertThat(repository.findSchemeDay(TENANT, 501L)).contains(new FactIngestionRepository.SchemeDay(SCHEME, D2));
    }

    @Test
    void findSchemeDay_followsTheRowWhenANewerVersionMovesIt() {
        repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));
        FactMeterReading moved = submission(TENANT, OTHER_SCHEME, 501L, "2026-01-03T08:00:00", "140");
        moved.setReadingDate(D3);
        repository.upsertMeterReading(moved);

        assertThat(repository.findSchemeDay(TENANT, 501L))
                .contains(new FactIngestionRepository.SchemeDay(OTHER_SCHEME, D3));
    }

    @Test
    void findSchemeDay_isEmptyForASubmissionNotStoredInTheTenant() {
        repository.upsertMeterReading(submission(TENANT, SCHEME, 501L, "2026-01-02T08:00:00", "140"));

        assertThat(repository.findSchemeDay(TENANT, 502L)).isEmpty();
        assertThat(repository.findSchemeDay(OTHER_TENANT, 501L)).isEmpty();
    }

    // ---- lockScheme -------------------------------------------------------------------------

    @Test
    void lockScheme_holdsATransactionScopedAdvisoryLockInItsOwnNamespace() {
        repository.lockScheme(TENANT, SCHEME);

        assertThat(schemeLockHeld(TENANT, SCHEME)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockScheme_outsideATransactionFailsRatherThanGuardingNothing() {
        assertThatThrownBy(() -> repository.lockScheme(TENANT, SCHEME))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockSchemes_holdsEachSchemesLock() {
        repository.lockSchemes(TENANT, List.of(OTHER_SCHEME, SCHEME));

        assertThat(schemeLockHeld(TENANT, SCHEME)).isTrue();
        assertThat(schemeLockHeld(TENANT, OTHER_SCHEME)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockSchemes_outsideATransactionFailsRatherThanGuardingNothing() {
        assertThatThrownBy(() -> repository.lockSchemes(TENANT, List.of(SCHEME, OTHER_SCHEME)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private boolean schemeLockHeld(int tenantId, int schemeId) {
        // A two-int advisory lock shows in pg_locks as classid = first key, objid = second key,
        // objsubid = 2; both columns are oid, so compare them as unsigned.
        Integer held = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_locks
                WHERE locktype = 'advisory' AND objsubid = 2 AND granted
                  AND pid = pg_backend_pid()
                  AND classid::bigint = ? AND objid::bigint = ?
                """, Integer.class,
                Integer.toUnsignedLong(FactIngestionRepository.SCHEME_LOCK_NAMESPACE),
                Integer.toUnsignedLong(Objects.hash(tenantId, schemeId)));
        return held == 1;
    }

    private static FactMeterReading submission(int tenantId, int schemeId, Long sourceReadingId,
                                               String sourceUpdatedAt, String confirmedReading) {
        return FactMeterReading.builder()
                .tenantId(tenantId)
                .schemeId(schemeId)
                .userId(11)
                .confirmedReading(new BigDecimal(confirmedReading))
                .readingAt(LocalDateTime.parse("2026-01-02T08:00:00"))
                .channel(ReadingChannel.BFM.getCode())
                .readingDate(D2)
                .submissionStatus(1)
                .readingType(0)
                .createdAt(LocalDateTime.now())
                .sourceReadingId(sourceReadingId)
                .sourceUpdatedAt(sourceUpdatedAt == null ? null : LocalDateTime.parse(sourceUpdatedAt))
                .build();
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analytics_schema.fact_meter_reading_table",
                Integer.class);
    }

    private BigDecimal confirmedReading(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT confirmed_reading FROM analytics_schema.fact_meter_reading_table WHERE id = ?",
                BigDecimal.class, id);
    }
}
