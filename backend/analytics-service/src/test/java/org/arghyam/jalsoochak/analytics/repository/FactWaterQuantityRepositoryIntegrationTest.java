package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
 * Pins {@code deleteReadingDerivedDay}: which of a day's rows go when the day can no longer be
 * calculated from its readings.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FactWaterQuantityRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_water_quantity_test")
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
    private FactWaterQuantityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final int SCHEME = 1;
    private static final int OTHER_SCHEME = 2;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_tenant_table,
                    analytics_schema.dim_date_table
                RESTART IDENTITY CASCADE
                """);
        // fact_water_quantity_table.date is FK-constrained to dim_date_table.full_date (V8).
        for (LocalDate date : List.of(D1, D2)) {
            jdbcTemplate.update("""
                    INSERT INTO analytics_schema.dim_date_table
                    (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                    VALUES (?, ?, 1, 1, 'January', 1, 2026, 1, FALSE, 2026)
                    """, Integer.parseInt(date.toString().replace("-", "")), date);
        }
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW())
                """);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, parent_department_location_id,
                 operating_status, created_at, updated_at)
                VALUES (1, 1, 'Scheme A', 1001, 2001, 100, 200, 1, NOW(), NOW()),
                       (2, 1, 'Scheme B', 1002, 2002, 100, 200, 1, NOW(), NOW())
                """);
    }

    @Test
    void deleteReadingDerivedDay_removesEveryReadingDerivedRowOfTheDay() {
        // No uniqueness on (tenant, scheme, date), so a day can hold duplicates; none may survive
        // holding a total the day no longer has.
        insertRow(SCHEME, D2, 50_000L, null, null);
        insertRow(SCHEME, D2, 50_000L, null, null);

        int removed = repository.deleteReadingDerivedDay(TENANT, SCHEME, D2);

        assertThat(removed).isEqualTo(2);
        assertThat(rowsOn(SCHEME, D2)).isZero();
    }

    @Test
    void deleteReadingDerivedDay_keepsRowsHoldingAReason() {
        insertRow(SCHEME, D2, 0L, "power cut", null);
        insertRow(SCHEME, D2, 0L, null, "meter changed");

        int removed = repository.deleteReadingDerivedDay(TENANT, SCHEME, D2);

        assertThat(removed).isZero();
        assertThat(rowsOn(SCHEME, D2)).isEqualTo(2);
    }

    @Test
    void deleteReadingDerivedDay_leavesOtherDaysAndSchemesAlone() {
        insertRow(SCHEME, D1, 40_000L, null, null);
        insertRow(OTHER_SCHEME, D2, 30_000L, null, null);
        insertRow(SCHEME, D2, 50_000L, null, null);

        repository.deleteReadingDerivedDay(TENANT, SCHEME, D2);

        assertThat(rowsOn(SCHEME, D1)).isEqualTo(1);
        assertThat(rowsOn(OTHER_SCHEME, D2)).isEqualTo(1);
    }

    private void insertRow(int schemeId, LocalDate date, long litres, String outageReason, String nonSubmissionReason) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, submission_status,
                 outage_reason, non_submission_reason, created_at, updated_at)
                VALUES (?, ?, 11, ?, ?, 1, ?, ?, NOW(), NOW())
                """, TENANT, schemeId, litres, date, outageReason, nonSubmissionReason);
    }

    private int rowsOn(int schemeId, LocalDate date) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM analytics_schema.fact_water_quantity_table
                WHERE tenant_id = ? AND scheme_id = ? AND date = ?
                """, Integer.class, TENANT, schemeId, date);
    }
}
