package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_test")
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
    private SchemeRegularityRepository repository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);
    private static final LocalDate D8 = LocalDate.of(2026, 1, 8);
    private static final LocalDate D10 = LocalDate.of(2026, 1, 10);

    @BeforeEach
    void setUp() {
        truncateAll();
        seedDimensions();
        seedMeterReadings();
        seedWaterQuantity();
    }

    @Test
    void getLgdAndDepartmentLevels_returnsExpectedValues() {
        assertThat(repository.getLgdLevel(100)).isEqualTo(1);
        assertThat(repository.getLgdLevel(101)).isEqualTo(2);
        assertThat(repository.getDepartmentLevel(200)).isEqualTo(1);
        assertThat(repository.getDepartmentLevel(201)).isEqualTo(2);
    }

    @Test
    void getNationalDashboardTenantStateMetadata_returnsLevelOneLgdAndTenantStatus() {
        List<SchemeRegularityRepository.NationalDashboardTenantStateMetadata> rows =
                repository.getNationalDashboardTenantStateMetadata();

        assertThat(rows).hasSize(1);
        SchemeRegularityRepository.NationalDashboardTenantStateMetadata row = rows.getFirst();
        assertThat(row.tenantId()).isEqualTo(1);
        assertThat(row.lgdId()).isEqualTo(100);
        assertThat(row.tenantStatus()).isEqualTo(1);
    }

    @Test
    void getNationalDashboardStateBoundaries_returnsGeoJsonWhenGeomPresent() {
        jdbcTemplate.update(
                """
                        UPDATE analytics_schema.dim_lgd_location_table
                        SET geom = ST_SetSRID(ST_GeomFromText('POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))'), 4326)
                        WHERE lgd_id = 100
                        """);

        List<SchemeRegularityRepository.NationalDashboardStateBoundary> rows =
                repository.getNationalDashboardStateBoundaries();

        assertThat(rows).hasSize(1);
        SchemeRegularityRepository.NationalDashboardStateBoundary row = rows.getFirst();
        assertThat(row.tenantId()).isEqualTo(1);
        assertThat(row.lgdId()).isEqualTo(100);
        assertThat(row.tenantStatus()).isEqualTo(1);
        assertThat(row.stateCode()).isEqualTo("mp");
        assertThat(row.stateTitle()).isEqualTo("Madhya Pradesh");
        assertThat(row.boundaryGeoJson()).contains("Polygon");
        assertThat(row.boundaryGeoJson()).contains("coordinates");
    }

    @Test
    void getNationalDashboardStateBoundaries_returnsNullBoundaryGeoJsonWhenGeomMissing() {
        List<SchemeRegularityRepository.NationalDashboardStateBoundary> rows =
                repository.getNationalDashboardStateBoundaries();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().lgdId()).isEqualTo(100);
        assertThat(rows.getFirst().boundaryGeoJson()).isNull();
    }

    @Test
    void getTenantWiseSupplyDaysInEfficientRange_countsSchemeDaysWithinTenantConfiguredRange() {
        List<SchemeRegularityRepository.TenantSupplyDaysInEfficientRange> rows =
                repository.getTenantWiseSupplyDaysInEfficientRange(D1, D3);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().tenantId()).isEqualTo(1);
        // Tenant config defaults to 0 (via COALESCE) -> efficient band is [0,0]. The seeded readings
        // (100/200/50) never fall in [0,0], and days with NO reading no longer count (phantom-day
        // over-count fix: the efficient CASE now requires an actual reading), so the count is 0.
        // (Pre-fix this returned 3 -- the three no-reading scheme-days that COALESCE'd to 0.)
        assertThat(rows.getFirst().supplyDaysInEfficientRange()).isEqualTo(0L);
    }

    @Test
    void supplyDaysInEfficientRange_countsRealReadingDaysOnly_notPhantomNoReadingDays() {
        // Regression for the efficient-range over-count: the query CROSS JOINs every scheme x every
        // day and LEFT JOINs water, so a day with NO reading used to COALESCE to 0 and -- when the
        // band includes 0 (fhtc=0 or required_lpcd=0) -- was wrongly counted as "efficient".
        // Default config here gives band [0,0]; add ONE real zero-water reading (scheme 1, D3) and
        // assert ONLY it counts (1), not the no-reading phantom days. Pre-fix this returned 3.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 0, D3, SubmissionStatus.NOT_SUBMITTED.getCode(), "draught", "app_issue");

        List<SchemeRegularityRepository.TenantSupplyDaysInEfficientRange> rows =
                repository.getTenantWiseSupplyDaysInEfficientRange(D1, D3);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().supplyDaysInEfficientRange()).isEqualTo(1L);
    }

    @Test
    void getSchemeRegularityMetricsByLgd_countsOnlyPositiveConfirmedReadingDays() {
        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                repository.getSchemeRegularityMetrics(100, D1, D3);

        assertThat(metrics.schemeCount()).isEqualTo(2);
        assertThat(metrics.totalSupplyDays()).isEqualTo(2);
    }

    @Test
    void getReadingSubmissionRateMetricsByLgd_countsNonNegativeReadingDays() {
        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                repository.getReadingSubmissionRateMetricsByLgd(100, D1, D3);

        assertThat(metrics.schemeCount()).isEqualTo(2);
        assertThat(metrics.totalSupplyDays()).isEqualTo(4);
    }

    @Test
    void getSchemeRegularityAndSubmissionByDepartment_matchExpectedCounts() {
        SchemeRegularityRepository.SchemeRegularityMetrics regularity =
                repository.getSchemeRegularityMetricsByDepartment(200, D1, D3);
        SchemeRegularityRepository.SchemeRegularityMetrics submission =
                repository.getReadingSubmissionRateMetricsByDepartment(200, D1, D3);

        assertThat(regularity.schemeCount()).isEqualTo(2);
        assertThat(regularity.totalSupplyDays()).isEqualTo(2);
        assertThat(submission.schemeCount()).isEqualTo(2);
        assertThat(submission.totalSupplyDays()).isEqualTo(4);
    }

    @Test
    void childReadingSubmissionQueries_returnExpectedRows_forLgdAndDepartment() {
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> byLgd =
                repository.getChildReadingSubmissionRateMetricsByLgd(100, D1, D3);
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> byDept =
                repository.getChildReadingSubmissionRateMetricsByDepartment(200, D1, D3);

        assertThat(byLgd).hasSize(2);
        assertThat(byLgd.get(0).lgdId()).isEqualTo(101);
        assertThat(byLgd.get(0).schemeCount()).isEqualTo(1);
        assertThat(byLgd.get(0).totalSubmissionDays()).isEqualTo(3);
        assertThat(byLgd.get(0).readingSubmissionRate()).isEqualByComparingTo("1.0000");
        assertThat(byLgd.get(1).lgdId()).isEqualTo(102);
        assertThat(byLgd.get(1).totalSubmissionDays()).isEqualTo(1);
        assertThat(byLgd.get(1).readingSubmissionRate()).isEqualByComparingTo("0.3333");

        assertThat(byDept).hasSize(2);
        assertThat(byDept.get(0).departmentId()).isEqualTo(201);
        assertThat(byDept.get(0).totalSubmissionDays()).isEqualTo(3);
        assertThat(byDept.get(1).departmentId()).isEqualTo(202);
        assertThat(byDept.get(1).totalSubmissionDays()).isEqualTo(1);
    }

    @Test
    void childSchemeRegularityQueries_returnExpectedRows_forLgdAndDepartment() {
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> byLgd =
                repository.getChildSchemeRegularityMetricsByLgd(100, D1, D3);
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> byDept =
                repository.getChildSchemeRegularityMetricsByDepartment(200, D1, D3);

        assertThat(byLgd).hasSize(2);
        assertThat(byLgd.get(0).lgdId()).isEqualTo(101);
        assertThat(byLgd.get(0).totalSupplyDays()).isEqualTo(2);
        assertThat(byLgd.get(0).averageRegularity()).isEqualByComparingTo("0.6667");
        assertThat(byLgd.get(1).lgdId()).isEqualTo(102);
        assertThat(byLgd.get(1).totalSupplyDays()).isEqualTo(0);
        assertThat(byLgd.get(1).averageRegularity()).isEqualByComparingTo("0.0000");

        assertThat(byDept).hasSize(2);
        assertThat(byDept.get(0).departmentId()).isEqualTo(201);
        assertThat(byDept.get(0).totalSupplyDays()).isEqualTo(2);
        assertThat(byDept.get(1).departmentId()).isEqualTo(202);
        assertThat(byDept.get(1).totalSupplyDays()).isEqualTo(0);
    }

    @Test
    void duplicateSchemeMappingRows_doNotInflateRegionRollups() {
        // A scheme legitimately has multiple dim_scheme_table rows (one per parent LGD/dept mapping;
        // see migration V24 uq_dim_scheme_tenant_scheme_parent_lgd_dept). Insert a SECOND mapping row
        // for Scheme A (id 1) in the SAME LGD region (level_2_lgd_id = 101), differing only by
        // parent_department_location_id so it satisfies the unique constraint. Region rollups must
        // count the scheme once and must not multiply its supply/submission days.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, 1, "Scheme A (duplicate mapping)", 1001, 2001, 0.0, 0.0,
                100, 100, 101, null, null, null, null,
                202, 200, 201, null, null, null, null,
                1, 10, 10, 10);

        // Parent metric already used DISTINCT — stays correct (control).
        SchemeRegularityRepository.SchemeRegularityMetrics submission =
                repository.getReadingSubmissionRateMetricsByLgd(100, D1, D3);
        assertThat(submission.schemeCount()).isEqualTo(2);
        assertThat(submission.totalSupplyDays()).isEqualTo(4);

        // Child submission-rate rollup must NOT double-count Scheme A's mapping rows.
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> childSubmission =
                repository.getChildReadingSubmissionRateMetricsByLgd(100, D1, D3);
        assertThat(childSubmission).hasSize(2);
        assertThat(childSubmission.get(0).lgdId()).isEqualTo(101);
        assertThat(childSubmission.get(0).schemeCount()).isEqualTo(1);
        assertThat(childSubmission.get(0).totalSubmissionDays()).isEqualTo(3);
        assertThat(childSubmission.get(0).readingSubmissionRate()).isEqualByComparingTo("1.0000");

        // Child regularity rollup must NOT double-count either.
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> childRegularity =
                repository.getChildSchemeRegularityMetricsByLgd(100, D1, D3);
        assertThat(childRegularity.get(0).lgdId()).isEqualTo(101);
        assertThat(childRegularity.get(0).schemeCount()).isEqualTo(1);
        assertThat(childRegularity.get(0).totalSupplyDays()).isEqualTo(2);
        assertThat(childRegularity.get(0).averageRegularity()).isEqualByComparingTo("0.6667");

        // Scheme-count helper stays distinct.
        assertThat(repository.getSchemeCountByLgdInScope(1, 100)).isEqualTo(2);
    }

    @Test
    void crossTenantSchemeIdCollision_isIsolatedByTenantScopedOverloads() {
        // scheme_id is unique only WITHIN a tenant. The region scope is tenant-scoped, but the fact
        // join must also constrain tenant_id or another tenant's rows for the same scheme_id leak in.
        // Seed tenant 2 with the SAME scheme_id (1) and three compliant readings for it.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, 2, "up", "Uttar Pradesh", "IN", 1);
        for (LocalDate d : List.of(D1, D2, D3)) {
            jdbcTemplate.update("""
                    INSERT INTO analytics_schema.fact_meter_reading_table
                    (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                     reading_date, created_at, submission_status, reading_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                    """, 2, 1, 11, 7, 7, 90, "x", 1, d, 1, 0);
        }
        // Tenant 2 water row reusing scheme_id 2 (in tenant 1's scope) with a sentinel outage reason.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 2, 2, 11, 100, D2, SubmissionStatus.NOT_SUBMITTED.getCode(), "sentinel_tenant2", null);

        // Tenant-scoped overload must isolate tenant 1 (5 compliant in the single-tenant seed), not 8.
        SchemeRegularityRepository.SubmissionStatusCount status =
                repository.getSubmissionStatusCountByLgd(1, 100, D1, D3);
        assertThat(status.compliantSubmissionCount()).isEqualTo(5);
        assertThat(status.anomalousSubmissionCount()).isEqualTo(0);

        // Outage-reason rollup must not leak tenant 2's rows (the sentinel reason must be absent).
        List<SchemeRegularityRepository.OutageReasonSchemeCount> outage =
                repository.getOutageReasonSchemeCountByLgd(1, 100, D1, D3);
        assertThat(outage).noneMatch(r -> "sentinel_tenant2".equals(r.outageReason()));
    }

    @Test
    void overallOutageReasonCount_countsSchemesDistinctlyAcrossTenants() {
        // The national "overall outage" KPI counts distinct schemes across ALL tenants. scheme_id is
        // unique only within a tenant, so two different schemes in different tenants that share a
        // scheme_id and the same outage reason must count as TWO, not collapse to one.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, 2, "up", "Uttar Pradesh", "IN", 1);
        // Tenant 2 reuses scheme_id 1 (same id as tenant 1's Scheme A) with the same outage reason on D1.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 2, 1, 11, 100, D1, SubmissionStatus.NOT_SUBMITTED.getCode(), "draught", "app_issue");

        List<SchemeRegularityRepository.OutageReasonSchemeCount> overall =
                repository.getOverallOutageReasonSchemeCount(D1, D8);
        int draught = overall.stream()
                .filter(r -> "draught".equals(r.outageReason()))
                .mapToInt(SchemeRegularityRepository.OutageReasonSchemeCount::schemeCount)
                .findFirst().orElse(0);
        // tenant 1 Scheme A (D1) + tenant 2 scheme 1 (D1) = two distinct (tenant, scheme) pairs.
        assertThat(draught).isEqualTo(2);
    }

    @Test
    void streamSchemeSubmissionMetricsByDepartment_mapsRowsWithoutMissingColumns() {
        // Regression: the by-department stream SELECT must expose the four supplied_lgd_location_* array
        // columns that mapSchemeSubmissionMetrics reads, or row mapping throws SQLException at runtime.
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> rows = new java.util.ArrayList<>();
        repository.streamSchemeSubmissionMetricsByDepartment(1, 200, D1, D3, "schemeName", "asc", rows::add);

        assertThat(rows).extracting(SchemeRegularityRepository.SchemeSubmissionMetrics::schemeId)
                .containsExactlyInAnyOrder(1, 2);
        // arrays are selected (as empty literals) and mapped to non-null empty lists, not a SQLException
        assertThat(rows.get(0).suppliedLgdLocationIds()).isNotNull();
    }

    @Test
    void duplicateSchemeMappingRows_doNotInflateEfficientSupplyDaysOrWaterTotals() {
        // Region-wise water/efficient-range scope projects measure columns (fhtc/household/planned).
        // A multi-mapped scheme (V24) whose mapping rows carry DIFFERING measures must still contribute
        // ONCE per child region; plain SELECT DISTINCT over the measure projection keeps both rows and
        // fans out the CROSS JOIN over dates and the SUM of measures. DISTINCT ON (scheme, region) fixes it.

        // Non-zero water norm so the efficient window is meaningful:
        // target for Scheme A (fhtc=10) = required_lpcd(2) * fhtc(10) * pph(5) = 100; range [100,100].
        jdbcTemplate.update("""
                UPDATE analytics_schema.dim_tenant_table
                SET required_lpcd = 2, person_count_per_household = 5,
                    over_supply_range_percentage = 0, under_supply_range_percentage = 0
                WHERE tenant_id = 1
                """);

        // Baseline (single mapping row): Scheme A daily eWater = D1:100, D2:200, D8:300 -> only D1 hits [100,100].
        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> baseline =
                repository.getRegionWiseWaterQuantityByLgd(1, 100, D1, D10);
        assertThat(baseline.get(0).lgdId()).isEqualTo(101);
        assertThat(baseline.get(0).supplyDaysInEfficientRange()).isEqualTo(1L);
        assertThat(baseline.get(0).householdCount()).isEqualTo(10L);
        assertThat(baseline.get(0).waterQuantity()).isEqualTo(600L);

        // Second mapping row for Scheme A in the SAME child region (level_2=101), differing planned_fhtc
        // and a different parent_department to satisfy the V24 unique key. fhtc stays 10 (threshold unchanged).
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, 1, "Scheme A (dup mapping)", 1001, 2001, 0.0, 0.0,
                100, 100, 101, null, null, null, null,
                202, 200, 201, null, null, null, null,
                1, 10, 99, 10);

        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> withDup =
                repository.getRegionWiseWaterQuantityByLgd(1, 100, D1, D10);
        // Must NOT inflate: efficient days stay 1, household stays 10, water stays 600 (not 2 / 20 / 1200).
        assertThat(withDup.get(0).lgdId()).isEqualTo(101);
        assertThat(withDup.get(0).supplyDaysInEfficientRange()).isEqualTo(1L);
        assertThat(withDup.get(0).householdCount()).isEqualTo(10L);
        assertThat(withDup.get(0).waterQuantity()).isEqualTo(600L);
    }

    @Test
    void regionOwnWaterSupply_countsMultiSubRegionSchemeOnce_matchesParentChildRow_notSumOfChildren() {
        // Reproduces the state-vs-district dashboard mismatch: a region's headline figure must NOT be
        // derived by summing its child rows. A scheme that serves TWO blocks within one district has two
        // dim_scheme_table mapping rows (V24). The per-block child rows each carry that scheme's full
        // water + FHTC (water is attached per scheme), so summing the blocks double-counts it — inflating
        // the district's total water (-> MLD up) and FHTC (-> LPCD down). getRegionOwnWaterSupplyByLgd
        // dedups by scheme_id and is the correct value, equal to the row the district shows under its parent.

        // Two level-3 blocks under district 101.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, 1011, 1, "L1011", "BlockA1", "Block A1", 3, 100, 101, 1011, null, null, null);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, 1012, 1, "L1012", "BlockA2", "Block A2", 3, 100, 101, 1012, null, null, null);

        // Place Scheme A's original mapping in block 1011, then add a second mapping in block 1012
        // (same district 101, different block; differing parent_department satisfies the V24 unique key).
        jdbcTemplate.update(
                "UPDATE analytics_schema.dim_scheme_table SET level_3_lgd_id = 1011 WHERE scheme_id = 1 AND tenant_id = 1");
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, 1, "Scheme A (block 1012 mapping)", 1001, 2001, 0.0, 0.0,
                100, 100, 101, 1012, null, null, null,
                202, 200, 201, null, null, null, null,
                1, 10, 10, 10);

        // Scheme A water over D1..D3 from fact_water_quantity_table (SUBMITTED only): D2=200 => 200.
        // FHTC = 10, households = 10.
        // Buggy path — summing the per-block child rows double-counts Scheme A (once per block).
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> blocks =
                repository.getAverageWaterSupplyPerCurrentRegionByLgd(1, 101, D1, D3);
        assertThat(blocks).hasSize(2);
        long blockSumWater = blocks.stream()
                .mapToLong(SchemeRegularityRepository.ChildRegionWaterSupplyMetrics::totalWaterSuppliedLiters).sum();
        long blockSumFhtc = blocks.stream()
                .mapToLong(SchemeRegularityRepository.ChildRegionWaterSupplyMetrics::totalAchievedFhtcCount).sum();
        assertThat(blockSumWater).isEqualTo(400L);
        assertThat(blockSumFhtc).isEqualTo(20L);

        // Fix — the district's own deduped total counts Scheme A once.
        SchemeRegularityRepository.ChildRegionWaterSupplyMetrics own =
                repository.getRegionOwnWaterSupplyByLgd(1, 101, D1, D3);
        assertThat(own.lgdId()).isEqualTo(101);
        assertThat(own.schemeCount()).isEqualTo(1);
        assertThat(own.totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(own.totalAchievedFhtcCount()).isEqualTo(10L);
        assertThat(own.totalHouseholdCount()).isEqualTo(10L);

        // And it equals the trustworthy value the district shows as a child of its parent (state 100).
        SchemeRegularityRepository.ChildRegionWaterSupplyMetrics districtAsChild =
                repository.getAverageWaterSupplyPerCurrentRegionByLgd(1, 100, D1, D3).stream()
                        .filter(m -> m.lgdId() == 101)
                        .findFirst()
                        .orElseThrow();
        assertThat(own.totalWaterSuppliedLiters()).isEqualTo(districtAsChild.totalWaterSuppliedLiters());
        assertThat(own.totalAchievedFhtcCount()).isEqualTo(districtAsChild.totalAchievedFhtcCount());
        assertThat(own.schemeCount()).isEqualTo(districtAsChild.schemeCount());
    }

    @Test
    void getRegionOwnWaterSupplyByDepartment_dedupsSchemeAcrossSubDepartments() {
        // Department analogue: a scheme mapped to two child departments within one parent department must
        // be counted once in the parent's own total (not once per child department).
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 2011, 1, "Child Dept A1", "Child Dept A1", 3, 200, 201, 2011, null, null, null);
        // Move Scheme A's original mapping to level_3 dept 2011, then add a second mapping in a different
        // level_3 dept value within the same parent dept 201 (different parent_lgd satisfies the V24 key).
        jdbcTemplate.update(
                "UPDATE analytics_schema.dim_scheme_table SET level_3_dept_id = 2011 WHERE scheme_id = 1 AND tenant_id = 1");
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, 1, "Scheme A (dept 2012 mapping)", 1001, 2001, 0.0, 0.0,
                101, 100, 101, null, null, null, null,
                201, 200, 201, 2012, null, null, null,
                1, 10, 10, 10);

        SchemeRegularityRepository.ChildRegionWaterSupplyMetrics own =
                repository.getRegionOwnWaterSupplyByDepartment(1, 201, D1, D3);
        assertThat(own.departmentId()).isEqualTo(201);
        assertThat(own.schemeCount()).isEqualTo(1);
        assertThat(own.totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(own.totalAchievedFhtcCount()).isEqualTo(10L);
    }

    @Test
    void getSchemeStatusCountByLgd_countsActiveAndInactiveSchemes() {
        SchemeRegularityRepository.SchemeStatusCount count = repository.getSchemeStatusCountByLgd(100);

        assertThat(count.activeSchemeCount()).isEqualTo(1);
        assertThat(count.inactiveSchemeCount()).isEqualTo(1);
    }

    @Test
    void getSchemeStatusCountByDepartment_countsActiveAndInactiveSchemes() {
        SchemeRegularityRepository.SchemeStatusCount count = repository.getSchemeStatusCountByDepartment(200);

        assertThat(count.activeSchemeCount()).isEqualTo(1);
        assertThat(count.inactiveSchemeCount()).isEqualTo(1);
    }

    @Test
    void getTopSchemeSubmissionMetricsByLgd_includesImmediateParentLgdDetailsPerScheme() {
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> rows =
                repository.getTopSchemeSubmissionMetricsByLgd(100, D1, D3, 5);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).totalWaterSupplied()).isEqualTo(200L);
        assertThat(rows.get(1).totalWaterSupplied()).isEqualTo(0L);
        assertThat(rows.get(0).immediateParentLgdId()).isEqualTo(100);
        assertThat(rows.get(0).immediateParentLgdCName()).isEqualTo("Parent");
        assertThat(rows.get(0).immediateParentLgdTitle()).isEqualTo("Parent LGD");
    }

    @Test
    void getTopSchemeSubmissionMetricsByDepartment_includesImmediateParentDepartmentDetailsPerScheme() {
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> rows =
                repository.getTopSchemeSubmissionMetricsByDepartment(200, D1, D3, 5);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).totalWaterSupplied()).isEqualTo(200L);
        assertThat(rows.get(1).totalWaterSupplied()).isEqualTo(0L);
        assertThat(rows.get(0).immediateParentDepartmentId()).isEqualTo(200);
        assertThat(rows.get(0).immediateParentDepartmentCName()).isEqualTo("Parent Dept");
        assertThat(rows.get(0).immediateParentDepartmentTitle()).isEqualTo("Parent Dept");
    }

    @Test
    void getParentLgdCNameByLgd_returnsParentLgdNameFromSchemeJoin() {
        String parentLgdCName = repository.getParentLgdCNameByLgd(100);

        assertThat(parentLgdCName).isEqualTo("Parent");
    }

    @Test
    void getParentLgdTitleByLgd_returnsParentLgdTitleFromSchemeJoin() {
        String parentLgdTitle = repository.getParentLgdTitleByLgd(100);

        assertThat(parentLgdTitle).isEqualTo("Parent LGD");
    }

    @Test
    void getParentDepartmentCNameByDepartment_returnsParentDepartmentNameFromSchemeJoin() {
        String parentDepartmentCName = repository.getParentDepartmentCNameByDepartment(200);

        assertThat(parentDepartmentCName).isEqualTo("Parent Dept");
    }

    @Test
    void getParentDepartmentTitleByDepartment_returnsParentDepartmentTitleFromSchemeJoin() {
        String parentDepartmentTitle = repository.getParentDepartmentTitleByDepartment(200);

        assertThat(parentDepartmentTitle).isEqualTo("Parent Dept");
    }

    @Test
    void getPeriodicWaterQuantityByLgdId_weekScale_returnsExpectedRowsAndAverages() {
        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> rows =
                repository.getPeriodicWaterQuantityByLgdId(100, D1, D10, PeriodScale.WEEK);

        assertThat(rows).hasSize(2);

        SchemeRegularityRepository.PeriodicWaterQuantityMetrics weekOne = rows.get(0);
        SchemeRegularityRepository.PeriodicWaterQuantityMetrics weekTwo = rows.get(1);

        assertThat(weekOne.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(weekOne.periodEndDate()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(weekOne.averageWaterQuantity()).isEqualByComparingTo(new BigDecimal("116.6667"));
        assertThat(weekOne.householdCount()).isEqualTo(30);

        assertThat(weekTwo.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 8));
        assertThat(weekTwo.periodEndDate()).isEqualTo(LocalDate.of(2026, 1, 14));
        assertThat(weekTwo.averageWaterQuantity()).isEqualByComparingTo(new BigDecimal("185.0000"));
        assertThat(weekTwo.householdCount()).isEqualTo(30);
    }

    @Test
    void getPeriodicWaterQuantityByDepartment_monthScale_returnsSingleMonthMetric() {
        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> rows =
                repository.getPeriodicWaterQuantityByDepartment(200, D1, D10, PeriodScale.MONTH);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(rows.get(0).averageWaterQuantity()).isEqualByComparingTo("144.0000");
        assertThat(rows.get(0).householdCount()).isEqualTo(30);
    }

    @Test
    void getPeriodicSchemeRegularityByLgdId_weekScale_returnsExpectedRowsAndSupplyDays() {
        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> rows =
                repository.getPeriodicSchemeRegularityByLgdId(100, D1, D10, PeriodScale.WEEK);

        assertThat(rows).hasSize(2);

        SchemeRegularityRepository.PeriodicSchemeRegularityMetrics weekOne = rows.get(0);
        assertThat(weekOne.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(weekOne.periodEndDate()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(weekOne.schemeCount()).isEqualTo(2);
        assertThat(weekOne.totalSupplyDays()).isEqualTo(2);
        // total_water_quantity now sums fact_water_quantity (SUBMITTED/NULL only): only D2 (200) qualifies.
        assertThat(weekOne.totalWaterQuantity()).isEqualTo(200L);

        SchemeRegularityRepository.PeriodicSchemeRegularityMetrics weekTwo = rows.get(1);
        assertThat(weekTwo.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 8));
        assertThat(weekTwo.periodEndDate()).isEqualTo(LocalDate.of(2026, 1, 14));
        assertThat(weekTwo.schemeCount()).isEqualTo(2);
        assertThat(weekTwo.totalSupplyDays()).isEqualTo(0);
        assertThat(weekTwo.totalWaterQuantity()).isEqualTo(0L);
    }

    @Test
    void getPeriodicSchemeRegularityByLgdId_monthScale_returnsSingleMonthMetric() {
        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> rows =
                repository.getPeriodicSchemeRegularityByLgdId(100, D1, D10, PeriodScale.MONTH);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(rows.get(0).periodEndDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(rows.get(0).schemeCount()).isEqualTo(2);
        assertThat(rows.get(0).totalSupplyDays()).isEqualTo(2);
        // total_water_quantity now sums fact_water_quantity (SUBMITTED/NULL only): only D2 (200) qualifies.
        assertThat(rows.get(0).totalWaterQuantity()).isEqualTo(200L);
    }

    @Test
    void getPeriodicOutageReasonSchemeCountByLgdId_monthScale_matchesDistinctSchemeCountsPerReason() {
        List<SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow> rows =
                repository.getPeriodicOutageReasonSchemeCountByLgdId(100, D1, D10, PeriodScale.MONTH);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(rows.get(0).outageReason()).isEqualTo("draught");
        assertThat(rows.get(0).schemeCount()).isEqualTo(1);
        assertThat(rows.get(1).outageReason()).isEqualTo("no_electricity");
        assertThat(rows.get(1).schemeCount()).isEqualTo(2);
    }

    @Test
    void getPeriodicOutageReasonSchemeCountByLgdId_weekScale_splitsAcrossRollingWeeksAnchoredToStartDate() {
        List<SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow> rows =
                repository.getPeriodicOutageReasonSchemeCountByLgdId(100, D1, D10, PeriodScale.WEEK);

        assertThat(rows.stream().map(SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow::periodStartDate)
                        .distinct()
                        .count())
                .isEqualTo(2);

        assertThat(rows)
                .anySatisfy(r -> {
                    assertThat(r.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.periodStartDate()).isEqualTo(LocalDate.of(2026, 1, 8));
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });
    }

    @Test
    void outageQueriesByLgd_returnParentAndChildAggregations() {
        List<SchemeRegularityRepository.OutageReasonSchemeCount> parent =
                repository.getOutageReasonSchemeCountByLgd(100, D1, D10);
        List<SchemeRegularityRepository.ChildRegionRef> children = repository.getChildRegionsByLgd(100);
        List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childCounts =
                repository.getChildOutageReasonSchemeCountByLgd(100, D1, D10);

        assertThat(parent).hasSize(2);
        assertThat(parent)
                .anySatisfy(r -> {
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });

        assertThat(children).hasSize(2);
        assertThat(children.get(0).lgdId()).isEqualTo(101);
        assertThat(children.get(1).lgdId()).isEqualTo(102);

        assertThat(childCounts).hasSize(3);
        assertThat(childCounts)
                .anySatisfy(r -> {
                    assertThat(r.lgdId()).isEqualTo(101);
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.lgdId()).isEqualTo(101);
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.lgdId()).isEqualTo(102);
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(1);
                });
    }

    @Test
    void outageQueriesByDepartment_returnParentAndChildAggregations() {
        List<SchemeRegularityRepository.OutageReasonSchemeCount> parent =
                repository.getOutageReasonSchemeCountByDepartment(200, D1, D10);
        List<SchemeRegularityRepository.ChildRegionRef> children = repository.getChildRegionsByDepartment(200);
        List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childCounts =
                repository.getChildOutageReasonSchemeCountByDepartment(200, D1, D10);

        assertThat(parent).hasSize(2);
        assertThat(parent)
                .anySatisfy(r -> {
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });

        assertThat(children).hasSize(2);
        assertThat(children.get(0).departmentId()).isEqualTo(201);
        assertThat(children.get(1).departmentId()).isEqualTo(202);

        assertThat(childCounts).hasSize(3);
        assertThat(childCounts)
                .anySatisfy(r -> {
                    assertThat(r.departmentId()).isEqualTo(201);
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.departmentId()).isEqualTo(201);
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.departmentId()).isEqualTo(202);
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(1);
                });
    }

    @Test
    void outageQueriesByUser_returnMappedSchemeReasonCounts() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 3, 1, "Scheme C", 1003, 2003, 0.0, 0.0,
                100, 100, 101, null, null, null, null,
                200, 200, 201, null, null, null, null,
                1, 5, 5, 5);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW(), ?)
                """, "33333333-3333-3333-3333-333333333333", 1, 11, 3, null, 1);

        List<SchemeRegularityRepository.OutageReasonSchemeCount> userCounts =
                repository.getOutageReasonSchemeCountByUser(1, 11, D1, D10);
        List<SchemeRegularityRepository.DailyOutageReasonSchemeCount> dailyUserCounts =
                repository.getDailyOutageReasonSchemeCountByUser(1, 11, D1, D10);
        Integer schemeCount = repository.getSchemeCountByUser(1, 11);

        assertThat(userCounts).hasSize(2);
        assertThat(dailyUserCounts).hasSize(3);
        assertThat(schemeCount).isEqualTo(3);
        assertThat(userCounts)
                .anySatisfy(r -> {
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });
        assertThat(dailyUserCounts)
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D1);
                    assertThat(r.outageReason()).isEqualTo("draught");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D1);
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D8);
                    assertThat(r.outageReason()).isEqualTo("no_electricity");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });
    }

    @Test
    void nonSubmissionQueriesByLgd_returnParentAndChildAggregations() {
        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> parent =
                repository.getNonSubmissionReasonSchemeCountByLgd(100, D1, D10);
        List<SchemeRegularityRepository.ChildRegionRef> children = repository.getChildRegionsByLgd(100);
        List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childCounts =
                repository.getChildNonSubmissionReasonSchemeCountByLgd(100, D1, D10);

        assertThat(parent).hasSize(2);
        assertThat(parent)
                .anySatisfy(r -> {
                    assertThat(r.nonSubmissionReason()).isEqualTo("app_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });

        assertThat(children).hasSize(2);
        assertThat(children.get(0).lgdId()).isEqualTo(101);
        assertThat(children.get(1).lgdId()).isEqualTo(102);

        assertThat(childCounts).hasSize(3);
        assertThat(childCounts)
                .anySatisfy(r -> {
                    assertThat(r.lgdId()).isEqualTo(101);
                    assertThat(r.nonSubmissionReason()).isEqualTo("app_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.lgdId()).isEqualTo(101);
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.lgdId()).isEqualTo(102);
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                });
    }

    @Test
    void nonSubmissionQueriesByDepartment_returnParentAndChildAggregations() {
        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> parent =
                repository.getNonSubmissionReasonSchemeCountByDepartment(200, D1, D10);
        List<SchemeRegularityRepository.ChildRegionRef> children = repository.getChildRegionsByDepartment(200);
        List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childCounts =
                repository.getChildNonSubmissionReasonSchemeCountByDepartment(200, D1, D10);

        assertThat(parent).hasSize(2);
        assertThat(parent)
                .anySatisfy(r -> {
                    assertThat(r.nonSubmissionReason()).isEqualTo("app_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });

        assertThat(children).hasSize(2);
        assertThat(children.get(0).departmentId()).isEqualTo(201);
        assertThat(children.get(1).departmentId()).isEqualTo(202);

        assertThat(childCounts).hasSize(3);
        assertThat(childCounts)
                .anySatisfy(r -> {
                    assertThat(r.departmentId()).isEqualTo(201);
                    assertThat(r.nonSubmissionReason()).isEqualTo("app_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.departmentId()).isEqualTo(201);
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.departmentId()).isEqualTo(202);
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                });
    }

    @Test
    void nonSubmissionQueriesByUser_returnMappedSchemeReasonCounts() {
        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> userCounts =
                repository.getNonSubmissionReasonSchemeCountByUser(1, 11, D1, D10);
        List<SchemeRegularityRepository.DailyNonSubmissionReasonSchemeCount> dailyUserCounts =
                repository.getDailyNonSubmissionReasonSchemeCountByUser(1, 11, D1, D10);

        assertThat(userCounts).hasSize(2);
        assertThat(dailyUserCounts).hasSize(3);
        assertThat(userCounts)
                .anySatisfy(r -> {
                    assertThat(r.nonSubmissionReason()).isEqualTo("app_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });
        assertThat(dailyUserCounts)
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D1);
                    assertThat(r.nonSubmissionReason()).isEqualTo("app_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D1);
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(1);
                })
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D8);
                    assertThat(r.nonSubmissionReason()).isEqualTo("network_issue");
                    assertThat(r.schemeCount()).isEqualTo(2);
                });
    }

    @Test
    void submissionStatusCountByUser_returnsCompliantAndAnomalousCounts() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 1, 11, 9, 10, 90, "x", 1, D2, 1, 0);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 2, 11, null, 7, 90, "x", 1, D3, 1, 0);

        SchemeRegularityRepository.SubmissionStatusCount statusCount =
                repository.getSubmissionStatusCountByUser(1, 11, D1, D3);
        List<SchemeRegularityRepository.DailySubmissionSchemeCount> dailyCounts =
                repository.getDailySubmissionSchemeCountByUser(1, 11, D1, D3);

        // Counts are scoped to schemes mapped to the user via dim_user_scheme_mapping_table,
        // not to fact_meter_reading_table.user_id.
        assertThat(statusCount.compliantSubmissionCount()).isEqualTo(5);
        assertThat(statusCount.anomalousSubmissionCount()).isEqualTo(1);
        assertThat(dailyCounts).hasSize(3);
        assertThat(dailyCounts)
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D1);
                    assertThat(r.submittedSchemeCount()).isEqualTo(2);
                })
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D2);
                    assertThat(r.submittedSchemeCount()).isEqualTo(2);
                })
                .anySatisfy(r -> {
                    assertThat(r.date()).isEqualTo(D3);
                    assertThat(r.submittedSchemeCount()).isEqualTo(1);
                });
    }

    @Test
    void submissionStatusSummaryByLgdAndDepartment_matchesScopeAggregates() {
        assertThat(repository.getSchemeCountByLgd(100)).isEqualTo(2);
        assertThat(repository.getSchemeCountByDepartment(200)).isEqualTo(2);

        SchemeRegularityRepository.SubmissionStatusCount byLgd =
                repository.getSubmissionStatusCountByLgd(100, D1, D3);
        SchemeRegularityRepository.SubmissionStatusCount byDept =
                repository.getSubmissionStatusCountByDepartment(200, D1, D3);

        assertThat(byLgd.compliantSubmissionCount()).isEqualTo(5);
        assertThat(byLgd.anomalousSubmissionCount()).isEqualTo(0);
        assertThat(byDept.compliantSubmissionCount()).isEqualTo(5);
        assertThat(byDept.anomalousSubmissionCount()).isEqualTo(0);

        assertThat(repository.getSchemeCountByLgd(101)).isEqualTo(1);
        SchemeRegularityRepository.SubmissionStatusCount childLgd =
                repository.getSubmissionStatusCountByLgd(101, D1, D3);
        assertThat(childLgd.compliantSubmissionCount()).isEqualTo(3);
        assertThat(childLgd.anomalousSubmissionCount()).isEqualTo(0);
    }

    @Test
    void waterSupplyQueries_returnExpectedAggregatesAcrossScopes() {
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> current =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3);
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> nation =
                repository.getAverageWaterSupplyPerNation(D1, D3);
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> byLgd =
                repository.getAverageWaterSupplyPerCurrentRegionByLgd(1, 100, D1, D3);
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> byDept =
                repository.getAverageWaterSupplyPerCurrentRegionByDepartment(1, 200, D1, D3);

        assertThat(current).hasSize(2);
        assertThat(current.get(0).schemeId()).isEqualTo(1);
        assertThat(current.get(0).totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(current.get(0).supplyDays()).isEqualTo(2);
        // 200 liters (D2 SUBMITTED) / (households 10 * 3 days) = 6.6667
        assertThat(current.get(0).averageLitersPerHousehold()).isEqualByComparingTo("6.6667");
        assertThat(current.get(1).schemeId()).isEqualTo(2);
        assertThat(current.get(1).totalWaterSuppliedLiters()).isEqualTo(0L);
        assertThat(current.get(1).averageLitersPerHousehold()).isEqualByComparingTo("0.0000");

        assertThat(nation).hasSize(1);
        assertThat(nation.get(0).tenantId()).isEqualTo(1);
        assertThat(nation.get(0).schemeCount()).isEqualTo(2);
        assertThat(nation.get(0).totalHouseholdCount()).isEqualTo(30);
        // Nation-level total water supplied comes from fact_water_quantity_table (submission_status=SUBMITTED).
        // Seed data: only D2 has SUBMITTED water quantity for scheme 1 (200). Scheme count remains 2.
        assertThat(nation.get(0).totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(nation.get(0).avgWaterSupplyPerScheme()).isEqualByComparingTo("100.0000");

        assertThat(byLgd).hasSize(2);
        assertThat(byLgd.get(0).lgdId()).isEqualTo(101);
        assertThat(byLgd.get(0).totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(byLgd.get(0).avgWaterSupplyPerScheme()).isEqualByComparingTo("200.0000");
        assertThat(byLgd.get(1).lgdId()).isEqualTo(102);
        assertThat(byLgd.get(1).totalWaterSuppliedLiters()).isEqualTo(0L);

        assertThat(byDept).hasSize(2);
        assertThat(byDept.get(0).departmentId()).isEqualTo(201);
        assertThat(byDept.get(0).totalWaterSuppliedLiters()).isEqualTo(200L);
        assertThat(byDept.get(1).departmentId()).isEqualTo(202);
        assertThat(byDept.get(1).totalWaterSuppliedLiters()).isEqualTo(0L);
    }

    @Test
    void suppliedWater_countsNullSubmissionStatusRows() {
        // Legacy/direct-event water can persist submission_status = NULL; with a positive volume it must
        // count as supplied (exercises the "submission_status = SUBMITTED OR submission_status IS NULL" branch).
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 40, D3, null, null, null);

        SchemeRegularityRepository.SchemeWaterSupplyMetrics scheme1 =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3).stream()
                        .filter(r -> r.schemeId().equals(1)).findFirst().orElseThrow();

        // D2 SUBMITTED (200) + D3 NULL-status (40) = 240; D1 NOT_SUBMITTED (100) stays excluded.
        assertThat(scheme1.totalWaterSuppliedLiters()).isEqualTo(240L);
    }

    @Test
    void suppliedWater_deduplicatesToLatestRowPerSchemeAndDate() {
        // fact_water_quantity_table has no uniqueness on (tenant, scheme, date). Insert a stray *newer*
        // duplicate for (tenant1, scheme1, D2): summing both rows would give 700, so the query must instead
        // take only the latest row per (tenant, scheme, date) — mirroring ingestion's updated_at DESC, id DESC.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '1 hour', ?, ?, ?)
                """, 1, 1, 11, 500, D2, SubmissionStatus.SUBMITTED.getCode(), null, null);

        SchemeRegularityRepository.SchemeWaterSupplyMetrics scheme1 =
                repository.getAverageWaterSupplyPerCurrentRegion(1, D1, D3).stream()
                        .filter(r -> r.schemeId().equals(1)).findFirst().orElseThrow();

        // Latest D2 row wins (500) — not summed with the seeded 200 (=700) and not the stale 200.
        assertThat(scheme1.totalWaterSuppliedLiters()).isEqualTo(500L);
    }

    @Test
    void regionWiseWaterQuantityQueries_returnExpectedRows_forLgdAndDepartment() {
        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> byLgd =
                repository.getRegionWiseWaterQuantityByLgd(100, D1, D10);
        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> byDept =
                repository.getRegionWiseWaterQuantityByDepartment(200, D1, D10);

        assertThat(byLgd).hasSize(2);
        assertThat(byLgd.get(0).lgdId()).isEqualTo(101);
        assertThat(byLgd.get(0).householdCount()).isEqualTo(10);
        assertThat(byLgd.get(0).waterQuantity()).isEqualTo(600L);
        assertThat(byLgd.get(1).lgdId()).isEqualTo(102);
        assertThat(byLgd.get(1).householdCount()).isEqualTo(20);
        assertThat(byLgd.get(1).waterQuantity()).isEqualTo(120L);

        assertThat(byDept).hasSize(2);
        assertThat(byDept.get(0).departmentId()).isEqualTo(201);
        assertThat(byDept.get(0).waterQuantity()).isEqualTo(600L);
        assertThat(byDept.get(1).departmentId()).isEqualTo(202);
        assertThat(byDept.get(1).waterQuantity()).isEqualTo(120L);
    }

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.fact_escalation_table,
                    analytics_schema.fact_scheme_performance_table,
                    analytics_schema.dim_user_scheme_mapping_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_date_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seedDimensions() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, "mp", "Madhya Pradesh", "IN", 1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """, 11, 1, "user11@example.com", 1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """, 12, 1, "user12@example.com", 1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, 100, 1, "L100", "Parent", "Parent LGD", 1, 100, null, null, null, null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, 101, 1, "L101", "ChildA", "Child LGD A", 2, 100, 101, null, null, null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, 102, 1, "L102", "ChildB", "Child LGD B", 2, 100, 102, null, null, null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 200, 1, "Parent Dept", "Parent Dept", 1, 200, null, null, null, null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 201, 1, "Child Dept A", "Child Dept A", 2, 200, 201, null, null, null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 202, 1, "Child Dept B", "Child Dept B", 2, 200, 202, null, null, null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 1, 1, "Scheme A", 1001, 2001, 0.0, 0.0,
                100, 100, 101, null, null, null, null,
                200, 200, 201, null, null, null, null,
                1, 10, 10, 10);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, 2, 1, "Scheme B", 1002, 2002, 0.0, 0.0,
                100, 100, 102, null, null, null, null,
                200, 200, 202, null, null, null, null,
                0, 20, 20, 20);

        insertDate(D1);
        insertDate(D2);
        insertDate(D3);
        insertDate(D8);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW(), ?)
                """, "11111111-1111-1111-1111-111111111111", 1, 11, 1, null, 1);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW(), ?)
                """, "22222222-2222-2222-2222-222222222222", 1, 11, 2, null, 1);
    }

    private void seedMeterReadings() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 1, 11, 10, 10, 90, "x", 1, D1, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 1, 11, 0, 0, 90, "x", 1, D2, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 1, 11, 5, 5, 90, "x", 1, D3, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 2, 12, -1, -1, 90, "x", 1, D1, 1, 0);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url, reading_at, channel,
                 reading_date, created_at, submission_status, reading_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), ?, ?)
                """, 1, 2, 12, 0, 0, 90, "x", 1, D2, 1, 0);
    }

    private void seedWaterQuantity() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 100, D1, SubmissionStatus.NOT_SUBMITTED.getCode(), "draught", "app_issue");

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 200, D2, SubmissionStatus.SUBMITTED.getCode(), null, null);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 1, 11, 300, D8, SubmissionStatus.NOT_SUBMITTED.getCode(), "no_electricity", "network_issue");

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 2, 12, 50, D1, SubmissionStatus.NOT_SUBMITTED.getCode(), "no_electricity", "network_issue");

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                """, 1, 2, 12, 70, D8, SubmissionStatus.NOT_SUBMITTED.getCode(), "no_electricity", "network_issue");
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
}
