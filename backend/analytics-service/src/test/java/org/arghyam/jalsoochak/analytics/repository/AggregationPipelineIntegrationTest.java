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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the pre-aggregation population (fact_scheme_daily_table -> region
 * rollups, norm snapshot + efficient-range) and the aggregate read path. Requires
 * Docker (Testcontainers + real Flyway, so the analytics migrations are exercised).
 *
 * <p>Water semantics under test are the unified supplied-water rule: only the latest
 * water row per scheme/day with submission_status SUBMITTED(1)-or-NULL and quantity &gt; 0
 * counts; {@code supplied} mirrors the same rule (a NOT_SUBMITTED positive quantity is
 * neither water nor a supply day).</p>
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
        // Env tier of the work-status filter off: base pipeline tests assert unfiltered
        // aggregation; the filter tests below configure the SCD-2 history tiers instead
        // (which take precedence over env regardless of this setting).
        registry.add("analytics.dashboard.included-work-statuses", () -> "");
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
                "SELECT COUNT(*) FROM analytics_schema.fact_scheme_daily_table WHERE tenant_id = 1", Integer.class);
        assertThat(rows).isEqualTo(4);

        // scheme 1 / D1: supplied + efficient (water 10 within [10,10]); norm snapshot captured.
        Map<String, Object> s1 = jdbcTemplate.queryForMap("""
                SELECT supplied, submitted, water_supplied_liters,
                       is_supply_efficient, norm_required_lpcd
                FROM analytics_schema.fact_scheme_daily_table
                WHERE tenant_id = 1 AND scheme_id = 1 AND reading_date = ?
                """, D1);
        assertThat(s1.get("supplied")).isEqualTo(1);
        assertThat(s1.get("submitted")).isEqualTo(1);
        assertThat(((Number) s1.get("water_supplied_liters")).longValue()).isEqualTo(10L);
        assertThat(s1.get("is_supply_efficient")).isEqualTo(1);
        assertThat(((Number) s1.get("norm_required_lpcd")).intValue()).isEqualTo(1);

        // scheme 2 / D1: SUBMITTED 999 -> supplied but out of the efficient band.
        Map<String, Object> s2d1 = jdbcTemplate.queryForMap("""
                SELECT supplied, water_supplied_liters, is_supply_efficient
                FROM analytics_schema.fact_scheme_daily_table
                WHERE tenant_id = 1 AND scheme_id = 2 AND reading_date = ?
                """, D1);
        assertThat(s2d1.get("supplied")).isEqualTo(1);
        assertThat(((Number) s2d1.get("water_supplied_liters")).longValue()).isEqualTo(999L);
        assertThat(s2d1.get("is_supply_efficient")).isEqualTo(0);

        // scheme 2 / D2: NOT_SUBMITTED 500 -> excluded by the unified rule (no water, no supply day).
        Map<String, Object> s2 = jdbcTemplate.queryForMap("""
                SELECT supplied, water_supplied_liters, is_supply_efficient
                FROM analytics_schema.fact_scheme_daily_table
                WHERE tenant_id = 1 AND scheme_id = 2 AND reading_date = ?
                """, D2);
        assertThat(s2.get("supplied")).isEqualTo(0);
        assertThat(((Number) s2.get("water_supplied_liters")).longValue()).isEqualTo(0L);
        assertThat(s2.get("is_supply_efficient")).isEqualTo(0);
    }

    @Test
    void regionRollup_day_andWeek_andReadPath() {
        aggregationRepository.upsertSchemeDaily(D1, D2);
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D1, D1, true);
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D2, D2, true);
        aggregationRepository.upsertRegionMetrics(PeriodScale.WEEK, LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 10), true);

        // DAY D1 at LGD level 1, region 1: both schemes supplied (s1 10, s2 999); only s1 efficient;
        // continuous = 2 (daysInRange = 1).
        Map<String, Object> day1 = jdbcTemplate.queryForMap("""
                SELECT scheme_count, total_supply_days, total_submission_days, total_water_supplied_liters,
                       supply_days_in_efficient_range, continuous_scheme_count
                FROM analytics_schema.fact_region_metrics_table
                WHERE period_scale = 'DAY' AND work_status_scope = 'TENANT'
                  AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, D1);
        assertThat(day1.get("scheme_count")).isEqualTo(2);
        assertThat(day1.get("total_supply_days")).isEqualTo(2);
        assertThat(day1.get("total_submission_days")).isEqualTo(2);
        assertThat(((Number) day1.get("total_water_supplied_liters")).longValue()).isEqualTo(1009L);
        assertThat(day1.get("supply_days_in_efficient_range")).isEqualTo(1);
        assertThat(day1.get("continuous_scheme_count")).isEqualTo(2);

        // WEEK aggregate sums both days: supply days = scheme1(2) + scheme2(1: D2 is NOT_SUBMITTED) = 3;
        // water = 10 + 10 + 999 (the NOT_SUBMITTED 500 is excluded by the unified rule).
        Map<String, Object> week = jdbcTemplate.queryForMap("""
                SELECT total_supply_days, total_submission_days, total_water_supplied_liters
                FROM analytics_schema.fact_region_metrics_table
                WHERE period_scale = 'WEEK' AND work_status_scope = 'TENANT'
                  AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, LocalDate.of(2026, 1, 4));
        assertThat(week.get("total_supply_days")).isEqualTo(3);
        assertThat(week.get("total_submission_days")).isEqualTo(4);
        assertThat(((Number) week.get("total_water_supplied_liters")).longValue()).isEqualTo(1019L);

        // Read path: summing DAY rows over [D1, D2] yields supply days = 3, scheme_count = 2.
        var metrics = aggregateReadRepository.getRegionMetrics(1, "LGD", 1, D1, D2);
        assertThat(metrics).isPresent();
        assertThat(metrics.get().schemeCount()).isEqualTo(2);
        assertThat(metrics.get().totalSupplyDays()).isEqualTo(3L);

        // National read (level-1, cross-tenant): one state row reading the SAME unified water figure.
        var national = aggregateReadRepository.getNationalRegionMetrics(1, D1, D2);
        assertThat(national).isPresent();
        assertThat(national.get()).hasSize(1);
        var state = national.get().get(0);
        assertThat(state.tenantId()).isEqualTo(1);
        assertThat(state.regionId()).isEqualTo(1);
        assertThat(state.schemeCount()).isEqualTo(2);
        assertThat(state.totalSupplyDays()).isEqualTo(3L);
        assertThat(state.totalWaterSuppliedLiters()).isEqualTo(1019L);
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
        assertThat(child.totalSupplyDays()).isEqualTo(3L);   // scheme1 (D1,D2) + scheme2 (D1; D2 NOT_SUBMITTED)
        assertThat(child.totalSubmissionDays()).isEqualTo(4L);
        assertThat(child.totalWaterSuppliedLiters()).isEqualTo(1019L);

        // Critical at cutoff = D2 (Jan 5): scheme1 last supply Jan 5 (not < cutoff),
        // scheme2 last supply Jan 4 (< cutoff) => 1 critical.
        var critical = aggregateReadRepository.getCriticalSchemeCount(1, "LGD", 1, D2);
        assertThat(critical).isPresent();
        assertThat(critical.getAsLong()).isEqualTo(1L);

        // Child reason distribution runs (no reasons seeded => present-but-empty).
        var outageDist = aggregateReadRepository.getChildReasonDistribution(1, "LGD", 1, 1, true, D1, D2);
        assertThat(outageDist).isPresent();
        assertThat(outageDist.get()).isEmpty();

        // Per-scheme water supply uses the unified supplied-water figure.
        var schemes = aggregateReadRepository.getSchemeWaterSupply(1, D1, D2);
        assertThat(schemes).isPresent();
        assertThat(schemes.get()).hasSize(2);
        var s1 = schemes.get().stream().filter(r -> r.schemeId() == 1).findFirst().orElseThrow();
        assertThat(s1.totalWaterSuppliedLiters()).isEqualTo(20L); // 10 (D1) + 10 (D2)
        assertThat(s1.supplyDays()).isEqualTo(2);
        var s2 = schemes.get().stream().filter(r -> r.schemeId() == 2).findFirst().orElseThrow();
        assertThat(s2.totalWaterSuppliedLiters()).isEqualTo(999L); // 999 (D1); D2 NOT_SUBMITTED excluded
        assertThat(s2.supplyDays()).isEqualTo(1);
    }

    @Test
    void regionMetrics_partialDayCoverage_fallsBackToLegacy() {
        aggregationRepository.upsertSchemeDaily(D1, D2);
        // Only D1's DAY bucket is aggregated (D2 missing) -> the [D1, D2] window is partly covered.
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D1, D1, true);

        // Partial coverage -> empty, so the caller uses the legacy raw-fact query instead of an
        // under-summed total.
        assertThat(aggregateReadRepository.getRegionMetrics(1, "LGD", 1, D1, D2)).isEmpty();
        // A fully covered sub-range ([D1, D1]) still returns metrics from the aggregate.
        assertThat(aggregateReadRepository.getRegionMetrics(1, "LGD", 1, D1, D1)).isPresent();
    }

    @Test
    void criticalCount_noAggregatedRowsOnOrAfterCutoff_fallsBackToLegacy() {
        aggregationRepository.upsertSchemeDaily(D1, D2);

        // Cutoff after all aggregated data -> no rows on/after cutoff -> empty (legacy fallback),
        // so a store not backfilled up to the cutoff is not reported as all-critical.
        assertThat(aggregateReadRepository.getCriticalSchemeCount(1, "LGD", 1, D2.plusDays(1))).isEmpty();
        // Cutoff within the aggregated range -> present.
        assertThat(aggregateReadRepository.getCriticalSchemeCount(1, "LGD", 1, D2)).isPresent();
    }

    @Test
    void regionRollup_buildsScopedRows_withPeriodEffectiveFilters() {
        // Tenant tier: {4} in force through D1, then {1,4} from D2 on.
        insertFilterHistory(1, LocalDate.of(2020, 1, 1), D2, List.of(4));
        insertFilterHistory(1, D2, null, List.of(1, 4));
        // National tier: {1} the whole time.
        insertFilterHistory(0, LocalDate.of(2020, 1, 1), null, List.of(1));

        aggregationRepository.upsertSchemeDaily(D1, D2);
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D1, D1, true);
        aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, D2, D2, true);

        // TENANT scope, D1 bucket (filter {4} in force on its period_end): only scheme1 counts —
        // water 10, supply day 1, efficient 1.
        Map<String, Object> tenantD1 = jdbcTemplate.queryForMap("""
                SELECT scheme_count, total_supply_days, total_water_supplied_liters, supply_days_in_efficient_range
                FROM analytics_schema.fact_region_metrics_table
                WHERE period_scale = 'DAY' AND work_status_scope = 'TENANT'
                  AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, D1);
        assertThat(tenantD1.get("scheme_count")).isEqualTo(1);
        assertThat(tenantD1.get("total_supply_days")).isEqualTo(1);
        assertThat(((Number) tenantD1.get("total_water_supplied_liters")).longValue()).isEqualTo(10L);
        assertThat(tenantD1.get("supply_days_in_efficient_range")).isEqualTo(1);

        // TENANT scope, D2 bucket ({1,4} in force): both schemes count, but scheme2's D2 water
        // is NOT_SUBMITTED so only scheme1's 10 qualifies.
        Map<String, Object> tenantD2 = jdbcTemplate.queryForMap("""
                SELECT scheme_count, total_supply_days, total_water_supplied_liters
                FROM analytics_schema.fact_region_metrics_table
                WHERE period_scale = 'DAY' AND work_status_scope = 'TENANT'
                  AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, D2);
        assertThat(tenantD2.get("scheme_count")).isEqualTo(2);
        assertThat(tenantD2.get("total_supply_days")).isEqualTo(1);
        assertThat(((Number) tenantD2.get("total_water_supplied_liters")).longValue()).isEqualTo(10L);

        // NATIONAL scope, D1 bucket (uniform national {1}): only scheme2 counts — water 999.
        Map<String, Object> nationalD1 = jdbcTemplate.queryForMap("""
                SELECT scheme_count, total_supply_days, total_water_supplied_liters
                FROM analytics_schema.fact_region_metrics_table
                WHERE period_scale = 'DAY' AND work_status_scope = 'NATIONAL'
                  AND tenant_id = 1 AND hierarchy = 'LGD'
                  AND region_level = 1 AND region_id = 1 AND period_start = ?
                """, D1);
        assertThat(nationalD1.get("scheme_count")).isEqualTo(1);
        assertThat(nationalD1.get("total_supply_days")).isEqualTo(1);
        assertThat(((Number) nationalD1.get("total_water_supplied_liters")).longValue()).isEqualTo(999L);

        // NATIONAL rows exist only at LGD levels 1-2 (all the national dashboard reads).
        Integer nationalDeptRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM analytics_schema.fact_region_metrics_table
                WHERE work_status_scope = 'NATIONAL' AND hierarchy = 'DEPT'
                """, Integer.class);
        assertThat(nationalDeptRows).isZero();

        // The national read path returns the NATIONAL-scope figures.
        var national = aggregateReadRepository.getNationalRegionMetrics(1, D1, D1);
        assertThat(national).isPresent();
        assertThat(national.get()).hasSize(1);
        assertThat(national.get().get(0).schemeCount()).isEqualTo(1);
        assertThat(national.get().get(0).totalWaterSuppliedLiters()).isEqualTo(999L);
    }

    @Test
    void derivedReads_applyTenantFilterInForceAtRangeEnd() {
        insertFilterHistory(1, LocalDate.of(2020, 1, 1), null, List.of(4));
        aggregationRepository.upsertSchemeDaily(D1, D2);

        // Continuous over [D1, D2]: only scheme1 is in filter; it supplied both days => 1.
        var continuous = aggregateReadRepository.getContinuousSchemeCount(1, "LGD", 1, D1, D2, 2);
        assertThat(continuous).isPresent();
        assertThat(continuous.getAsLong()).isEqualTo(1L);

        // Child rollup: child region 10 counts only scheme1 (water 10+10, 2 supply days).
        var children = aggregateReadRepository.getChildRegionMetrics(1, "LGD", 1, 1, D1, D2);
        assertThat(children).isPresent();
        assertThat(children.get()).hasSize(1);
        assertThat(children.get().get(0).schemeCount()).isEqualTo(1);
        assertThat(children.get().get(0).totalSupplyDays()).isEqualTo(2L);
        assertThat(children.get().get(0).totalWaterSuppliedLiters()).isEqualTo(20L);

        // Per-scheme water supply: only the handed-over scheme is listed.
        var schemes = aggregateReadRepository.getSchemeWaterSupply(1, D1, D2);
        assertThat(schemes).isPresent();
        assertThat(schemes.get()).hasSize(1);
        assertThat(schemes.get().get(0).schemeId()).isEqualTo(1);

        // Critical at cutoff D2: scheme1 (the only in-filter scheme) last supplied D2 => 0 critical.
        var critical = aggregateReadRepository.getCriticalSchemeCount(1, "LGD", 1, D2);
        assertThat(critical).isPresent();
        assertThat(critical.getAsLong()).isZero();
    }

    private void insertFilterHistory(int tenantId, LocalDate from, LocalDate to, List<Integer> statuses) {
        String array = "{" + statuses.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_work_status_filter_table
                (tenant_id, effective_from, effective_to, included_work_statuses, created_at)
                VALUES (?, ?, ?, ?::int[], NOW())
                """, tenantId, from, to, array);
    }

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE
                    analytics_schema.fact_region_metrics_table,
                    analytics_schema.fact_scheme_daily_table,
                    analytics_schema.dim_tenant_water_norm_table,
                    analytics_schema.dim_tenant_work_status_filter_table,
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
                INSERT INTO analytics_schema.dim_tenant_water_norm_table
                (tenant_id, effective_from, effective_to, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at)
                VALUES (1, DATE '2020-01-01', NULL, 1, 1, 0, 0, NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'User 11')
                """);

        // work_status: scheme1 = 4 (handed over), scheme2 = 1 (in progress) — inert while
        // no filter tier is configured (env off), exercised by the filter-scope tests.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, work_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'S1', 1001, 2001, 0.0, 0.0, 1, 1, 10, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 4, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'S2', 1002, 2002, 0.0, 0.0, 1, 1, 10, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 1, 10, 10, 10, NOW(), NOW())
                """);

        // Meter readings drive `submitted` and the compliant/anomalous counts only
        // (supply days come from the water rows under the unified rule).
        insertReading(1, 10, D1);
        insertReading(1, 10, D2);
        insertReading(2, 10, D1);
        insertReading(2, 0, D2);

        // Water rows (unified rule): scheme1 efficient both days (10 within [10,10]);
        // scheme2 D1 SUBMITTED 999 (supplied, out of range); scheme2 D2 NOT_SUBMITTED 500
        // (excluded: no water, no supply day).
        insertWaterQuantity(1, 10, D1, 1);
        insertWaterQuantity(1, 10, D2, 1);
        insertWaterQuantity(2, 999, D1, 1);
        insertWaterQuantity(2, 500, D2, 0);
    }

    private void insertReading(int schemeId, int confirmed, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url,
                 reading_at, channel, reading_date, created_at, submission_status, reading_type)
                VALUES (1, ?, 11, ?, ?, 90, 'x', NOW(), 1, ?, NOW(), 1, 0)
                """, schemeId, confirmed, confirmed, date);
    }

    private void insertWaterQuantity(int schemeId, int quantity, LocalDate date, int submissionStatus) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status)
                VALUES (1, ?, 11, ?, ?, NOW(), NOW(), ?)
                """, schemeId, quantity, date, submissionStatus);
    }
}
