package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
import org.junit.jupiter.api.BeforeEach;
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
 * Verifies the dashboard {@code work_status} filter: with
 * {@code analytics.dashboard.included-work-statuses = 4}, every dashboard aggregate counts ONLY
 * handed-over schemes (work_status = 4) and excludes schemes with any other work_status (here 1)
 * and NULL work_status.
 *
 * <p>Three schemes are seeded, all under the same parent LGD 100 / department 200 and mapped to the
 * same operator (user 11):
 * <ul>
 *   <li>Scheme 1 — work_status = 4 (handed-over)  -> INCLUDED</li>
 *   <li>Scheme 2 — work_status = 1 (not handed-over) -> EXCLUDED</li>
 *   <li>Scheme 3 — work_status = NULL -> EXCLUDED</li>
 * </ul>
 * Each assertion below would return a larger value if the filter were absent; the exact expected
 * values reflect Scheme 1 alone. The complementary "filter disabled" path (all schemes counted) is
 * covered by {@link SchemeRegularityRepositoryIntegrationTest} and the other aggregation ITs, which
 * override the property to an empty string.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryWorkStatusFilterIntegrationTest {

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
        // The behaviour under test: only work_status = 4 (handed-over) schemes are included.
        registry.add("analytics.dashboard.included-work-statuses", () -> "4");
        // The continuous-schemes work_status filter defaults to OFF; this suite asserts the filtered
        // (ON) behaviour for continuous schemes too, so enable it explicitly. The default-OFF path is
        // covered by SchemeRegularityRepositoryContinuousWorkStatusToggleIntegrationTest.
        registry.add("analytics.dashboard.continuous-schemes.work-status-filter-enabled", () -> "true");
    }

    @Autowired
    private SchemeRegularityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);

    private static final int HANDED_OVER = 4;
    private static final int NOT_HANDED_OVER = 1;

    @BeforeEach
    void setUp() {
        truncateAll();
        seed();
    }

    // ---- scheme-count helpers ----

    @Test
    void schemeCount_byLgd_countsOnlyHandedOverScheme() {
        // LGD 100 (level 1) covers all three schemes; only Scheme 1 is handed-over.
        assertThat(repository.getSchemeCountByLgd(100)).isEqualTo(1);
        // LGD 101 holds Scheme 1 (handed-over) and Scheme 3 (NULL) -> NULL is excluded too.
        assertThat(repository.getSchemeCountByLgd(101)).isEqualTo(1);
        // LGD 102 holds only Scheme 2 (work_status = 1) -> excluded entirely.
        assertThat(repository.getSchemeCountByLgd(102)).isEqualTo(0);
        assertThat(repository.getSchemeCountByLgdInScope(1, 100)).isEqualTo(1);
    }

    @Test
    void schemeCount_byDepartment_countsOnlyHandedOverScheme() {
        assertThat(repository.getSchemeCountByDepartment(200)).isEqualTo(1);
        assertThat(repository.getSchemeCountByDepartment(201)).isEqualTo(1);
        assertThat(repository.getSchemeCountByDepartment(202)).isEqualTo(0);
        assertThat(repository.getSchemeCountByDepartmentInScope(1, 200)).isEqualTo(1);
    }

    @Test
    void schemeCount_byUser_countsOnlyHandedOverScheme() {
        // User 11 is mapped to all three schemes; the filter leaves only Scheme 1.
        assertThat(repository.getSchemeCountByUser(1, 11)).isEqualTo(1);
    }

    @Test
    void schemeCount_byUser_withSupervisor_countsOnlySchemesSharedWithThem() {
        // Backs the SDO report's per-Section-Officer Total Schemes column: user 11's count must drop
        // to the schemes they share with the supervising officer, on top of the work-status filter.
        mapUserScheme("44444444-4444-4444-4444-444444444444", 12, 2);   // supervisor shares only Scheme 2
        assertThat(repository.getSchemeCountByUser(1, 11, 12L))
                .as("Scheme 2 is shared but not handed-over, so the work-status filter still excludes it")
                .isZero();

        mapUserScheme("55555555-5555-5555-5555-555555555555", 12, 1);   // now also shares Scheme 1
        assertThat(repository.getSchemeCountByUser(1, 11, 12L))
                .as("Scheme 1 is both shared and handed-over")
                .isEqualTo(1);

        // A null supervisor leaves the unscoped behaviour untouched.
        assertThat(repository.getSchemeCountByUser(1, 11, null)).isEqualTo(1);
    }

    // ---- scheme-status count ----

    @Test
    void schemeStatusCount_countsOnlyHandedOverScheme() {
        SchemeRegularityRepository.SchemeStatusCount byLgd = repository.getSchemeStatusCountByLgd(100);
        // Scheme 1 is active; Scheme 2 (active, ws=1) and Scheme 3 (inactive, ws=NULL) are excluded.
        assertThat(byLgd.activeSchemeCount()).isEqualTo(1);
        assertThat(byLgd.inactiveSchemeCount()).isEqualTo(0);

        SchemeRegularityRepository.SchemeStatusCount byDept = repository.getSchemeStatusCountByDepartment(200);
        assertThat(byDept.activeSchemeCount()).isEqualTo(1);
        assertThat(byDept.inactiveSchemeCount()).isEqualTo(0);
    }

    // ---- submission-status summary ----

    @Test
    void submissionStatusCount_byLgd_countsOnlyHandedOverSchemeReadings() {
        SchemeRegularityRepository.SubmissionStatusCount byLgd =
                repository.getSubmissionStatusCountByLgd(100, D1, D3);
        // Only Scheme 1's two compliant reading rows (D1, D2) are counted.
        assertThat(byLgd.compliantSubmissionCount()).isEqualTo(2);
        assertThat(byLgd.anomalousSubmissionCount()).isEqualTo(0);
    }

    // ---- outage reasons ----

    @Test
    void outageReasons_byLgd_excludeNonHandedOverSchemes() {
        List<SchemeRegularityRepository.OutageReasonSchemeCount> byLgd =
                repository.getOutageReasonSchemeCountByLgd(100, D1, D3);

        assertThat(byLgd).hasSize(1);
        assertThat(byLgd.getFirst().outageReason()).isEqualTo("ho_outage");
        assertThat(byLgd.getFirst().schemeCount()).isEqualTo(1);
        assertThat(byLgd).noneMatch(r -> "excluded_outage".equals(r.outageReason()));
        assertThat(byLgd).noneMatch(r -> "null_outage".equals(r.outageReason()));
    }

    @Test
    void outageReasons_byUser_excludeNonHandedOverSchemes() {
        List<SchemeRegularityRepository.OutageReasonSchemeCount> byUser =
                repository.getOutageReasonSchemeCountByUser(1, 11, D1, D3);

        assertThat(byUser).hasSize(1);
        assertThat(byUser.getFirst().outageReason()).isEqualTo("ho_outage");
        assertThat(byUser).noneMatch(r -> "excluded_outage".equals(r.outageReason()));
        assertThat(byUser).noneMatch(r -> "null_outage".equals(r.outageReason()));
    }

    // ---- non-submission reasons ----

    @Test
    void nonSubmissionReasons_byLgd_excludeNonHandedOverSchemes() {
        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> byLgd =
                repository.getNonSubmissionReasonSchemeCountByLgd(100, D1, D3);

        assertThat(byLgd).hasSize(1);
        assertThat(byLgd.getFirst().nonSubmissionReason()).isEqualTo("ho_nonsub");
        assertThat(byLgd.getFirst().schemeCount()).isEqualTo(1);
        assertThat(byLgd).noneMatch(r -> "excluded_nonsub".equals(r.nonSubmissionReason()));
        assertThat(byLgd).noneMatch(r -> "null_nonsub".equals(r.nonSubmissionReason()));
    }

    // ---- water supply (average per region) ----

    @Test
    void waterSupplyPerCurrentRegion_returnsOnlyHandedOverScheme() {
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> rows =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().schemeId()).isEqualTo(1);
        // Scheme 1 SUBMITTED water on D2 only.
        assertThat(rows.getFirst().totalWaterSuppliedLiters()).isEqualTo(200L);
    }

    @Test
    void waterSupplyPerNation_aggregatesOnlyHandedOverScheme() {
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> rows =
                repository.getAverageWaterSupplyPerNation(D1, D3);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().tenantId()).isEqualTo(1);
        assertThat(rows.getFirst().schemeCount()).isEqualTo(1);
        assertThat(rows.getFirst().totalHouseholdCount()).isEqualTo(10);
        assertThat(rows.getFirst().totalWaterSuppliedLiters()).isEqualTo(200L);
    }

    @Test
    void waterSupplyPerChildLgd_excludesNonHandedOverSchemeRegion() {
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> byLgd =
                repository.getAverageWaterSupplyPerCurrentRegionByLgd(1, 100, D1, D3);

        assertThat(byLgd).hasSize(2);
        SchemeRegularityRepository.ChildRegionWaterSupplyMetrics child101 =
                byLgd.stream().filter(r -> r.lgdId() == 101).findFirst().orElseThrow();
        SchemeRegularityRepository.ChildRegionWaterSupplyMetrics child102 =
                byLgd.stream().filter(r -> r.lgdId() == 102).findFirst().orElseThrow();

        // Scheme 1 (handed-over) lives in 101; Scheme 2 (excluded) lives in 102 -> 102 contributes nothing.
        assertThat(child101.totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(child102.totalWaterSuppliedLiters()).isEqualTo(0L);
    }

    // ---- water quantity (region-wise) ----

    @Test
    void regionWiseWaterQuantity_byLgd_excludesNonHandedOverSchemes() {
        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> byLgd =
                repository.getRegionWiseWaterQuantityByLgd(100, D1, D3);

        assertThat(byLgd).hasSize(2);
        SchemeRegularityRepository.ChildRegionWaterQuantityMetrics child101 =
                byLgd.stream().filter(r -> r.lgdId() == 101).findFirst().orElseThrow();
        SchemeRegularityRepository.ChildRegionWaterQuantityMetrics child102 =
                byLgd.stream().filter(r -> r.lgdId() == 102).findFirst().orElseThrow();

        // 101: only Scheme 1 counts (Scheme 3 NULL excluded) -> household 10. Water is now unified on the
        // canonical supplied-volume definition ({{SWS}}): only the SUBMITTED D2 row (200) counts; the
        // NOT_SUBMITTED D1 row (100) is excluded (H1). eWater = 200.
        assertThat(child101.householdCount()).isEqualTo(10L);
        assertThat(child101.waterQuantity()).isEqualTo(200L);
        // 102: Scheme 2 excluded entirely.
        assertThat(child102.householdCount()).isEqualTo(0L);
        assertThat(child102.waterQuantity()).isEqualTo(0L);
    }

    // ---- water quantity (periodic) ----

    @Test
    void periodicWaterQuantity_byLgd_aggregatesOnlyHandedOverScheme() {
        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> rows =
                repository.getPeriodicWaterQuantityByLgdId(100, D1, D3, PeriodScale.MONTH);

        assertThat(rows).hasSize(1);
        // Household total sums only in-scope schemes -> Scheme 1's 10 (not 35 across all three).
        assertThat(rows.getFirst().householdCount()).isEqualTo(10);
    }

    // ---- critical schemes ----

    @Test
    void criticalSchemeCount_byLgd_countsOnlyHandedOverScheme() {
        // Cutoff D3: every scheme's last confirmed reading (<= D2) is before the cutoff, so all three
        // would be critical without the filter. The filter leaves only Scheme 1.
        assertThat(repository.getCriticalSchemeCountByLgd(1, 100, D3)).isEqualTo(1L);
    }

    // ---- continuous schemes ----

    @Test
    void continuousSchemeCount_byLgd_countsOnlyHandedOverScheme() {
        // Over D1..D2 all three schemes reported on at least one day (continuous = reported >= 1 day).
        // Without the filter this would be 3; the filter leaves only Scheme 1.
        assertThat(repository.getContinuousSchemeCountByLgd(1, 100, D1, D2)).isEqualTo(1L);
    }

    // ---- seeding ----

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_user_scheme_mapping_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_date_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seed() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, "mp", "Madhya Pradesh", "IN", 1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """, 11, 1, "user11@example.com", 1);

        insertLgd(100, "Parent LGD", 1, 100, null);
        insertLgd(101, "Child LGD A", 2, 100, 101);
        insertLgd(102, "Child LGD B", 2, 100, 102);

        insertDepartment(200, "Parent Dept", 1, 200, null);
        insertDepartment(201, "Child Dept A", 2, 200, 201);
        insertDepartment(202, "Child Dept B", 2, 200, 202);

        // Scheme 1 — handed-over (INCLUDED); LGD 101 / Dept 201; active.
        insertScheme(1, "Scheme Handed Over", 101, 201, 1, 10, 10, 10, HANDED_OVER);
        // Scheme 2 — work_status = 1 (EXCLUDED); LGD 102 / Dept 202; active.
        insertScheme(2, "Scheme Not Handed Over", 102, 202, 1, 20, 20, 20, NOT_HANDED_OVER);
        // Scheme 3 — work_status = NULL (EXCLUDED); LGD 101 / Dept 201; inactive.
        insertScheme(3, "Scheme Null Status", 101, 201, 0, 5, 5, 5, null);

        insertDate(D1);
        insertDate(D2);
        insertDate(D3);

        mapUserScheme("11111111-1111-1111-1111-111111111111", 11, 1);
        mapUserScheme("22222222-2222-2222-2222-222222222222", 11, 2);
        mapUserScheme("33333333-3333-3333-3333-333333333333", 11, 3);

        // Meter readings (all compliant: extracted = confirmed, submitted).
        insertReading(1, D1, 10, 10);
        insertReading(1, D2, 5, 5);
        insertReading(2, D1, 20, 20);
        insertReading(2, D2, 7, 7);
        insertReading(3, D1, 5, 5);

        // Water quantity: NOT_SUBMITTED rows carry outage/non-submission reasons; the SUBMITTED D2 row supplies water.
        insertWaterQuantity(1, D1, 100, SubmissionStatus.NOT_SUBMITTED.getCode(), "ho_outage", "ho_nonsub");
        insertWaterQuantity(1, D2, 200, SubmissionStatus.SUBMITTED.getCode(), null, null);
        insertWaterQuantity(2, D1, 500, SubmissionStatus.NOT_SUBMITTED.getCode(), "excluded_outage", "excluded_nonsub");
        insertWaterQuantity(2, D2, 600, SubmissionStatus.SUBMITTED.getCode(), null, null);
        insertWaterQuantity(3, D1, 700, SubmissionStatus.NOT_SUBMITTED.getCode(), "null_outage", "null_nonsub");
    }

    private void insertLgd(int lgdId, String title, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, lgdId, 1, "L" + lgdId, title, title, level, level1, level2, null, null, null, null);
    }

    private void insertDepartment(int departmentId, String title, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, departmentId, 1, title, title, level, level1, level2, null, null, null, null);
    }

    private void insertScheme(int schemeId, String name, int level2Lgd, int level2Dept,
                              int operatingStatus, int fhtc, int planned, int household, Integer workStatus) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, work_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, schemeId, 1, name, 1000 + schemeId, 2000 + schemeId, 0.0, 0.0,
                100, 100, level2Lgd, null, null, null, null,
                200, 200, level2Dept, null, null, null, null,
                operatingStatus, fhtc, planned, household, workStatus);
    }

    private void mapUserScheme(String uuid, int userId, int schemeId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW(), ?)
                """, uuid, 1, userId, schemeId, null, 1);
    }

    private void insertReading(int schemeId, LocalDate date, Integer extracted, Integer confirmed) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, schemeId, 11, extracted, confirmed, 90, "x", 1, date, 1, 0);
    }

    private void insertWaterQuantity(int schemeId, LocalDate date, int waterQuantity,
                                     Integer submissionStatus, String outageReason, String nonSubmissionReason) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, schemeId, 11, waterQuantity, date, submissionStatus, outageReason, nonSubmissionReason);
    }

    private void insertDate(LocalDate date) {
        int dateKey = Integer.parseInt(date.toString().replace("-", ""));
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, EXTRACT(DAY FROM ?::date), EXTRACT(MONTH FROM ?::date), TO_CHAR(?::date, 'FMMonth'),
                        EXTRACT(QUARTER FROM ?::date), EXTRACT(YEAR FROM ?::date), EXTRACT(WEEK FROM ?::date),
                        EXTRACT(ISODOW FROM ?::date) IN (6,7), EXTRACT(YEAR FROM ?::date))
                """, dateKey, date, date, date, date, date, date, date, date, date);
    }
}
