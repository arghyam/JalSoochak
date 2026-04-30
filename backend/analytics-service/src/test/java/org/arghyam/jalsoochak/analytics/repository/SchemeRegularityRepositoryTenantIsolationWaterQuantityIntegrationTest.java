package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryTenantIsolationWaterQuantityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_water_quantity_tenant_iso_test")
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

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);

    @BeforeEach
    void setUp() {
        truncateAnalytics();
        seedDates();
        seedTenants();
        seedLgdHierarchyForTwoTenants();
        seedSchemesTwoTenantsWithSameLgdIds();
        seedWaterQuantityForBothTenants();
    }

    @Test
    void getRegionWiseWaterQuantityByLgd_filtersSchemesByTenantId_soEfficientDaysCannotIncludeOtherTenants() {
        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> rows =
                repository.getRegionWiseWaterQuantityByLgd(1, 1, D1, D3);

        SchemeRegularityRepository.ChildRegionWaterQuantityMetrics child2 =
                rows.stream().filter(r -> r.lgdId().equals(2)).findFirst().orElseThrow();

        // daysInRange=3. Tenant 1 has 2 schemes under child_lgd_id=2, and we seed all 3 days as efficient
        // for those tenant 1 schemes. So expected = 2 * 3 = 6. If other-tenant schemes leaked in, it would be 12.
        assertThat(child2.supplyDaysInEfficientRange()).isEqualTo(6L);
    }

    private void truncateAnalytics() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_tenant_table,
                    analytics_schema.dim_date_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seedDates() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, year, week, is_weekend, fiscal_year)
                VALUES
                (20260101, ?, 1, 1, 2026, 1, false, 2026),
                (20260102, ?, 2, 1, 2026, 1, false, 2026),
                (20260103, ?, 3, 1, 2026, 1, false, 2026)
                """, D1, D2, D3);
    }

    private void seedTenants() {
        // Set tight efficient range: required_lpcd=1, people/household=1, and +/-0% range.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at, updated_at)
                VALUES
                (1, 't1', 'Tenant 1', 'IN', 1, 1, 1, 0, 0, NOW(), NOW()),
                (2, 't2', 'Tenant 2', 'IN', 1, 1, 1, 0, 0, NOW(), NOW())
                """);

        // Only needed due to dim_user_table constraints in some migrations.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES
                (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11'),
                (21, 2, 'u21@test.local', 1, NOW(), NOW(), 'User 21')
                """);
    }

    private void seedLgdHierarchyForTwoTenants() {
        // Parent LGD (level 1): id=1. Child LGD (level 2): id=2.
        // We insert both for tenant 1 and tenant 2 to mimic real data where the same LGD ids appear per tenant.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 created_at, updated_at)
                VALUES
                (1, 1, 'State (T1)', 1, 1, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (2, 1, 'District (T1)', 2, 1, 2, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (1, 2, 'State (T2)', 1, 1, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (2, 2, 'District (T2)', 2, 1, 2, NULL, NULL, NULL, NULL, NOW(), NOW())
                """);
    }

    private void seedSchemesTwoTenantsWithSameLgdIds() {
        // Keep scheme_id duplicated across tenants to ensure missing tenant filters will inflate counts.
        // Put all schemes under level_1_lgd_id=1 and level_2_lgd_id=2.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Scheme 1 (T1)', 1001, 2001, 0.0, 0.0, 1, 1, 2, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Scheme 2 (T1)', 1002, 2002, 0.0, 0.0, 1, 1, 2, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (1, 2, 'Scheme 1 (T2)', 2001, 3001, 0.0, 0.0, 1, 1, 2, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 2, 'Scheme 2 (T2)', 2002, 3002, 0.0, 0.0, 1, 1, 2, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW())
                """);
    }

    private void seedWaterQuantityForBothTenants() {
        // With tenant cfg: required_lpcd=1, people=1, +/-0%, and fhtc_count=10, the efficient target is exactly 10.
        // We record water_quantity=10 for all schemes across all 3 days.
        for (LocalDate d : List.of(D1, D2, D3)) {
            jdbcTemplate.update("""
                    INSERT INTO analytics_schema.fact_water_quantity_table
                    (tenant_id, scheme_id, user_id, water_quantity, date, created_at)
                    VALUES
                    (1, 1, 11, 10, ?, NOW()),
                    (1, 2, 11, 10, ?, NOW()),
                    (2, 1, 21, 10, ?, NOW()),
                    (2, 2, 21, 10, ?, NOW())
                    """, d, d, d, d);
        }
    }
}

