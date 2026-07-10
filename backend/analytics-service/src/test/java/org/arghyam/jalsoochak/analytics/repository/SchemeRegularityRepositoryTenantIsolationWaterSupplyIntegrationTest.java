package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryTenantIsolationWaterSupplyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_water_supply_tenant_iso_test")
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
        seedTwoTenants();
        seedSchemesTwoTenants();
        insertDate(D1);
        insertDate(D2);
        insertDate(D3);
        seedMeterReadingsForBothTenants();
        seedWaterQuantityForBothTenants();
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_filtersByTenantId_inBothSchemeAndFactJoin() {
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> tenant1Rows =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3);

        assertThat(tenant1Rows).hasSize(2);

        SchemeRegularityRepository.SchemeWaterSupplyMetrics s1 =
                tenant1Rows.stream().filter(r -> r.schemeId().equals(1)).findFirst().orElseThrow();
        SchemeRegularityRepository.SchemeWaterSupplyMetrics s2 =
                tenant1Rows.stream().filter(r -> r.schemeId().equals(2)).findFirst().orElseThrow();

        // Tenant 1 scheme 1 water from fact_water_quantity_table (SUBMITTED only): D1 (30) + D3 (20) => 50.
        // The D2 NOT_SUBMITTED row (1000) is excluded. supply_days=2 (meter positives at D1, D3).
        assertThat(s1.totalWaterSuppliedLiters()).isEqualTo(50L);
        assertThat(s1.supplyDays()).isEqualTo(2);
        // house_hold_count=10, daysInRange=3 => 50 / 30 => 1.6667
        assertThat(s1.averageLitersPerHousehold()).isEqualByComparingTo("1.6667");

        // Tenant 1 scheme 2 has no water quantity rows => totals remain 0.
        assertThat(s2.totalWaterSuppliedLiters()).isEqualTo(0L);
        assertThat(s2.supplyDays()).isEqualTo(0);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_whenCalledForTenant2_returnsTenant2NumbersOnly() {
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> tenant2Rows =
                repository.getAverageWaterSupplyPerCurrentRegion(2, D1, D3);

        assertThat(tenant2Rows).hasSize(2);

        SchemeRegularityRepository.SchemeWaterSupplyMetrics s1 =
                tenant2Rows.stream().filter(r -> r.schemeId().equals(1)).findFirst().orElseThrow();

        // Tenant 2 scheme 1 water from fact_water_quantity_table (SUBMITTED): D1 (80) => 80. supply_days=1.
        assertThat(s1.totalWaterSuppliedLiters()).isEqualTo(80L);
        assertThat(s1.supplyDays()).isEqualTo(1);
        // house_hold_count=10, daysInRange=3 => 80 / 30 => 2.6667
        assertThat(s1.averageLitersPerHousehold()).isEqualByComparingTo("2.6667");
    }

    @Test
    void getPeriodicSchemeRegularityForNation_includesMeterReadingsAcrossTenants() {
        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> rows =
                repository.getPeriodicSchemeRegularityForNation(D1, D3, PeriodScale.DAY);

        assertThat(rows).hasSize(3);

        assertThat(rows.get(0).periodStartDate()).isEqualTo(D1);
        assertThat(rows.get(0).periodEndDate()).isEqualTo(D1);
        assertThat(rows.get(0).schemeCount()).isEqualTo(4);
        assertThat(rows.get(0).totalSupplyDays()).isEqualTo(2);
        assertThat(rows.get(0).totalWaterQuantity()).isEqualTo(110L);

        assertThat(rows.get(1).periodStartDate()).isEqualTo(D2);
        assertThat(rows.get(1).periodEndDate()).isEqualTo(D2);
        assertThat(rows.get(1).schemeCount()).isEqualTo(4);
        assertThat(rows.get(1).totalSupplyDays()).isEqualTo(0);
        assertThat(rows.get(1).totalWaterQuantity()).isEqualTo(0L);

        assertThat(rows.get(2).periodStartDate()).isEqualTo(D3);
        assertThat(rows.get(2).periodEndDate()).isEqualTo(D3);
        assertThat(rows.get(2).schemeCount()).isEqualTo(4);
        assertThat(rows.get(2).totalSupplyDays()).isEqualTo(1);
        // total_water_quantity now sums fact_water_quantity (SUBMITTED/NULL): D3 tenant1 scheme1 SUBMITTED = 20.
        assertThat(rows.get(2).totalWaterQuantity()).isEqualTo(20L);
    }

    private void truncateAnalytics() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_date_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seedTwoTenants() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES
                (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW()),
                (2, 'up', 'Uttar Pradesh', 'IN', 1, NOW(), NOW())
                """);

        // Only needed for user_id NOT NULL.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES
                (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11'),
                (21, 2, 'u21@test.local', 1, NOW(), NOW(), 'User 21')
                """);
    }

    private void seedSchemesTwoTenants() {
        // Keep scheme_id duplicated across tenants to ensure tenant join clauses are necessary.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Scheme A', 1001, 2001, 0.0, 0.0, 100, 100, 101, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Scheme B', 1002, 2002, 0.0, 0.0, 100, 100, 102, NULL, NULL, NULL, NULL, 200, 200, 202, NULL, NULL, NULL, NULL, 0, 20, 20, 20, NOW(), NOW()),
                (1, 2, 'Scheme A (UP)', 2001, 3001, 0.0, 0.0, 100, 100, 101, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 2, 'Scheme B (UP)', 2002, 3002, 0.0, 0.0, 100, 100, 102, NULL, NULL, NULL, NULL, 200, 200, 202, NULL, NULL, NULL, NULL, 0, 20, 20, 20, NOW(), NOW())
                """);
    }

    private void seedMeterReadingsForBothTenants() {
        // Tenant 1 scheme 1:
        // D1 => +10, D2 => 0, D3 => +5 => total 15, supply_days=2.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 10, 10, 90, "x", 1, D1, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 0, 0, 90, "x", 1, D2, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 5, 5, 90, "x", 1, D3, 1, 0);

        // Tenant 2 scheme 1:
        // D1 => +100, D2 => 0, D3 => 0 => total 100, supply_days=1.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                2, 1, 21, 100, 100, 90, "x", 1, D1, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                2, 1, 21, 0, 0, 90, "x", 1, D2, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                2, 1, 21, 0, 0, 90, "x", 1, D3, 1, 0);
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

    private void seedWaterQuantityForBothTenants() {
        // Tenant 1 scheme 1: D1=30 SUBMITTED, D2=1000 NOT_SUBMITTED (excluded), D3=20 SUBMITTED => 50 supplied.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 30, D1, SubmissionStatus.SUBMITTED.getCode(), null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 1000, D2, SubmissionStatus.NOT_SUBMITTED.getCode(), "no_electricity", "network_issue");

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 20, D3, SubmissionStatus.SUBMITTED.getCode(), null, null);

        // Tenant 2 scheme 1: D1=80 SUBMITTED => 80 supplied. Confirms the tenant_id filter isolates tenants.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 2, 1, 21, 80, D1, SubmissionStatus.SUBMITTED.getCode(), null, null);
    }
}

