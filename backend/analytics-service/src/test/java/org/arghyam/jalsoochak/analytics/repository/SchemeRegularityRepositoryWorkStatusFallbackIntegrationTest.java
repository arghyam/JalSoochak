package org.arghyam.jalsoochak.analytics.repository;

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
 * Verifies the <b>three-tier</b> effective-set resolution of the dashboard {@code work_status} filter
 * (Phase 2), which {@link SchemeRegularityRepositoryWorkStatusFilterIntegrationTest} does not exercise
 * (that test only proves the env-default tier). Here every tier is populated so the precedence is
 * observable:
 *
 * <ul>
 *   <li><b>env default</b> = {@code {4}} ({@code analytics.dashboard.included-work-statuses = 4}).</li>
 *   <li><b>tenant-0 (national default)</b> = {@code {1}} — a config-only row that must never be counted
 *       as a tenant.</li>
 *   <li><b>Tenant A</b> (id 1) own = {@code {4}}.</li>
 *   <li><b>Tenant B</b> (id 2) own = {@code {1,4}}.</li>
 *   <li><b>Tenant C</b> (id 3) own = {@code NULL} → must fall through to tenant-0 ({@code {1}}), NOT env.</li>
 * </ul>
 *
 * <p>Tenant-scoped screens ({@code {{WS}}} via {@link SchemeRegularityRepository#getSchemeCountByLgd(Integer)})
 * resolve own → tenant-0 → env; national screens ({@code {{NWS}}} via
 * {@link SchemeRegularityRepository#getStateWiseRegularityMetrics}) resolve tenant-0 → env uniformly across
 * every scheme, so per-tenant and national counts legitimately diverge.</p>
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SchemeRegularityRepository.class)
class SchemeRegularityRepositoryWorkStatusFallbackIntegrationTest {

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
        // env-default tier = {4}; overridden per tenant and by tenant-0 below.
        registry.add("analytics.dashboard.included-work-statuses", () -> "4");
    }

    @Autowired
    private SchemeRegularityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);

    private static final int WS_HANDED_OVER = 4;
    private static final int WS_IN_PROGRESS = 1;

    @BeforeEach
    void setUp() {
        truncateAll();
        seed();
    }

    // ---- tenant-scoped {{WS}}: own -> tenant-0 -> env ----

    @Test
    void tenantWithOwnConfig_usesOwnSet_notEnvOrNational() {
        // Tenant A own = {4}: of s1(4), s2(4), s3(1) under LGD 100, only the two handed-over count.
        assertThat(repository.getSchemeCountByLgd(100)).isEqualTo(2);
        // Tenant B own = {1,4}: both s4(4) and s5(1) under LGD 200 count.
        assertThat(repository.getSchemeCountByLgd(200)).isEqualTo(2);
    }

    @Test
    void tenantWithoutOwnConfig_fallsThroughToTenantZero_notEnv() {
        // Tenant C own = NULL -> tenant-0 = {1} (NOT env {4}): of s6(4), s7(1) under LGD 300, only s7 counts.
        // If the fallback wrongly used env {4}, this would be 1 but would count s6 instead — the
        // household/id checks in the national test below pin down that it is genuinely the {1} scheme.
        assertThat(repository.getSchemeCountByLgd(300)).isEqualTo(1);
    }

    // ---- national {{NWS}}: tenant-0 -> env, uniform; diverges from per-tenant ----

    @Test
    void nationalScreens_applyTenantZeroSetUniformly_andExcludeTenantZeroRow() {
        List<SchemeRegularityRepository.StateSchemeRegularityMetrics> rows =
                repository.getStateWiseRegularityMetrics(D1, D3);

        // Work Item D: the config-only tenant-0 row must never be enumerated as a tenant.
        assertThat(rows).noneMatch(r -> r.tenantId() == 0);
        assertThat(rows).extracting(SchemeRegularityRepository.StateSchemeRegularityMetrics::tenantId)
                .containsExactly(1, 2, 3);

        // National set = tenant-0 {1} for EVERY tenant, ignoring each tenant's own config:
        //   Tenant A: only s3(1) -> 1   (diverges from its per-tenant count of 2)
        //   Tenant B: only s5(1) -> 1
        //   Tenant C: only s7(1) -> 1
        assertThat(schemeCountFor(rows, 1)).isEqualTo(1);
        assertThat(schemeCountFor(rows, 2)).isEqualTo(1);
        assertThat(schemeCountFor(rows, 3)).isEqualTo(1);

        // Explicit divergence: Tenant A national (1) != Tenant A per-tenant (2).
        assertThat(schemeCountFor(rows, 1)).isNotEqualTo(repository.getSchemeCountByLgd(100));
    }

    private static int schemeCountFor(
            List<SchemeRegularityRepository.StateSchemeRegularityMetrics> rows, int tenantId) {
        return rows.stream().filter(r -> r.tenantId() == tenantId).findFirst().orElseThrow().schemeCount();
    }

    // ---- seeding ----

    private void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
    }

    private void seed() {
        // tenant-0: national default {1}, no schemes, must not be counted as a tenant.
        insertTenant(0, "NATIONAL", "National Default", "{1}");
        insertTenant(1, "mp", "Tenant A", "{4}");
        insertTenant(2, "tr", "Tenant B", "{1,4}");
        insertTenant(3, "up", "Tenant C", null); // own NULL -> falls through to tenant-0

        // Tenant A under LGD 100/101
        insertLgd(1, 100, "A parent", 1, 100, null);
        insertLgd(1, 101, "A child", 2, 100, 101);
        insertScheme(1, 1, 100, 101, WS_HANDED_OVER);
        insertScheme(2, 1, 100, 101, WS_HANDED_OVER);
        insertScheme(3, 1, 100, 101, WS_IN_PROGRESS);

        // Tenant B under LGD 200/201
        insertLgd(2, 200, "B parent", 1, 200, null);
        insertLgd(2, 201, "B child", 2, 200, 201);
        insertScheme(4, 2, 200, 201, WS_HANDED_OVER);
        insertScheme(5, 2, 200, 201, WS_IN_PROGRESS);

        // Tenant C under LGD 300/301
        insertLgd(3, 300, "C parent", 1, 300, null);
        insertLgd(3, 301, "C child", 2, 300, 301);
        insertScheme(6, 3, 300, 301, WS_HANDED_OVER);
        insertScheme(7, 3, 300, 301, WS_IN_PROGRESS);
    }

    private void insertTenant(int tenantId, String stateCode, String title, String includedWorkStatuses) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, included_work_statuses, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::int[], NOW(), NOW())
                """, tenantId, stateCode, title, "IN", 1, includedWorkStatuses);
    }

    private void insertLgd(int tenantId, int lgdId, String title, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                """, lgdId, tenantId, "L" + lgdId, title, title, level, level1, level2, null, null, null, null);
    }

    private void insertScheme(int schemeId, int tenantId, int level1Lgd, int level2Lgd, Integer workStatus) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, work_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, schemeId, tenantId, "Scheme " + schemeId, 1000 + schemeId, 2000 + schemeId, 0.0, 0.0,
                level1Lgd, level1Lgd, level2Lgd, null, null, null, null,
                1, 10, 10, 10, workStatus);
    }
}
