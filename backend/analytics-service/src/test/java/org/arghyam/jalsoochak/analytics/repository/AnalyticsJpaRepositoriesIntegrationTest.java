package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnalyticsJpaRepositoriesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_jpa_test")
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
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private DimDateRepository dimDateRepository;
    @Autowired
    private DimTenantRepository dimTenantRepository;
    @Autowired
    private DimUserRepository dimUserRepository;
    @Autowired
    private DimSchemeRepository dimSchemeRepository;
    @Autowired
    private DimLgdLocationRepository dimLgdLocationRepository;
    @Autowired
    private DimDepartmentLocationRepository dimDepartmentLocationRepository;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.fact_escalation_table,
                    analytics_schema.fact_scheme_performance_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_date_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);

        seedDimensions();
        seedFacts();
    }

    @Test
    void dimRepositories_findersWorkAsExpected() {
        DimDate dimDate = dimDateRepository.findByFullDate(D1).orElseThrow();
        assertThat(dimDate.getDateKey()).isEqualTo(20260101);

        assertThat(dimTenantRepository.findByStatus(1)).hasSize(1);
        assertThat(dimTenantRepository.findByStatus(0)).hasSize(1);
        assertThat(dimUserRepository.findByTenantId(1)).hasSize(2);
        assertThat(dimSchemeRepository.findByTenantId(1)).hasSize(2);
        assertThat(dimSchemeRepository.findByTenantId(2)).hasSize(1);
        assertThat(dimLgdLocationRepository.findByTenantId(1)).hasSize(3);
        assertThat(dimDepartmentLocationRepository.findByTenantId(1)).hasSize(3);
    }

    private void seedDimensions() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW()),
                       (2, 'up', 'Uttar Pradesh', 'IN', 0, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES
                (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11'),
                (12, 1, 'u12@test.local', 1, NOW(), NOW(), 'User 12'),
                (21, 2, 'u21@test.local', 1, NOW(), NOW(), 'User 21')
                """);

        insertDate(20260101, D1);
        insertDate(20260102, D2);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES
                (100, 1, 'L100', 'Parent', 'Parent LGD', 1, 100, NULL, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (101, 1, 'L101', 'ChildA', 'Child A', 2, 100, 101, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (102, 1, 'L102', 'ChildB', 'Child B', 2, 100, 102, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (200, 2, 'L200', 'T2', 'Tenant2 LGD', 1, 200, NULL, NULL, NULL, NULL, NULL, NULL, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at, geom)
                VALUES
                (200, 1, 'Parent Dept', 'Parent Dept', 1, 200, NULL, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL),
                (201, 1, 'Child Dept A', 'Child Dept A', 2, 200, 201, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL),
                (202, 1, 'Child Dept B', 'Child Dept B', 2, 200, 202, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL)
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Scheme A', 1001, 2001, 0.0, 0.0, 100, 100, 101, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Scheme B', 1002, 2002, 0.0, 0.0, 100, 100, 102, NULL, NULL, NULL, NULL, 200, 200, 202, NULL, NULL, NULL, NULL, 0, 20, 20, 20, NOW(), NOW()),
                (3, 2, 'Scheme C', 1003, 2003, 0.0, 0.0, 200, 200, NULL, NULL, NULL, NULL, NULL, 200, 200, NULL, NULL, NULL, NULL, NULL, 1, 15, 15, 15, NOW(), NOW())
                """);
    }

    private void seedFacts() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES
                (1, 1, 11, 10, 10, 90, 'x', NOW(), 1, ?, NOW(), 1, 0),
                (1, 1, 11, 0, 0, 90, 'x', NOW(), 1, ?, NOW(), 1, 0),
                (1, 2, 12, 5, 5, 90, 'x', NOW(), 1, ?, NOW(), 1, 0),
                (1, 2, 12, -1, -1, 90, 'x', NOW(), 1, ?, NOW(), 1, 0)
                """, D1, D2, D1, D2);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason)
                VALUES
                (1, 1, 11, 100, ?, NOW(), NOW(), 1, 'draught'),
                (1, 1, 11, 200, ?, NOW(), NOW(), 1, NULL),
                (1, 2, 12, 50, ?, NOW(), NOW(), 1, 'no_electricity')
                """, D1, D2, D1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_escalation_table
                (tenant_id, scheme_id, escalation_type, message, user_id, resolution_status, remark, created_at, updated_at)
                VALUES
                (1, 1, 1, 'Esc-1', 11, 1, 'ok', NOW(), NOW()),
                (1, 2, 2, 'Esc-2', 12, 0, 'pending', NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_scheme_performance_table
                (scheme_id, tenant_id, performance_score, last_water_supply_date, created_at, updated_at)
                VALUES
                (1, 1, 90, ?, NOW(), NOW()),
                (2, 1, 80, ?, NOW(), NOW()),
                (3, 2, 70, ?, NOW(), NOW())
                """, D1, D2, D1);
    }

    private void insertDate(int dateKey, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, EXTRACT(DAY FROM ?::date), EXTRACT(MONTH FROM ?::date), TO_CHAR(?::date, 'FMMonth'),
                        EXTRACT(QUARTER FROM ?::date), EXTRACT(YEAR FROM ?::date), EXTRACT(WEEK FROM ?::date),
                        EXTRACT(ISODOW FROM ?::date) IN (6,7), EXTRACT(YEAR FROM ?::date))
                """, dateKey, date, date, date, date, date, date, date, date, date);
    }
}
