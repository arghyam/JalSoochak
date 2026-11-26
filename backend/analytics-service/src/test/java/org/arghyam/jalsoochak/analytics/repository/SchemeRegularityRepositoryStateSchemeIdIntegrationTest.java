package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository.CriticalSchemeRow;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository.SchemeRegularityListMetrics;
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
 * Pins that every scheme list read out of {@code dim_scheme_table} carries the state scheme id as the
 * text stored, alphanumeric ones included, and the centre scheme id as an integer.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryStateSchemeIdIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_state_scheme_id_test")
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
        registry.add("analytics.dashboard.included-work-statuses", () -> "");
    }

    private static final int TENANT = 1;
    private static final int USER = 11;
    private static final int STATE_LGD = 100;
    private static final int TOP_DEPT = 200;
    private static final int SCHEME = 1;
    private static final String STATE_SCHEME_ID = "TW20826";
    private static final int CENTRE_SCHEME_ID = 6001;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 7);

    @Autowired
    private SchemeRegularityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE analytics_schema.dim_user_scheme_mapping_table,
                         analytics_schema.dim_scheme_table,
                         analytics_schema.dim_lgd_location_table,
                         analytics_schema.dim_department_location_table,
                         analytics_schema.dim_user_table,
                         analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, 'pb', 'Punjab', 'IN', 1, NOW(), NOW())
                """, TENANT);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES (?, ?, 'u11@test.local', 1, NOW(), NOW(), 'User 11')
                """, USER, TENANT);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level, level_1_lgd_id, created_at, updated_at)
                VALUES (?, ?, 'L100', 'Punjab', 'State', 1, ?, NOW(), NOW())
                """, STATE_LGD, TENANT, STATE_LGD);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level, level_1_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, 'Department', 'Department', 1, ?, NOW(), NOW())
                """, TOP_DEPT, TENANT, TOP_DEPT);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, scheme_name, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, level_1_lgd_id, parent_department_location_id, level_1_dept_id,
                 operating_status, work_status, created_at, updated_at)
                VALUES (?, ?, 'Scheme A', ?, ?, ?, ?, ?, ?, 1, 4, NOW(), NOW())
                """, TENANT, SCHEME, STATE_SCHEME_ID, CENTRE_SCHEME_ID, STATE_LGD, STATE_LGD, TOP_DEPT, TOP_DEPT);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (gen_random_uuid(), ?, ?, ?, NULL, NOW(), NOW(), 1)
                """, TENANT, USER, SCHEME);
    }

    @Test
    void criticalSchemeListsCarryTheStoredStateSchemeId() {
        List<List<CriticalSchemeRow>> lists = List.of(
                repository.getCriticalSchemesByUserSchemes(TENANT, USER, END, 10, 0),
                repository.getCriticalSchemesByLgd(TENANT, STATE_LGD, END, 10, 0),
                repository.getCriticalSchemesByDepartment(TENANT, TOP_DEPT, END, 10, 0));

        assertThat(lists).allSatisfy(rows -> assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.stateSchemeId()).isEqualTo(STATE_SCHEME_ID);
            assertThat(row.centreSchemeId()).isEqualTo(CENTRE_SCHEME_ID);
        }));
    }

    @Test
    void schemeRegionReportsCarryTheStoredStateSchemeId() {
        List<List<SchemeRegularityListMetrics>> reports = List.of(
                repository.getSchemeRegionReportByLgd(STATE_LGD, START, END),
                repository.getSchemeRegionReportByLgd(TENANT, STATE_LGD, START, END),
                repository.getSchemeRegionReportByDepartment(TOP_DEPT, START, END),
                repository.getSchemeRegionReportByDepartment(TENANT, TOP_DEPT, START, END));

        assertThat(reports).allSatisfy(rows -> assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.stateSchemeId()).isEqualTo(STATE_SCHEME_ID);
            assertThat(row.centreSchemeId()).isEqualTo(CENTRE_SCHEME_ID);
        }));
    }
}
