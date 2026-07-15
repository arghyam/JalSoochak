package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryEdgeCasesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_edge_cases_test")
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
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);

    @BeforeEach
    void setUp() {
        truncateAnalytics();
        seedDimensionsSingleTenant();
    }

    @Test
    void getSchemeRegularityMetrics_duplicateWaterRowsOnSameDay_doNotIncreaseSupplyDays() {
        // Regularity supply days come from fact_water_quantity, de-duplicated to the latest row per
        // (scheme, date). A supply day = SUBMITTED/legacy-NULL row with water_quantity > 0.
        // scheme 1: two SUBMITTED rows on D1 (duplicate) + a SUBMITTED row on D3 => 2 supply days.
        seedWaterQuantity(1, 11, D1, 100, SubmissionStatus.SUBMITTED.getCode());
        seedWaterQuantity(1, 11, D1, 120, SubmissionStatus.SUBMITTED.getCode()); // duplicate day, must not add
        seedWaterQuantity(1, 11, D3, 80, SubmissionStatus.SUBMITTED.getCode());
        // scheme 2: only a NOT_SUBMITTED (outage) row => 0 supply days.
        seedWaterQuantity(2, 12, D1, 0, SubmissionStatus.NOT_SUBMITTED.getCode());

        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                repository.getSchemeRegularityMetrics(100, D1, D3);

        assertThat(metrics.schemeCount()).isEqualTo(2);
        // scheme 1: D1 (dedup) + D3 => 2; scheme 2: 0 => total 2.
        assertThat(metrics.totalSupplyDays()).isEqualTo(2);
    }

    @Test
    void getReadingSubmissionRateMetricsByLgd_duplicateConfirmedReadingsOnSameDay_doesNotIncreaseSubmissionDays() {
        seedDuplicateMeterReadingOnSameDay();

        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                repository.getReadingSubmissionRateMetricsByLgd(100, D1, D3);

        // submission days are distinct reading_date with confirmed_reading >= 0.
        // scheme 1: D1 (duplicate), D2 (0), D3 => 3 submission days
        // scheme 2: D2 (0) only => 1 submission day
        assertThat(metrics.schemeCount()).isEqualTo(2);
        assertThat(metrics.totalSupplyDays()).isEqualTo(4);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_emptyMeterReadings_returnsZeroAverages() {
        // No meter readings inserted on purpose.
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> rows =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3);

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().schemeId()).isEqualTo(1);
        assertThat(rows.getFirst().totalWaterSuppliedLiters()).isEqualTo(0L);
        assertThat(rows.getFirst().supplyDays()).isEqualTo(0);
        assertThat(rows.getFirst().averageLitersPerHousehold()).isEqualByComparingTo("0.0000");

        assertThat(rows.get(1).schemeId()).isEqualTo(2);
        assertThat(rows.get(1).totalWaterSuppliedLiters()).isEqualTo(0L);
        assertThat(rows.get(1).supplyDays()).isEqualTo(0);
        assertThat(rows.get(1).averageLitersPerHousehold()).isEqualByComparingTo("0.0000");
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_negativeDateRange_returnsEmptyList() {
        // start > end => daysInRange <= 0 => repository must return empty list.
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> rows =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D3, D1);

        assertThat(rows).isEmpty();
    }

    @Test
    void getSchemeRegularityMetrics_lateArrivingWaterSupply_increasesSupplyDays() {
        // Initial telemetry: scheme 1 supplied water only on D1 (SUBMITTED).
        seedWaterQuantity(1, 11, D1, 100, SubmissionStatus.SUBMITTED.getCode());

        SchemeRegularityRepository.SchemeRegularityMetrics first =
                repository.getSchemeRegularityMetrics(100, D1, D3);

        // scheme_count=2 (two schemes under LGD=100), but only scheme 1 has supply days.
        assertThat(first.schemeCount()).isEqualTo(2);
        assertThat(first.totalSupplyDays()).isEqualTo(1);

        // Late-arriving telemetry: same scheme supplies water on D2.
        seedWaterQuantity(1, 11, D2, 120, SubmissionStatus.SUBMITTED.getCode());

        SchemeRegularityRepository.SchemeRegularityMetrics second =
                repository.getSchemeRegularityMetrics(100, D1, D3);

        assertThat(second.schemeCount()).isEqualTo(2);
        assertThat(second.totalSupplyDays()).isEqualTo(2);
    }

    @Test
    void getReadingSubmissionRateMetricsByLgd_lateArrivingZeroConfirmedReading_increasesSubmissionDays() {
        // Initial telemetry: scheme 1 has confirmed_reading=0 on D1 (counts as submission day).
        seedSingleMeterReadingOnDate(1, 11, D1, 0);

        SchemeRegularityRepository.SchemeRegularityMetrics first =
                repository.getReadingSubmissionRateMetricsByLgd(100, D1, D3);

        // submission_days: scheme 1 => {D1}, scheme 2 => {D1? none} => 1 total submission day
        assertThat(first.schemeCount()).isEqualTo(2);
        assertThat(first.totalSupplyDays()).isEqualTo(1);

        // Late-arriving telemetry: a new day appears for the same scheme.
        seedSingleMeterReadingOnDate(1, 11, D2, 0);

        SchemeRegularityRepository.SchemeRegularityMetrics second =
                repository.getReadingSubmissionRateMetricsByLgd(100, D1, D3);

        assertThat(second.schemeCount()).isEqualTo(2);
        assertThat(second.totalSupplyDays()).isEqualTo(2);
    }

    @Test
    void getSchemeRegularityMetrics_missingParentLgd_throws() {
        assertThatThrownBy(() -> repository.getSchemeRegularityMetrics(999, D1, D3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_lgd_id not found in dim_lgd_location_table");
    }

    @Test
    void getReadingSubmissionRateMetricsByDepartment_missingParentDepartment_throws() {
        assertThatThrownBy(() -> repository.getReadingSubmissionRateMetricsByDepartment(999, D1, D3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id not found in dim_department_location_table");
    }

    @Test
    void getSchemeRegularityMetrics_inclusiveRange_startEqualsEnd_countsOnlyThatDay() {
        seedWaterQuantity(1, 11, D2, 100, SubmissionStatus.SUBMITTED.getCode());

        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                repository.getSchemeRegularityMetrics(100, D2, D2);

        assertThat(metrics.schemeCount()).isEqualTo(2);
        // scheme 1 supplied water on D2; scheme 2 has none => totalSupplyDays=1
        assertThat(metrics.totalSupplyDays()).isEqualTo(1);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_nonPositiveHouseHoldCount_doesNotDivideByZero() {
        // Make all schemes have non-positive house_hold_count so schemes_in_tenant becomes empty.
        jdbcTemplate.update("""
                UPDATE analytics_schema.dim_scheme_table
                SET house_hold_count = 0
                WHERE tenant_id = ?
                """, 1);

        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> rows =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3);

        assertThat(rows).isEmpty();
    }

    private void seedDuplicateMeterReadingOnSameDay() {
        // scheme_id=1 under tenant 1, user 11
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 10, 10, 90, "x", 1, D1, 1, 0);

        // Duplicate row for the same reading_date (D1). Distinct-day counting must ignore it.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 12, 11, 90, "x", 1, D1, 1, 0);

        // scheme 1: confirmed_reading=0 should count as submission day, but not as supply day
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 0, 0, 90, "x", 1, D2, 1, 0);

        // scheme 1: positive confirmed_reading
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 1, 11, 5, 5, 90, "x", 1, D3, 1, 0);

        // scheme 2: negative at D1 => does not count as supply/submission day
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 2, 12, -1, -1, 90, "x", 1, D1, 1, 0);

        // scheme 2: confirmed_reading=0 at D2 => counts as submission day, but not supply day
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, 2, 12, 0, 0, 90, "x", 1, D2, 1, 0);
    }

    private void seedDimensionsSingleTenant() {
        // Tenant
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW())
                """);

        // Users (only needed because fact table schema expects user_id NOT NULL)
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES
                (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11'),
                (12, 1, 'u12@test.local', 1, NOW(), NOW(), 'User 12')
                """);

        // Parent LGD and its children (for getLgdLevel + scheme LGD mapping).
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES
                (100, 1, 'L100', 'Parent', 'Parent LGD', 1, 100, NULL, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (101, 1, 'L101', 'ChildA', 'Child A', 2, 100, 101, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (102, 1, 'L102', 'ChildB', 'Child B', 2, 100, 102, NULL, NULL, NULL, NULL, NULL, NOW(), NOW())
                """);

        // Parent department and its children (for scheme dept mapping).
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

        // Schemes under LGD=100 (level_1_lgd_id) and dept=200
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Scheme A', 1001, 2001, 0.0, 0.0, 100, 100, 101, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Scheme B', 1002, 2002, 0.0, 0.0, 100, 100, 102, NULL, NULL, NULL, NULL, 200, 200, 202, NULL, NULL, NULL, NULL, 0, 20, 20, 20, NOW(), NOW())
                """);

        // fact_water_quantity_table.date has a FK to dim_date_table (V8); seed the dates used here.
        insertDate(D1);
        insertDate(D2);
        insertDate(D3);
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

    private void seedWaterQuantity(
            int schemeId, int userId, LocalDate date, int waterQuantity, int submissionStatus) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?)
                """,
                1, schemeId, userId, waterQuantity, date, submissionStatus);
    }

    private void seedSingleMeterReadingOnDate(
            int schemeId, int userId, LocalDate readingDate, int confirmedReading) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """,
                1, schemeId, userId, confirmedReading, confirmedReading, 90, "x",
                1, readingDate, 1, 0);
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
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
    }
}

