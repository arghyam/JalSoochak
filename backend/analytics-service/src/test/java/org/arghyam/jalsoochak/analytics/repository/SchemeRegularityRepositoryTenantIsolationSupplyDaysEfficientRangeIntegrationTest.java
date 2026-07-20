package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryTenantIsolationSupplyDaysEfficientRangeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_supply_days_efficient_range_tenant_iso_test")
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
        // Aggregation-focused IT: disable the dashboard work_status filter so seeded schemes
        // (which do not all carry the handed-over work_status) are included. Filter behaviour is
        // covered separately by SchemeRegularityRepositoryWorkStatusFilterIntegrationTest.
        registry.add("analytics.dashboard.included-work-statuses", () -> "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private SchemeRegularityRepository repository;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);

    @BeforeEach
    void setUp() {
        truncateAnalytics();
        seedDates();
        seedTenantsWithTightEfficientRange();
        seedSchemesWithDuplicateSchemeIdsAcrossTenants();
        seedWaterQuantityThatWouldBreakIfTenantsMixed();
    }

    @Test
    void getTenantWiseSupplyDaysInEfficientRange_isTenantIsolated_evenWhenSchemeIdsOverlap() {
        List<SchemeRegularityRepository.TenantSupplyDaysInEfficientRange> rows =
                repository.getTenantWiseSupplyDaysInEfficientRange(D1, D2);

        Map<Integer, Long> byTenant = rows.stream()
                .collect(Collectors.toMap(
                        SchemeRegularityRepository.TenantSupplyDaysInEfficientRange::tenantId,
                        SchemeRegularityRepository.TenantSupplyDaysInEfficientRange::supplyDaysInEfficientRange
                ));

        // daysInRange=2, each tenant has 2 schemes => max=4.
        // Tenant 1 is always in range (efficient) => 4.
        // Tenant 2 is always out of range => 0.
        assertThat(byTenant.get(1)).isEqualTo(4L);
        assertThat(byTenant.get(2)).isEqualTo(0L);
    }

    private void truncateAnalytics() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_scheme_table,
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
                (20260102, ?, 2, 1, 2026, 1, false, 2026)
                """, D1, D2);
    }

    private void seedTenantsWithTightEfficientRange() {
        // Efficient target = required_lpcd(1) * fhtc_count(10) * people(1) = 10, +/-0%
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at, updated_at)
                VALUES
                (1, 't1', 'Tenant 1', 'IN', 1, 1, 1, 0, 0, NOW(), NOW()),
                (2, 't2', 'Tenant 2', 'IN', 1, 1, 1, 0, 0, NOW(), NOW())
                """);

        // Required by some migrations/constraints for fact inserts.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES
                (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11'),
                (21, 2, 'u21@test.local', 1, NOW(), NOW(), 'User 21')
                """);
    }

    private void seedSchemesWithDuplicateSchemeIdsAcrossTenants() {
        // Duplicate scheme_id across tenants to simulate real scenario where scheme_id isn't globally unique.
        // Keep LGD columns populated (required by schema) but values don't matter for this query.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'S1-T1', 1001, 2001, 0.0, 0.0, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'S2-T1', 1002, 2002, 0.0, 0.0, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (1, 2, 'S1-T2', 2001, 3001, 0.0, 0.0, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 2, 'S2-T2', 2002, 3002, 0.0, 0.0, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW())
                """);
    }

    private void seedWaterQuantityThatWouldBreakIfTenantsMixed() {
        // Tenant 1: exactly efficient (10) on both days for both schemes.
        // Tenant 2: out of range (999) on both days for both schemes.
        for (LocalDate d : List.of(D1, D2)) {
            jdbcTemplate.update("""
                    INSERT INTO analytics_schema.fact_water_quantity_table
                    (tenant_id, scheme_id, user_id, water_quantity, date, created_at)
                    VALUES
                    (1, 1, 11, 10, ?, NOW()),
                    (1, 2, 11, 10, ?, NOW()),
                    (2, 1, 21, 999, ?, NOW()),
                    (2, 2, 21, 999, ?, NOW())
                    """, d, d, d, d);
        }
    }
}

