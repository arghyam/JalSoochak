package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
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
 * Verifies the scheme-regularity <b>threshold</b>: the per-scheme classification and the three-tier
 * effective-threshold fallback (own tenant → tenant-0 national default → env default), mirroring
 * {@link SchemeRegularityRepositoryWorkStatusFallbackIntegrationTest} which does the same for the
 * work-status filter.
 *
 * <p>The env default is pinned to <b>90</b>. Over a 30-day window that requires
 * {@code round-half-up(0.9 × 30) = 27} supply days, so a scheme with 27 supply days is regular and one
 * with 26 is not — the boundary that discriminates the Postgres {@code ::numeric} vs {@code double
 * precision} rounding trap. Lowering the effective threshold (via own-tenant or tenant-0 config) flips
 * the 26-day scheme to regular, which is how the fallback precedence is observed.</p>
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryThresholdFallbackIntegrationTest {

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
        // Disable the work-status filter so every seeded scheme is in scope (threshold is the concern here).
        registry.add("analytics.dashboard.included-work-statuses", () -> "");
        // Env-default threshold tier = 90%.
        registry.add("analytics.dashboard.regularity.threshold-percent", () -> "90");
    }

    @Autowired
    private SchemeRegularityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 1, 30);

    private static final int TENANT = 1;
    private static final int PARENT_LGD = 100;
    private static final int SCHEME_27_DAYS = 1; // regular at 90% (needs 27)
    private static final int SCHEME_26_DAYS = 2; // not regular at 90%, regular once threshold drops

    @BeforeEach
    void setUp() {
        truncateAll();
        // fact_water_quantity_table.date is FK-constrained to dim_date_table; seed the whole window.
        for (LocalDate d = WINDOW_START; !d.isAfter(WINDOW_END); d = d.plusDays(1)) {
            insertDate(d);
        }
    }

    // ---- classification at the env default (90% → 27 of 30 days) ----

    @Test
    void classification_at90Percent_27DaysIsRegular_26IsNot() {
        seedTenant(TENANT, "mp", "Tenant A", null);
        seedParentAndChildLgd(TENANT);
        seedScheme(SCHEME_27_DAYS, TENANT);
        seedScheme(SCHEME_26_DAYS, TENANT);
        seedSupplyDays(SCHEME_27_DAYS, 27);
        seedSupplyDays(SCHEME_26_DAYS, 26);

        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                repository.getSchemeRegularityMetrics(TENANT, PARENT_LGD, WINDOW_START, WINDOW_END);

        assertThat(metrics.schemeCount()).isEqualTo(2);
        assertThat(metrics.totalSupplyDays()).isEqualTo(53);
        // Only the 27-day scheme clears 27; the 26-day scheme is one short.
        assertThat(metrics.regularSchemeCount()).isEqualTo(1);

        assertThat(repository.getEffectiveTenantRegularityThresholdPercent(TENANT))
                .isEqualByComparingTo("90");
    }

    // ---- three-tier fallback: own tenant → tenant-0 → env ----

    @Test
    void ownTenantThreshold_wins_thenTenantZero_thenEnv() {
        // tenant-0 national default = 80%; own tenant = 70%.
        seedTenant(0, "NATIONAL", "National Default", "80.00");
        seedTenant(TENANT, "mp", "Tenant A", "70.00");
        seedParentAndChildLgd(TENANT);
        seedScheme(SCHEME_26_DAYS, TENANT);
        seedSupplyDays(SCHEME_26_DAYS, 26); // 26/30 = 86.7%

        // Own tenant 70% wins: 26 days ≥ round(0.70×30)=21 ⇒ regular.
        assertThat(repository.getEffectiveTenantRegularityThresholdPercent(TENANT))
                .isEqualByComparingTo("70.00");
        assertThat(repository.getSchemeRegularityMetrics(TENANT, PARENT_LGD, WINDOW_START, WINDOW_END)
                .regularSchemeCount()).isEqualTo(1);

        // Remove own config ⇒ tenant-0 80% applies: 26 days ≥ round(0.80×30)=24 ⇒ still regular.
        clearThreshold(TENANT);
        assertThat(repository.getEffectiveTenantRegularityThresholdPercent(TENANT))
                .isEqualByComparingTo("80.00");
        assertThat(repository.getSchemeRegularityMetrics(TENANT, PARENT_LGD, WINDOW_START, WINDOW_END)
                .regularSchemeCount()).isEqualTo(1);

        // Remove tenant-0 config ⇒ env default 90% applies: 26 days < round(0.90×30)=27 ⇒ not regular.
        clearThreshold(0);
        assertThat(repository.getEffectiveTenantRegularityThresholdPercent(TENANT))
                .isEqualByComparingTo("90");
        assertThat(repository.getSchemeRegularityMetrics(TENANT, PARENT_LGD, WINDOW_START, WINDOW_END)
                .regularSchemeCount()).isZero();
    }

    // ---- national screens ignore the own-tenant tier ----

    @Test
    void nationalScreen_ignoresOwnTenantThreshold_usesTenantZeroThenEnv() {
        // Own tenant sets a very lax 10%, but a national screen must not honour it.
        seedTenant(0, "NATIONAL", "National Default", null); // tenant-0 has no own value ⇒ env
        seedTenant(TENANT, "mp", "Tenant A", "10.00");
        seedParentAndChildLgd(TENANT);
        seedScheme(SCHEME_26_DAYS, TENANT);
        seedSupplyDays(SCHEME_26_DAYS, 26);

        // National effective threshold = env 90% (tenant-0 unset), NOT the tenant's 10%.
        assertThat(repository.getEffectiveNationalRegularityThresholdPercent())
                .isEqualByComparingTo("90");

        List<SchemeRegularityRepository.StateSchemeRegularityMetrics> rows =
                repository.getStateWiseRegularityMetrics(WINDOW_START, WINDOW_END);
        SchemeRegularityRepository.StateSchemeRegularityMetrics tenantRow = rows.stream()
                .filter(r -> r.tenantId() == TENANT).findFirst().orElseThrow();

        // 26 of 30 days at the national 90% bar (needs 27) ⇒ not regular, despite the tenant's own 10%.
        assertThat(tenantRow.schemeCount()).isEqualTo(1);
        assertThat(tenantRow.regularSchemeCount()).isZero();
    }

    // ---- seeding ----

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_tenant_table,
                    analytics_schema.dim_date_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seedTenant(int tenantId, String stateCode, String title, String thresholdPercent) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, regularity_threshold_percent, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::numeric, NOW(), NOW())
                """, tenantId, stateCode, title, "IN", 1, thresholdPercent);
    }

    private void clearThreshold(int tenantId) {
        jdbcTemplate.update(
                "UPDATE analytics_schema.dim_tenant_table SET regularity_threshold_percent = NULL WHERE tenant_id = ?",
                tenantId);
    }

    private void seedParentAndChildLgd(int tenantId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NOW(), NOW())
                """, PARENT_LGD, tenantId, "L" + PARENT_LGD, "Parent", "Parent", 1, PARENT_LGD, null);
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NOW(), NOW())
                """, 101, tenantId, "L101", "Child", "Child", 2, PARENT_LGD, 101);
    }

    private void seedScheme(int schemeId, int tenantId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, schemeId, tenantId, "Scheme " + schemeId, 1000 + schemeId, 2000 + schemeId, 0.0, 0.0,
                PARENT_LGD, PARENT_LGD, 101, null, null, null, null,
                null, null, null, null, null, null, null,
                1, 10, 10, 10);
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

    /** Seeds {@code days} consecutive SUBMITTED supply days (positive volume) from the window start. */
    private void seedSupplyDays(int schemeId, int days) {
        for (int i = 0; i < days; i++) {
            jdbcTemplate.update("""
                    INSERT INTO analytics_schema.fact_water_quantity_table
                    (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status, outage_reason, non_submission_reason)
                    VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, NULL, NULL)
                    """, TENANT, schemeId, 11, 100, WINDOW_START.plusDays(i), SubmissionStatus.SUBMITTED.getCode());
        }
    }
}
