package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({TenantBoundaryRepository.class, TenantDepartmentBoundaryRepository.class})
class BoundaryRepositoriesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("boundary_test")
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
    private TenantBoundaryRepository tenantBoundaryRepository;

    @Autowired
    private TenantDepartmentBoundaryRepository tenantDepartmentBoundaryRepository;

    @BeforeEach
    void setUp() {
        truncateAnalytics();
        seedAnalyticsTables();
    }

    @Test
    void tenantBoundaryRepository_allMethods_workIncludingValidation() {
        Map<String, Object> merged = tenantBoundaryRepository.getMergedBoundaryForTenant(1);
        Integer level = tenantBoundaryRepository.getLocationLevel(101);
        List<Map<String, Object>> children = tenantBoundaryRepository.getChildLevelByParent(1, 100, 1);
        Map<String, Object> mergedByParent = tenantBoundaryRepository.getMergedBoundaryByParent(1, 100, 1);

        assertThat(((Number) merged.get("boundary_count")).intValue()).isEqualTo(2);
        assertThat(merged.get("boundary_geojson")).isNotNull();
        assertThat(level).isEqualTo(2);
        assertThat(children).hasSize(2);
        assertThat(((Number) children.get(0).get("lgd_id")).intValue()).isEqualTo(101);
        assertThat(((Number) children.get(0).get("scheme_count")).intValue()).isEqualTo(1);
        assertThat(((Number) children.get(1).get("lgd_id")).intValue()).isEqualTo(102);
        assertThat(((Number) children.get(1).get("scheme_count")).intValue()).isEqualTo(1);
        assertThat(((Number) mergedByParent.get("child_count")).intValue()).isEqualTo(2);
        assertThat(mergedByParent.get("boundary_geojson")).isNotNull();

        assertThatThrownBy(() -> tenantBoundaryRepository.getMergedBoundaryForTenant(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id must be a positive integer");
        assertThatThrownBy(() -> tenantBoundaryRepository.getLocationLevel(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lgd_id must be a positive integer");
        assertThatThrownBy(() -> tenantBoundaryRepository.getChildLevelByParent(1, 100, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child LGD level available");
        assertThatThrownBy(() -> tenantBoundaryRepository.getMergedBoundaryByParent(1, 100, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child LGD level available");
    }

    @Test
    void tenantDepartmentBoundaryRepository_allMethods_workIncludingValidation() {
        Integer level = tenantDepartmentBoundaryRepository.getDepartmentLevel(1, 200);
        List<Map<String, Object>> children =
                tenantDepartmentBoundaryRepository.getChildDepartmentsByParent(1, 200, 1);
        Map<String, Object> merged =
                tenantDepartmentBoundaryRepository.getMergedBoundaryByParentDepartment(1, 200, 1);

        assertThat(level).isEqualTo(1);
        assertThat(children).hasSize(2);
        assertThat(((Number) children.get(0).get("department_id")).intValue()).isEqualTo(201);
        assertThat(((Number) children.get(0).get("scheme_count")).intValue()).isEqualTo(1);
        assertThat(((Number) children.get(1).get("department_id")).intValue()).isEqualTo(202);
        assertThat(((Number) children.get(1).get("scheme_count")).intValue()).isEqualTo(1);
        assertThat(((Number) merged.get("child_count")).intValue()).isEqualTo(2);
        assertThat(merged.get("boundary_geojson")).isNotNull();

        assertThat(tenantDepartmentBoundaryRepository.tableExists("analytics_schema", "dim_department_location_table")).isTrue();
        assertThat(tenantDepartmentBoundaryRepository.tableExists("analytics_schema", "missing_table")).isFalse();
        assertThat(tenantDepartmentBoundaryRepository.columnExists("analytics_schema", "dim_scheme_table", "level_2_dept_id")).isTrue();
        assertThat(tenantDepartmentBoundaryRepository.columnExists("analytics_schema", "dim_scheme_table", "missing_col")).isFalse();

        assertThatThrownBy(() -> tenantDepartmentBoundaryRepository.getChildDepartmentsByParent(1, 200, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported department level");
        assertThatThrownBy(() -> tenantDepartmentBoundaryRepository.tableExists("analytics-schema", "dim_scheme_table"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
        assertThatThrownBy(() -> tenantDepartmentBoundaryRepository.columnExists("analytics_schema", "dim_scheme_table", "bad-col"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid table/column name");
    }

    private void truncateAnalytics() {
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
    }

    private void seedAnalyticsTables() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES
                (100, 1, 'P100', 'Parent', 'Parent LGD', 1, 100, NULL, NULL, NULL, NULL, NULL,
                 ST_GeomFromText('POINT(77 28)', 4326), NOW(), NOW()),
                (101, 1, 'C101', 'ChildA', 'Child LGD A', 2, 100, 101, NULL, NULL, NULL, NULL,
                 ST_GeomFromText('POINT(77.1 28.1)', 4326), NOW(), NOW()),
                (102, 1, 'C102', 'ChildB', 'Child LGD B', 2, 100, 102, NULL, NULL, NULL, NULL,
                 ST_GeomFromText('POINT(77.2 28.2)', 4326), NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at, geom)
                VALUES
                (200, 1, 'Parent Dept', 'Parent Dept', 1, 200, NULL, NULL, NULL, NULL, NULL, NOW(), NOW(), ST_GeomFromText('POINT(77 28)', 4326)),
                (201, 1, 'Child Dept A', 'Child Dept A', 2, 200, 201, NULL, NULL, NULL, NULL, NOW(), NOW(), ST_GeomFromText('POINT(77.1 28.1)', 4326)),
                (202, 1, 'Child Dept B', 'Child Dept B', 2, 200, 202, NULL, NULL, NULL, NULL, NOW(), NOW(), ST_GeomFromText('POINT(77.2 28.2)', 4326))
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Scheme A', 1001, 2001, 0.0, 0.0, 100, 100, 101, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Scheme B', 1002, 2002, 0.0, 0.0, 100, 100, 102, NULL, NULL, NULL, NULL, 200, 200, 202, NULL, NULL, NULL, NULL, 1, 20, 20, 20, NOW(), NOW())
                """);
    }
}
