package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.repository.WeeklySituationReportRepository.SchemeWeekSnapshot;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration tests for the single per-scheme query behind both weekly report
 * variants, against the real Flyway migrations.
 *
 * <p>The cases worth a real database: that supply days count distinct days rather than rows, that an
 * outage day is not one of them, that a scheme with no supply at all still returns a row, and that
 * the supervisor narrowing actually restricts an officer to shared schemes.</p>
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(WeeklySituationReportRepository.class)
class WeeklySituationReportRepositoryIntegrationTest {

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
    private WeeklySituationReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final long OFFICER = 500L;
    private static final long SDO = 900L;
    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 1);  // Monday
    private static final LocalDate WEEK_END = LocalDate.of(2026, 6, 7);    // Sunday
    private static final int HANDED_OVER = 4;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE analytics_schema.fact_water_quantity_table, "
                + "analytics_schema.dim_user_scheme_mapping_table, "
                + "analytics_schema.dim_scheme_table, "
                + "analytics_schema.dim_tenant_table RESTART IDENTITY CASCADE");
        seed();
    }

    @Test
    @DisplayName("counts distinct supply days, not rows")
    void countsDistinctSupplyDays() {
        insertWaterQuantity(1, 10_000, WEEK_START, 1);
        insertWaterQuantity(1, 10_000, WEEK_START.plusDays(1), 1);
        insertWaterQuantity(1, 10_000, WEEK_START.plusDays(2), 1);

        assertThat(snapshot(1).supplyDays()).isEqualTo(3);
        assertThat(snapshot(1).litres()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("a scheme that supplied on no day still returns a row with zero days")
    void includesSchemesWithNoSupply() {
        // Its absence is Section 2 of the report, so it must be a row rather than a missing one.
        assertThat(snapshot(2).supplyDays()).isZero();
        assertThat(snapshot(2).litres()).isZero();
    }

    @Test
    @DisplayName("an outage day is not a supply day, even carrying a positive volume")
    void excludesNotSubmittedDays() {
        insertWaterQuantity(1, 10_000, WEEK_START, 1);
        insertWaterQuantity(1, 99_000, WEEK_START.plusDays(1), 0);   // NOT_SUBMITTED

        assertThat(snapshot(1).supplyDays()).isEqualTo(1);
        assertThat(snapshot(1).litres()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("a zero-volume day is not a supply day")
    void excludesZeroVolumeDays() {
        insertWaterQuantity(1, 0, WEEK_START, 1);

        assertThat(snapshot(1).supplyDays()).isZero();
    }

    @Test
    @DisplayName("days outside the week are excluded from both the count and the litres")
    void respectsTheWeekBoundaries() {
        insertWaterQuantity(1, 10_000, WEEK_START.minusDays(1), 1);  // Sunday before
        insertWaterQuantity(1, 10_000, WEEK_START, 1);
        insertWaterQuantity(1, 10_000, WEEK_END, 1);
        insertWaterQuantity(1, 10_000, WEEK_END.plusDays(1), 1);     // Monday after

        // Both ends are inclusive; neither neighbour leaks in.
        assertThat(snapshot(1).supplyDays()).isEqualTo(2);
        assertThat(snapshot(1).litres()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("a duplicated scheme-day is counted once, taking the latest row")
    void dedupesDuplicateWaterRows() {
        insertWaterQuantity(1, 10_000, WEEK_START, 1);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '1 hour', 1)
                """, TENANT, 1, OFFICER, 25_000, WEEK_START);

        assertThat(snapshot(1).supplyDays()).isEqualTo(1);
        assertThat(snapshot(1).litres()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("a fanned-out scheme is one row with its households counted once")
    void collapsesSchemeFanOut() {
        insertSchemeRow(1, 100, HANDED_OVER, 101, 201);
        insertSchemeRow(1, 100, HANDED_OVER, 102, 202);

        List<SchemeWeekSnapshot> rows = snapshots(null);

        assertThat(rows).filteredOn(s -> s.schemeId() == 1).singleElement()
                .extracting(SchemeWeekSnapshot::fhtc).isEqualTo(100L);
    }

    @Test
    @DisplayName("a scheme that is not handed over is excluded")
    void excludesSchemesNotHandedOver() {
        insertSchemeRow(3, 50, 1, 300, 300);
        mapUser(OFFICER, 3);

        assertThat(snapshots(null)).extracting(SchemeWeekSnapshot::schemeId).doesNotContain(3);
    }

    @Test
    @DisplayName("a scheme with a NULL work_status is excluded")
    void excludesSchemesWithNoWorkStatus() {
        insertSchemeRow(4, 50, null, 400, 400);
        mapUser(OFFICER, 4);

        assertThat(snapshots(null)).extracting(SchemeWeekSnapshot::schemeId).doesNotContain(4);
    }

    // ── supervisor narrowing ────────────────────────────────────────────────────

    @Test
    @DisplayName("the supervisor bind restricts an officer to the schemes shared with that supervisor")
    void narrowsToSharedSchemes() {
        // Scheme 1 is mapped to both; scheme 2 only to the Section Officer. In the SDO's report the
        // officer must contribute only scheme 1 — scheme 2 belongs to some other SDO's command.
        mapUser(SDO, 1);

        assertThat(snapshots(SDO)).extracting(SchemeWeekSnapshot::schemeId).containsExactly(1);
    }

    @Test
    @DisplayName("a null supervisor leaves the officer's own report unnarrowed")
    void nullSupervisorDisablesNarrowing() {
        mapUser(SDO, 1);

        // The same officer's own report legitimately covers more schemes than their row in the SDO's.
        assertThat(snapshots(null)).extracting(SchemeWeekSnapshot::schemeId).containsExactly(1, 2);
    }

    @Test
    @DisplayName("returns nothing for an officer with no schemes")
    void returnsEmptyForUnknownOfficer() {
        assertThat(repository.listSchemeWeekSnapshots(TENANT, 12345L, WEEK_START, WEEK_END, null)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private List<SchemeWeekSnapshot> snapshots(Long supervisorUserId) {
        return repository.listSchemeWeekSnapshots(TENANT, OFFICER, WEEK_START, WEEK_END, supervisorUserId);
    }

    private SchemeWeekSnapshot snapshot(int schemeId) {
        return snapshots(null).stream()
                .filter(s -> s.schemeId() == schemeId)
                .findFirst()
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
        mapUser(OFFICER, 1);
        mapUser(OFFICER, 2);

        // fact_water_quantity_table.date has an FK to dim_date_table.full_date.
        for (int i = -2; i <= 8; i++) {
            insertDate(WEEK_START.plusDays(i));
        }
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
}
