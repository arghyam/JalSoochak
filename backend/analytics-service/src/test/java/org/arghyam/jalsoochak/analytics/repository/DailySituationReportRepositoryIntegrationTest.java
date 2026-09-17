package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository.SchemeDaySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration tests for the officer-scoped queries behind the Daily Water Service
 * Situation Report, against the real Flyway migrations.
 *
 * <p>The cases that matter here are the ones a mock cannot catch: that a NOT_SUBMITTED row with a
 * positive volume is not supply, that a scheme fanned out across several mappings is counted and
 * summed once, and that a scheme with no {@code work_status} is excluded rather than silently
 * included.</p>
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(DailySituationReportRepository.class)
class DailySituationReportRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_test")
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
    private DailySituationReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final long OFFICER = 500L;
    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);
    private static final LocalDate PREV = DAY.minusDays(1);
    /** work_status 4 = HANDED_OVER, the only status the default filter admits. */
    private static final int HANDED_OVER = 4;

    /** The IST day [DAY 00:00, DAY+1 00:00) expressed as the UTC-naive instants anomalies are stored in. */
    private static final LocalDateTime DAY_START_UTC = DAY.atStartOfDay().minusHours(5).minusMinutes(30);
    private static final LocalDateTime DAY_END_UTC = DAY.plusDays(1).atStartOfDay().minusHours(5).minusMinutes(30);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE analytics_schema.anomaly_table, "
                + "analytics_schema.fact_meter_reading_table, "
                + "analytics_schema.fact_water_quantity_table, "
                + "analytics_schema.dim_user_scheme_mapping_table, "
                + "analytics_schema.dim_scheme_table, "
                + "analytics_schema.dim_tenant_table RESTART IDENTITY CASCADE");
        seed();
    }

    // ── scheme-day snapshots ────────────────────────────────────────────────────

    @Test
    @DisplayName("returns every handed-over scheme mapped to the officer, supplying or not")
    void listsAllOfficerSchemes() {
        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        // A scheme that did not supply must still be a row — its absence is the report's Section 2.
        assertThat(snapshots).extracting(SchemeDaySnapshot::schemeId).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a SUBMITTED day with positive volume counts as supply")
    void countsSubmittedPositiveVolumeAsSupply() {
        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(supplied(snapshots, 1)).isTrue();
    }

    @Test
    @DisplayName("a NOT_SUBMITTED outage day does not count as supply even with a positive volume")
    void excludesNotSubmittedRowsWithPositiveVolume() {
        // Scheme 2 carries an outage row whose water_quantity is stale but positive. Counting it would
        // report an outage as a supply day — the exact failure the canonical predicate exists to stop.
        insertWaterQuantity(2, 400_000, DAY, 0);

        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(supplied(snapshots, 2)).isFalse();
    }

    @Test
    @DisplayName("a zero-volume SUBMITTED day does not count as supply")
    void excludesZeroVolumeDays() {
        insertWaterQuantity(3, 0, DAY, 1);

        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(supplied(snapshots, 3)).isFalse();
    }

    @Test
    @DisplayName("a scheme fanned out across mappings is counted once and its households summed once")
    void collapsesSchemeFanOut() {
        // dim_scheme_table holds one row per LGD/department mapping. Without the DISTINCT ON this
        // scheme would appear three times and contribute 300 households instead of 100.
        insertSchemeRow(1, 100, HANDED_OVER, 101, 201);
        insertSchemeRow(1, 100, HANDED_OVER, 102, 202);

        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(snapshots).extracting(SchemeDaySnapshot::schemeId).containsExactly(1, 2, 3);
        assertThat(snapshots).filteredOn(s -> s.schemeId() == 1)
                .singleElement()
                .extracting(SchemeDaySnapshot::fhtc)
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("a scheme with a NULL work_status is excluded, not silently included")
    void excludesSchemesWithNoWorkStatus() {
        // "Total Schemes (Handed Over Schemes only)" is the denominator of the household percentages
        // on the same page, so an unclassified scheme must not slip into it.
        insertSchemeRow(4, 50, null, 400, 400);
        mapUser(OFFICER, 4);

        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(snapshots).extracting(SchemeDaySnapshot::schemeId).doesNotContain(4);
    }

    @Test
    @DisplayName("a scheme that is not handed over is excluded")
    void excludesSchemesNotHandedOver() {
        insertSchemeRow(5, 50, 1, 500, 500); // work_status 1 = Ongoing
        mapUser(OFFICER, 5);

        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(snapshots).extracting(SchemeDaySnapshot::schemeId).doesNotContain(5);
    }

    @Test
    @DisplayName("another officer's schemes are never included")
    void scopesToTheOfficer() {
        insertSchemeRow(6, 50, HANDED_OVER, 600, 600);
        mapUser(999L, 6);

        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(snapshots).extracting(SchemeDaySnapshot::schemeId).doesNotContain(6);
    }

    @Test
    @DisplayName("households come from fhtc_count")
    void reportsFhtcAsHouseholds() {
        List<SchemeDaySnapshot> snapshots = repository.listSchemeDaySnapshots(TENANT, OFFICER, DAY);

        assertThat(snapshots).filteredOn(s -> s.schemeId() == 3)
                .singleElement()
                .extracting(SchemeDaySnapshot::fhtc)
                .isEqualTo(0L);
    }

    // ── litres ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sums only the litres of qualifying supply days")
    void sumsSuppliedLitres() {
        insertWaterQuantity(2, 400_000, DAY, 0);   // outage: excluded
        insertWaterQuantity(3, 100_000, DAY, 1);   // supply: included

        long litres = repository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY);

        assertThat(litres).isEqualTo(600_000L);
    }

    @Test
    @DisplayName("counts a duplicated scheme-day once, taking the most recently updated row")
    void dedupesDuplicateWaterRows() {
        // A concurrent replay can leave two rows for one scheme-day; summing both would double the
        // day's volume and with it the LPCD.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '1 hour', 1)
                """, TENANT, 1, OFFICER, 900_000, DAY);

        long litres = repository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY);

        assertThat(litres).isEqualTo(900_000L);
    }

    @Test
    @DisplayName("returns zero litres when the officer has no schemes")
    void returnsZeroForUnknownOfficer() {
        assertThat(repository.sumWaterSuppliedOnDay(TENANT, 12345L, DAY)).isZero();
    }

    // ── anomalies ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("counts anomalies in the IST day, excluding soft-deleted and out-of-window rows")
    void countsAnomaliesInWindow() {
        assertThat(repository.countAnomalies(TENANT, OFFICER, DAY_START_UTC, DAY_END_UTC)).isEqualTo(2);
    }

    @Test
    @DisplayName("an anomaly just before the IST midnight boundary belongs to the previous day")
    void respectsTheIstDayBoundary() {
        // 18:29 UTC is 23:59 IST on PREV; 18:30 UTC is 00:00 IST on DAY. Getting this wrong shifts a
        // whole evening's anomalies into the wrong officer's report.
        insertAnomalyAt("edge-before", "5", 1, PREV.atTime(18, 29), false);
        insertAnomalyAt("edge-after", "5", 1, PREV.atTime(18, 30), false);

        assertThat(repository.countAnomalies(TENANT, OFFICER, DAY_START_UTC, DAY_END_UTC)).isEqualTo(3);
    }

    @Test
    @DisplayName("lists one row per scheme and anomaly type")
    void listsAnomaliesByScheme() {
        List<DailySituationReportRepository.SchemeAnomaly> rows =
                repository.listAnomaliesByScheme(TENANT, OFFICER, DAY_START_UTC, DAY_END_UTC);

        assertThat(rows).extracting(DailySituationReportRepository.SchemeAnomaly::schemeId,
                        DailySituationReportRepository.SchemeAnomaly::type)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, "5"),
                        org.assertj.core.groups.Tuple.tuple(2, "4"));
    }

    @Test
    @DisplayName("collapses repeated occurrences of one type on one scheme into a single row")
    void dedupesRepeatedAnomalyTypes() {
        // The section lists what went wrong where, not how many times — three unreadable images on
        // one scheme is one line, while the summary count still reports three.
        insertAnomalyAt("dup1", "5", 1, DAY.atTime(11, 0), false);

        List<DailySituationReportRepository.SchemeAnomaly> rows =
                repository.listAnomaliesByScheme(TENANT, OFFICER, DAY_START_UTC, DAY_END_UTC);

        assertThat(rows).hasSize(2);
        assertThat(repository.countAnomalies(TENANT, OFFICER, DAY_START_UTC, DAY_END_UTC)).isEqualTo(3);
    }

    @Test
    @DisplayName("anomalies on a scheme that is not handed over are excluded")
    void excludesAnomaliesOnNonHandedOverSchemes() {
        insertSchemeRow(7, 50, 1, 700, 700);
        mapUser(OFFICER, 7);
        insertAnomalyAt("other", "5", 7, DAY.atTime(10, 0), false);

        assertThat(repository.countAnomalies(TENANT, OFFICER, DAY_START_UTC, DAY_END_UTC)).isEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static boolean supplied(List<SchemeDaySnapshot> snapshots, int schemeId) {
        return snapshots.stream()
                .filter(s -> s.schemeId() == schemeId)
                .findFirst()
                .map(SchemeDaySnapshot::supplied)
                .orElseThrow(() -> new AssertionError("scheme " + schemeId + " missing from snapshots"));
    }

    private void seed() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, TENANT, "mp", "Madhya Pradesh", "IN", 1, 55, 5);

        insertSchemeRow(1, 100, HANDED_OVER, 100, 200);
        insertSchemeRow(2, 100, HANDED_OVER, 100, 200);
        insertSchemeRow(3, 0, HANDED_OVER, 100, 200);

        mapUser(OFFICER, 1);
        mapUser(OFFICER, 2);
        mapUser(OFFICER, 3);

        // fact_water_quantity_table.date has an FK to dim_date_table.full_date.
        for (int i = 0; i <= 13; i++) {
            insertDate(DAY.minusDays(i));
        }

        insertWaterQuantity(1, 500_000, DAY, 1);   // scheme 1 supplies

        insertAnomalyAt("a1", "5", 1, DAY.atTime(10, 0), false);
        insertAnomalyAt("a2", "4", 2, DAY.atTime(10, 0), false);
        insertAnomalyAt("a3", "6", 1, DAY.atTime(10, 0), true);      // soft-deleted → excluded
        insertAnomalyAt("a4", "5", 1, PREV.atTime(10, 0), false);    // outside the DAY window
    }

    private void insertSchemeRow(int schemeId, int fhtc, Integer workStatus, int parentLgd, int parentDept) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id,
                 level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id,
                 level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, work_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0.0, 0.0, ?, ?, 0, 0, 0, 0, 0, ?, ?, 0, 0, 0, 0, 0,
                        1, ?, ?, ?, ?, NOW(), NOW())
                """, schemeId, TENANT, "Scheme " + schemeId, 1000 + schemeId, 2000 + schemeId,
                parentLgd, parentLgd, parentDept, parentDept,
                workStatus, fhtc, fhtc, fhtc);
    }

    private void mapUser(long userId, int schemeId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (gen_random_uuid(), ?, ?, ?, NULL, NOW(), NOW(), 1)
                """, TENANT, userId, schemeId);
    }

    private void insertDate(LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (date_key) DO NOTHING
                """,
                Integer.parseInt(date.toString().replace("-", "")), date,
                date.getDayOfMonth(), date.getMonthValue(), date.getMonth().name(),
                (date.getMonthValue() - 1) / 3 + 1, date.getYear(),
                date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()),
                date.getDayOfWeek().getValue() >= 6, date.getYear());
    }

    private void insertWaterQuantity(int schemeId, int litres, LocalDate date, int submissionStatus) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?)
                """, TENANT, schemeId, OFFICER, litres, date, submissionStatus);
    }

    private void insertAnomalyAt(String uuid, String type, int schemeId, LocalDateTime createdAtUtc, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.anomaly_table
                (uuid, type, scheme_id, tenant_id, status, created_at, deleted_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, uuid, type, schemeId, TENANT, createdAtUtc,
                deleted ? createdAtUtc.plusHours(1) : null);
    }
}
