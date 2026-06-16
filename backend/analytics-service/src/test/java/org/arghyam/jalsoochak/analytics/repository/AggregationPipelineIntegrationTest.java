package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the pre-aggregation population (agg_scheme_daily -> region
 * rollups, norm snapshot + efficient-range) and the aggregate read path. Requires
 * Docker (Testcontainers + real Flyway, so V40-V45 are exercised).
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AggregationRepository.class, AggregateReadRepository.class})
class AggregationPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_aggregation_pipeline_test")
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AggregationRepository aggregationRepository;
    @Autowired
    private AggregateReadRepository aggregateReadRepository;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 4); // Sunday
    private static final LocalDate D2 = LocalDate.of(2026, 1, 5); // Monday (same Sun-week)

    @BeforeEach
    void setUp() {
        truncateAll();
        seed();
    }

    @Test
    void schemeDailyUpsert_capturesSupplyEfficientRangeAndNormSnapshot() {
        aggregationRepository.upsertSchemeDaily(D1, D2);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_schema.agg_scheme_daily WHERE tenant_id = 1", Integer.class);
        assertThat(rows).isEqualTo(4);

        // scheme 1 / D1: supplied + efficient (water 10 within [10,10]); norm snapshot captured.
        Map<String, Object> s1 = jdbcTemplate.queryForMap("""
                SELECT supplied, submitted, water_quantity_liters, water_quantity_row_count,
                       confirmed_reading_total, in_efficient_range, snap_required_lpcd
                FROM analytics_schema.agg_scheme_daily
                WHERE tenant_id = 1 AND scheme_id = 1 AND reading_date = ?
                """, D1);
        assertThat(s1.get("supplied")).isEqualTo(1);
        assertThat(s1.get("submitted")).isEqualTo(1);
        assertThat(((Number) s1.get("water_quantity_liters")).longValue()).isEqualTo(10L);
        assertThat(((Number) s1.get("water_quantity_row_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) s1.get("confirmed_reading_total")).longValue()).isEqualTo(10L);
        assertThat(s1.get("in_efficient_range")).isEqualTo(1);
        assertThat(((Number) s1.get("snap_required_lpcd")).intValue()).isEqualTo(1);

        // scheme 2 / D2: confirmed_reading 0 -> not supplied; water 999 -> out of efficient range.
        Map<String, Object> s2 = jdbcTemplate.queryForMap("""
                SELECT supplied, in_efficient_range
                FROM analytics_schema.agg_scheme_daily
                WHERE tenant_id = 1 AND scheme_id = 2 AND reading_date = ?
                """, D2);
        assertThat(s2.get("supplied")).isEqualTo(0);
        assertThat(s2.get("in_efficient_range")).isEqualTo(0);
    }

    @Test
    void regionRollup_day_andWeek_andReadPath() {
        aggregationRepository.upsertSchemeDaily(D1, D2);
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D1, D1, true);
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D2, D2, true);
        aggregationRepository.upsertRegionMetrics(PeriodScale.WEEK, LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 10), true);

        // DAY D1 at LGD level 1, region 1: both schemes supplied + efficient; continuous = 2 (daysInRange = 1).
        Map<String, Object> day1 = jdbcTemplate.queryForMap("""
                SELECT scheme_count, total_supply_days, total_submission_days, total_water_supplied_liters,
                       supply_days_in_efficient_range, continuous_scheme_count
                FROM analytics_schema.agg_region_metrics
                WHERE period_scale = 'DAY' AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, D1);
        assertThat(day1.get("scheme_count")).isEqualTo(2);
        assertThat(day1.get("total_supply_days")).isEqualTo(2);
        assertThat(day1.get("total_submission_days")).isEqualTo(2);
        assertThat(((Number) day1.get("total_water_supplied_liters")).longValue()).isEqualTo(20L);
        assertThat(day1.get("supply_days_in_efficient_range")).isEqualTo(2);
        assertThat(day1.get("continuous_scheme_count")).isEqualTo(2);

        // WEEK aggregate sums both days: supply days = scheme1(2) + scheme2(1) = 3.
        Map<String, Object> week = jdbcTemplate.queryForMap("""
                SELECT total_supply_days, total_submission_days, total_water_supplied_liters
                FROM analytics_schema.agg_region_metrics
                WHERE period_scale = 'WEEK' AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, LocalDate.of(2026, 1, 4));
        assertThat(week.get("total_supply_days")).isEqualTo(3);
        assertThat(week.get("total_submission_days")).isEqualTo(4);
        assertThat(((Number) week.get("total_water_supplied_liters")).longValue()).isEqualTo(1029L);

        // Read path: summing DAY rows over [D1, D2] yields supply days = 3, scheme_count = 2.
        var metrics = aggregateReadRepository.getRegionMetrics(1, "LGD", 1, D1, D2);
        assertThat(metrics).isPresent();
        assertThat(metrics.get().schemeCount()).isEqualTo(2);
        assertThat(metrics.get().totalSupplyDays()).isEqualTo(3L);

        // National read (level-1, cross-tenant): one state row, submitted-water = 10+10+10+999.
        var national = aggregateReadRepository.getNationalRegionMetrics(1, D1, D2);
        assertThat(national).isPresent();
        assertThat(national.get()).hasSize(1);
        var state = national.get().get(0);
        assertThat(state.tenantId()).isEqualTo(1);
        assertThat(state.regionId()).isEqualTo(1);
        assertThat(state.schemeCount()).isEqualTo(2);
        assertThat(state.totalSupplyDays()).isEqualTo(3L);
        assertThat(state.totalWaterSubmittedLiters()).isEqualTo(1029L);
    }

    @Test
    void childRollup_andCriticalCount_fromAggregates() {
        aggregationRepository.upsertSchemeDaily(D1, D2);

        // Both schemes sit under level-1 LGD 1, level-2 LGD 10 -> one child region (10).
        var children = aggregateReadRepository.getChildRegionMetrics(1, "LGD", 1, 1, D1, D2);
        assertThat(children).isPresent();
        assertThat(children.get()).hasSize(1);
        var child = children.get().get(0);
        assertThat(child.regionId()).isEqualTo(10);
        assertThat(child.schemeCount()).isEqualTo(2);
        assertThat(child.totalSupplyDays()).isEqualTo(3L);   // scheme1 (D1,D2) + scheme2 (D1)
        assertThat(child.totalSubmissionDays()).isEqualTo(4L);

        // Critical at cutoff = D2 (Jan 5): scheme1 last supply Jan 5 (not < cutoff),
        // scheme2 last supply Jan 4 (< cutoff) => 1 critical.
        var critical = aggregateReadRepository.getCriticalSchemeCount(1, "LGD", 1, D2);
        assertThat(critical).isPresent();
        assertThat(critical.getAsLong()).isEqualTo(1L);

        // Child reason distribution runs (no reasons seeded => present-but-empty).
        var outageDist = aggregateReadRepository.getChildReasonDistribution(1, "LGD", 1, 1, true, D1, D2);
        assertThat(outageDist).isPresent();
        assertThat(outageDist.get()).isEmpty();

        // Per-scheme water supply uses confirmed-reading totals.
        var schemes = aggregateReadRepository.getSchemeWaterSupply(1, D1, D2);
        assertThat(schemes).isPresent();
        assertThat(schemes.get()).hasSize(2);
        var s1 = schemes.get().stream().filter(r -> r.schemeId() == 1).findFirst().orElseThrow();
        assertThat(s1.totalConfirmedReading()).isEqualTo(20L); // 10 (D1) + 10 (D2)
        assertThat(s1.supplyDays()).isEqualTo(2);
        var s2 = schemes.get().stream().filter(r -> r.schemeId() == 2).findFirst().orElseThrow();
        assertThat(s2.totalConfirmedReading()).isEqualTo(10L); // 10 (D1); D2 confirmed 0
        assertThat(s2.supplyDays()).isEqualTo(1);
    }

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE
                    analytics_schema.agg_region_distribution,
                    analytics_schema.agg_region_metrics,
                    analytics_schema.agg_scheme_daily,
                    analytics_schema.dim_tenant_water_norm,
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_tenant_table,
                    analytics_schema.dim_date_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void insertDate(LocalDate date) {
        int dateKey = Integer.parseInt(date.toString().replace("-", ""));
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, EXTRACT(DAY FROM ?::date), EXTRACT(MONTH FROM ?::date),
                        EXTRACT(YEAR FROM ?::date), EXTRACT(WEEK FROM ?::date),
                        EXTRACT(ISODOW FROM ?::date) IN (6,7), EXTRACT(YEAR FROM ?::date))
                """, dateKey, date, date, date, date, date, date, date);
    }

    private void seed() {
        // fact_water_quantity.date has a FK to dim_date_table.
        insertDate(D1);
        insertDate(D2);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at, updated_at)
                VALUES (1, 't1', 'Tenant 1', 'IN', 1, 1, 1, 0, 0, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_water_norm
                (tenant_id, effective_from, effective_to, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at)
                VALUES (1, DATE '2020-01-01', NULL, 1, 1, 0, 0, NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11')
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'S1', 1001, 2001, 0.0, 0.0, 1, 1, 10, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'S2', 1002, 2002, 0.0, 0.0, 1, 1, 10, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW())
                """);

        // Meter readings: scheme1 supplies both days; scheme2 supplies D1 only (conf 0 on D2).
        insertReading(1, 10, D1);
        insertReading(1, 10, D2);
        insertReading(2, 10, D1);
        insertReading(2, 0, D2);

        // Water quantity: efficient (10) except scheme2/D2 (999, out of range).
        insertWaterQuantity(1, 10, D1);
        insertWaterQuantity(1, 10, D2);
        insertWaterQuantity(2, 10, D1);
        insertWaterQuantity(2, 999, D2);
    }

    private void insertReading(int schemeId, int confirmed, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url,
                 reading_at, channel, reading_date, created_at, submission_status, reading_type)
                VALUES (1, ?, 11, ?, ?, 90, 'x', NOW(), 1, ?, NOW(), 1, 0)
                """, schemeId, confirmed, confirmed, date);
    }

    private void insertWaterQuantity(int schemeId, int quantity, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, submission_status)
                VALUES (1, ?, 11, ?, ?, NOW(), 1)
                """, schemeId, quantity, date);
    }
}
