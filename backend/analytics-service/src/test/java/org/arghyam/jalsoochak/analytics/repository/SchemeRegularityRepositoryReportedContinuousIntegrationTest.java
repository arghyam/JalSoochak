package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REPORTED-METRIC: validates the reported-days continuity SQL of getContinuousSchemeCountByLgd against
 * a real Postgres. Exercises the three UNION branches (readings / image-reject anomalies /
 * submission_attempt), the IST day-boundary shift, lever A (any reading vs supplied), and that the
 * count (list=false) agrees with the list (list=true).
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryReportedContinuousIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_reported_continuous_test")
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

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Autowired
    private SchemeRegularityRepository repository;

    private static final int TENANT = 1;
    private static final int LGD = 100;                 // level 1
    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);
    private static final int DAYS_IN_RANGE = 3;          // (D3 - D1) + 1

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.submission_attempt_table,
                    analytics_schema.anomaly_table,
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
        seedTenantAndLgd();
        seedSchemes(10, 20, 30, 40, 50);

        // Scheme 10: supplied all 3 days -> reported 3 (branch A)
        reading(10, D1, 5); reading(10, LocalDate.of(2026, 1, 2), 5); reading(10, D3, 5);
        // Scheme 20: supplied days 1,2 + duplicate reject at 2026-01-02 20:00 UTC = 2026-01-03 01:30 IST -> day 3
        // (branch B + IST boundary; AT TIME ZONE makes this session-independent)
        reading(20, D1, 5); reading(20, LocalDate.of(2026, 1, 2), 5);
        anomaly(20, "DUPLICATE_IMAGE_SUBMISSION", "2026-01-02 20:00:00+00");
        // Scheme 30: supplied days 1,2 + submission_attempt on day 3 (branch C)
        reading(30, D1, 5); reading(30, LocalDate.of(2026, 1, 2), 5);
        submissionAttempt(30, "2026-01-03 06:00:00");
        // Scheme 40: supplied days 1,2 only -> reported 2 -> NOT continuous
        reading(40, D1, 5); reading(40, LocalDate.of(2026, 1, 2), 5);
        // Scheme 50: day1 is 0-supply (outage) + supplied days 2,3 -> reported 3 (lever A: any reading counts)
        reading(50, D1, 0); reading(50, LocalDate.of(2026, 1, 2), 5); reading(50, D3, 5);
    }

    @Test
    void reportedContinuousCount_countsReadingsAndRejectsAndAttempts_withIstBoundary() {
        long count = repository.getContinuousSchemeCountByLgd(TENANT, LGD, D1, D3, DAYS_IN_RANGE);
        // 10 (readings), 20 (reject on IST day 3), 30 (attempt day 3), 50 (0-supply still reports) => 4.
        // 40 is excluded (only 2 reported days).
        assertThat(count).isEqualTo(4L);
    }

    @Test
    void listMatchesCount_sameReportedContinuitySchemes() {
        List<SchemeRegularityRepository.ContinuousSchemeRow> rows =
                repository.getContinuousSchemesByLgd(TENANT, LGD, D1, D3, DAYS_IN_RANGE, 100, 0);
        List<Integer> ids = rows.stream()
                .map(SchemeRegularityRepository.ContinuousSchemeRow::schemeId)
                .collect(Collectors.toList());
        assertThat(ids).containsExactlyInAnyOrder(10, 20, 30, 50);
    }

    // ---- seed helpers ----

    private void seedTenantAndLgd() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at, updated_at)
                VALUES (?, 't1', 'Tenant 1', 'IN', 1, 1, 1, 0, 0, NOW(), NOW())
                """, TENANT);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 created_at, updated_at)
                VALUES (?, ?, 'L100', 'District', 'District', 1, ?, ?, NULL, NULL, NULL, NULL, NOW(), NOW())
                """, LGD, TENANT, LGD, LGD);
    }

    private void seedSchemes(int... schemeIds) {
        for (int s : schemeIds) {
            jdbcTemplate.update("""
                    INSERT INTO analytics_schema.dim_scheme_table
                    (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                     parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                     parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                     operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                    VALUES
                    (?, ?, ?, ?, ?, 0.0, 0.0, ?, ?, ?, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW())
                    """, s, TENANT, "S" + s, 1000 + s, 2000 + s, LGD, LGD, LGD);
        }
    }

    private void reading(int schemeId, LocalDate date, int confirmed) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, reading_at, reading_date, created_at)
                VALUES (?, ?, 11, ?, ?, ?, ?, NOW())
                """, TENANT, schemeId, confirmed, confirmed, date.atTime(10, 0), date);
    }

    private void anomaly(int schemeId, String type, String createdAtUtc) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.anomaly_table
                (uuid, type, scheme_id, tenant_id, status, created_at)
                VALUES (?, ?, ?, ?, 1, ?::timestamptz)
                """, "u-" + schemeId + "-" + type, type, schemeId, TENANT, createdAtUtc);
    }

    private void submissionAttempt(int schemeId, String attemptedAt) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.submission_attempt_table
                (tenant_id, scheme_id, attempted_at)
                VALUES (?, ?, ?::timestamp)
                """, TENANT, schemeId, attemptedAt);
    }
}
