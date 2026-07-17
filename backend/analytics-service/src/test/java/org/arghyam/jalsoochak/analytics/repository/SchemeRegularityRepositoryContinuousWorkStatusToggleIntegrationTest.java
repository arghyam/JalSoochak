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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the continuous-schemes {@code work_status} kill-switch
 * ({@code analytics.dashboard.continuous-schemes.work-status-filter-enabled = false}): the
 * continuous-schemes count/list queries (LGD / department / user) count ALL schemes regardless of
 * {@code work_status}, while every OTHER dashboard aggregate keeps the filter. The complementary
 * filter-ON path is covered by {@link SchemeRegularityRepositoryWorkStatusFilterIntegrationTest},
 * which enables the toggle explicitly.
 *
 * <p>{@code included-work-statuses = 4} is set so the filter WOULD exclude the non-handed-over schemes
 * if it applied; the toggle being OFF is the reason they are still counted. Three schemes under the
 * same parent LGD 100 / department 200, all mapped to operator (user 11):
 * <ul>
 *   <li>Scheme 1 — work_status = 4 (handed-over)</li>
 *   <li>Scheme 2 — work_status = 1 (not handed-over)</li>
 *   <li>Scheme 3 — work_status = NULL</li>
 * </ul>
 * Over D1..D2 (2 days) Schemes 1 and 2 report both days; Scheme 3 reports only D1. Continuous now means
 * "reported on at least one day", so with the filter ON the continuous count would be 1 (Scheme 1 only);
 * with it OFF it is 3 (all three schemes reported at least once).
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryContinuousWorkStatusToggleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_continuous_toggle_test")
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
        // The filter WOULD exclude Scheme 2 (work_status = 1) and Scheme 3 (NULL) ...
        registry.add("analytics.dashboard.included-work-statuses", () -> "4");
        // ... but the continuous-schemes kill-switch is OFF, so continuous queries ignore it.
        registry.add("analytics.dashboard.continuous-schemes.work-status-filter-enabled", () -> "false");
    }

    @Autowired
    private SchemeRegularityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final int USER = 11;
    private static final int LGD = 100;
    private static final int DEPARTMENT = 200;
    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);

    private static final int HANDED_OVER = 4;
    private static final int NOT_HANDED_OVER = 1;

    @BeforeEach
    void setUp() {
        truncateAll();
        seed();
    }

    @Test
    void continuousCount_byLgd_countsAllSchemesRegardlessOfWorkStatus() {
        // Filter ON would leave only Scheme 1; with the toggle OFF, Schemes 2 (ws=1) and 3 (NULL) are
        // also counted -- all three reported on at least one day.
        assertThat(repository.getContinuousSchemeCountByLgd(TENANT, LGD, D1, D2))
                .isEqualTo(3L);
    }

    @Test
    void continuousList_byLgd_listsAllSchemesRegardlessOfWorkStatus() {
        List<SchemeRegularityRepository.ContinuousSchemeRow> rows =
                repository.getContinuousSchemesByLgd(TENANT, LGD, D1, D2, 100, 0);
        assertThat(rows)
                .extracting(SchemeRegularityRepository.ContinuousSchemeRow::schemeId)
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void continuousCount_byDepartment_countsAllSchemesRegardlessOfWorkStatus() {
        assertThat(repository.getContinuousSchemeCountByDepartment(TENANT, DEPARTMENT, D1, D2))
                .isEqualTo(3L);
    }

    @Test
    void continuousCount_byUser_countsAllSchemesRegardlessOfWorkStatus() {
        assertThat(repository.getContinuousSchemeCountByUserSchemes(TENANT, USER, D1, D2))
                .isEqualTo(3L);
    }

    @Test
    void toggleIsScopedToContinuous_otherDashboardAggregatesStillFilter() {
        // The kill-switch is continuous-only: the general scheme-count filter still excludes Scheme 2
        // (ws=1) and Scheme 3 (NULL), so LGD 100 resolves to just Scheme 1.
        assertThat(repository.getSchemeCountByLgd(LGD)).isEqualTo(1);
    }

    // ---- seeding ----

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.submission_attempt_table,
                    analytics_schema.anomaly_table,
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.dim_user_scheme_mapping_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seed() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, TENANT, "mp", "Madhya Pradesh", "IN", 1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """, USER, TENANT, "user11@example.com", 1);

        insertLgd(100, "Parent LGD", 1, 100, null);
        insertLgd(101, "Child LGD A", 2, 100, 101);
        insertLgd(102, "Child LGD B", 2, 100, 102);

        insertDepartment(200, "Parent Dept", 1, 200, null);
        insertDepartment(201, "Child Dept A", 2, 200, 201);
        insertDepartment(202, "Child Dept B", 2, 200, 202);

        // Scheme 1 — handed-over; Scheme 2 — ws=1; Scheme 3 — ws=NULL. All under LGD 100 / Dept 200.
        insertScheme(1, "Scheme Handed Over", 101, 201, HANDED_OVER);
        insertScheme(2, "Scheme Not Handed Over", 102, 202, NOT_HANDED_OVER);
        insertScheme(3, "Scheme Null Status", 101, 201, null);

        mapUserScheme("11111111-1111-1111-1111-111111111111", USER, 1);
        mapUserScheme("22222222-2222-2222-2222-222222222222", USER, 2);
        mapUserScheme("33333333-3333-3333-3333-333333333333", USER, 3);

        // Schemes 1 and 2 report both days (continuous); Scheme 3 only D1 (not continuous).
        insertReading(1, D1, 10);
        insertReading(1, D2, 5);
        insertReading(2, D1, 20);
        insertReading(2, D2, 7);
        insertReading(3, D1, 5);
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

    private void insertScheme(int schemeId, String name, int level2Lgd, int level2Dept, Integer workStatus) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, work_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, schemeId, TENANT, name, 1000 + schemeId, 2000 + schemeId, 0.0, 0.0,
                100, 100, level2Lgd, null, null, null, null,
                200, 200, level2Dept, null, null, null, null,
                1, 10, 10, 10, workStatus);
    }

    private void mapUserScheme(String uuid, int userId, int schemeId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW(), ?)
                """, uuid, TENANT, userId, schemeId, null, 1);
    }

    private void insertReading(int schemeId, LocalDate date, int confirmed) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, TENANT, schemeId, USER, confirmed, confirmed, 90, "x", 1, date, 1, 0);
    }
}
