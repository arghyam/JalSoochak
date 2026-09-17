package org.arghyam.jalsoochak.analytics.repository;

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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the schemes-dashboard aggregates to <em>one row per scheme</em> when {@code dim_scheme_table}
 * holds several rows for the same scheme.
 *
 * <p>Since V24 the table is unique on
 * {@code (tenant_id, scheme_id, parent_lgd_location_id, parent_department_location_id)}, so one scheme
 * legitimately spans many rows. {@code DimensionServiceImpl} nevertheless rewrites a single row per
 * scheme — the one found by {@code findTopByTenantIdAndSchemeIdOrderByUpdatedAtDescCreatedAtDesc} — so
 * a multi-row scheme whose status was ever updated carries the new status on one row and the stale one
 * on the rest. Aggregates that group over the raw rows then count that scheme once per distinct value,
 * and the status buckets sum to more than the scheme total.
 *
 * <p>Scheme 1 is seeded exactly that way: two rows, the fresher one carrying the current status. Every
 * assertion below states that the dashboard sees one scheme, with the freshest row's status.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularitySchemeFanoutIntegrationTest {

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
        // Filter off: the drifted rows must both stay in scope for the double-count to be visible.
        registry.add("analytics.dashboard.included-work-statuses", () -> "");
    }

    @Autowired
    private SchemeRegularityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final int PARENT_LGD = 100;
    private static final int PARENT_DEPT = 200;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 3);

    /** Scheme 1's current status, written to its freshest row. */
    private static final int CURRENT_WORK_STATUS = 2;      // Completed
    private static final int CURRENT_OPERATING_STATUS = 0; // Non-Operative
    /** Scheme 1's superseded status, left behind on the older row. */
    private static final int STALE_WORK_STATUS = 4;        // Handed Over
    private static final int STALE_OPERATING_STATUS = 1;   // Operative

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE analytics_schema.dim_scheme_table,
                         analytics_schema.dim_lgd_location_table,
                         analytics_schema.dim_department_location_table,
                         analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
        seed();
    }

    @Test
    void statusBreakdownByLgd_countsAFannedOutSchemeOnce() {
        SchemeRegularityRepository.SchemeStatusBreakdown breakdown =
                repository.getSchemeStatusCountByLgd(TENANT, PARENT_LGD);

        assertThat(breakdown.total()).isEqualTo(2);
        assertThat(sum(breakdown.workStatusCounts()))
                .as("work_status buckets must partition the scheme total")
                .isEqualTo(breakdown.total());
        assertThat(sum(breakdown.operatingStatusCounts()))
                .as("operating_status buckets must partition the scheme total")
                .isEqualTo(breakdown.total());
        assertThat(breakdown.total())
                .isEqualTo((int) repository.getSchemeCountByLgdInScope(TENANT, PARENT_LGD));
    }

    @Test
    void statusBreakdownByDepartment_countsAFannedOutSchemeOnce() {
        SchemeRegularityRepository.SchemeStatusBreakdown breakdown =
                repository.getSchemeStatusCountByDepartment(TENANT, PARENT_DEPT);

        assertThat(breakdown.total()).isEqualTo(2);
        assertThat(sum(breakdown.workStatusCounts())).isEqualTo(breakdown.total());
        assertThat(sum(breakdown.operatingStatusCounts())).isEqualTo(breakdown.total());
        assertThat(breakdown.total())
                .isEqualTo((int) repository.getSchemeCountByDepartmentInScope(TENANT, PARENT_DEPT));
    }

    @Test
    void statusBreakdown_reportsTheFreshestRowsStatus() {
        SchemeRegularityRepository.SchemeStatusBreakdown breakdown =
                repository.getSchemeStatusCountByLgd(TENANT, PARENT_LGD);

        // Scheme 1 -> current status; Scheme 2 -> its own (STALE_* values, held by a single row).
        assertThat(breakdown.workStatusCounts())
                .containsExactlyInAnyOrder(
                        new SchemeRegularityRepository.SchemeStatusCodeCount(CURRENT_WORK_STATUS, 1),
                        new SchemeRegularityRepository.SchemeStatusCodeCount(STALE_WORK_STATUS, 1));
        assertThat(breakdown.operatingStatusCounts())
                .containsExactlyInAnyOrder(
                        new SchemeRegularityRepository.SchemeStatusCodeCount(CURRENT_OPERATING_STATUS, 1),
                        new SchemeRegularityRepository.SchemeStatusCodeCount(STALE_OPERATING_STATUS, 1));
    }

    @Test
    void topSchemesByLgd_listsAFannedOutSchemeOnceWithTheFreshestStatus() {
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> metrics =
                repository.getTopSchemeSubmissionMetricsByLgd(
                        TENANT, PARENT_LGD, START, END, 10, 0, "reportingRate", "desc");

        assertOneRowPerSchemeWithFreshStatus(metrics);
    }

    @Test
    void topSchemesByDepartment_listsAFannedOutSchemeOnceWithTheFreshestStatus() {
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> metrics =
                repository.getTopSchemeSubmissionMetricsByDepartment(
                        TENANT, PARENT_DEPT, START, END, 10, 0, "reportingRate", "desc");

        assertOneRowPerSchemeWithFreshStatus(metrics);
    }

    @Test
    void csvStreamByLgd_emitsAFannedOutSchemeOnce() {
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> metrics = new ArrayList<>();
        repository.streamSchemeSubmissionMetricsByLgd(
                TENANT, PARENT_LGD, START, END, "reportingRate", "desc", metrics::add);

        assertOneRowPerSchemeWithFreshStatus(metrics);
    }

    @Test
    void csvStreamByDepartment_emitsAFannedOutSchemeOnce() {
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> metrics = new ArrayList<>();
        repository.streamSchemeSubmissionMetricsByDepartment(
                TENANT, PARENT_DEPT, START, END, "reportingRate", "desc", metrics::add);

        assertOneRowPerSchemeWithFreshStatus(metrics);
    }

    /** The list and the breakdown must agree: one row per scheme, carrying the freshest row's status. */
    private void assertOneRowPerSchemeWithFreshStatus(
            List<SchemeRegularityRepository.SchemeSubmissionMetrics> metrics) {
        assertThat(metrics)
                .extracting(SchemeRegularityRepository.SchemeSubmissionMetrics::schemeId)
                .containsExactlyInAnyOrder(1, 2);

        SchemeRegularityRepository.SchemeSubmissionMetrics fannedOut = metrics.stream()
                .filter(metric -> metric.schemeId() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(fannedOut.workStatus()).isEqualTo(CURRENT_WORK_STATUS);
        assertThat(fannedOut.operatingStatus()).isEqualTo(CURRENT_OPERATING_STATUS);
        assertThat(fannedOut.schemeName()).isEqualTo("Scheme Fanned Out");
    }

    private static int sum(List<SchemeRegularityRepository.SchemeStatusCodeCount> counts) {
        return counts.stream().mapToInt(SchemeRegularityRepository.SchemeStatusCodeCount::count).sum();
    }

    private void seed() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, TENANT, "mp", "Madhya Pradesh", "IN", 1);

        insertLgd(PARENT_LGD, "Parent LGD", 1, PARENT_LGD, null);
        insertLgd(101, "Child LGD A", 2, PARENT_LGD, 101);
        insertLgd(102, "Child LGD B", 2, PARENT_LGD, 102);

        insertDepartment(PARENT_DEPT, "Parent Dept", 1, PARENT_DEPT, null);
        insertDepartment(201, "Child Dept A", 2, PARENT_DEPT, 201);
        insertDepartment(202, "Child Dept B", 2, PARENT_DEPT, 202);

        // Scheme 1, row 1 — the older mapping, left holding the superseded status.
        insertSchemeRow(1, "Scheme Fanned Out", 101, 201, STALE_WORK_STATUS, STALE_OPERATING_STATUS,
                "2026-01-01 00:00:00");
        // Scheme 1, row 2 — the row DimensionServiceImpl rewrites, holding the current status.
        insertSchemeRow(1, "Scheme Fanned Out", 102, 202, CURRENT_WORK_STATUS, CURRENT_OPERATING_STATUS,
                "2026-02-01 00:00:00");
        // Scheme 2 — a single-row scheme, unaffected by the fan-out.
        insertSchemeRow(2, "Scheme Single Row", 101, 201, STALE_WORK_STATUS, STALE_OPERATING_STATUS,
                "2026-01-01 00:00:00");
    }

    private void insertLgd(int lgdId, String title, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, lgdId, TENANT, "L" + lgdId, title, title, level, level1, level2, null, null, null, null);
    }

    private void insertDepartment(int departmentId, String title, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, departmentId, TENANT, title, title, level, level1, level2, null, null, null, null);
    }

    private void insertSchemeRow(int schemeId, String name, int level2Lgd, int level2Dept,
                                 Integer workStatus, Integer operatingStatus, String updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, work_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamp, ?::timestamp)
                """, schemeId, TENANT, name, 1000 + schemeId, 2000 + schemeId, 0.0, 0.0,
                level2Lgd, PARENT_LGD, level2Lgd, null, null, null, null,
                level2Dept, PARENT_DEPT, level2Dept, null, null, null, null,
                operatingStatus, 10, 10, 10, workStatus, updatedAt, updatedAt);
    }
}
