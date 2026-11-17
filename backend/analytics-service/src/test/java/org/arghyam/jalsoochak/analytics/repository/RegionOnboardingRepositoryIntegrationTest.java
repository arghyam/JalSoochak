package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.arghyam.jalsoochak.analytics.enums.RegionHierarchy.DEPARTMENT;
import static org.arghyam.jalsoochak.analytics.enums.RegionHierarchy.LGD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture, tenant 1 (state 100, department root 200):
 * <pre>
 * district 101  onboarded      scheme 1 (block 111, dept 201), scheme 4 (dept 204)
 *   block 112                  no scheme
 * district 102  onboarded      no scheme
 * district 103  not onboarded  scheme 2 (block 131, dept 203), scheme 5 (dept 204)
 * placeholder scheme 3: state 100 only, dept 205
 * </pre>
 * Tenant 2 reuses ids: its district 101 is not onboarded but has a scheme, its district 103 is
 * onboarded, so any join that ignores {@code tenant_id} flips a result.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(RegionOnboardingRepository.class)
class RegionOnboardingRepositoryIntegrationTest {

    private static final int TENANT = 1;
    private static final int OTHER_TENANT = 2;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("region_onboarding_test")
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
    private RegionOnboardingRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
        seed();
    }

    @Test
    void onboardedDistrictWithSchemes_isOnboarded() {
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 2, 101)).isTrue();
    }

    @Test
    void onboardedDistrictWithoutSchemes_isNotOnboarded() {
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 2, 102)).isFalse();
    }

    @Test
    void districtNotOnboarded_staysHiddenWhateverSchemesItHas() {
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 2, 103)).isFalse();
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 3, 131)).isFalse();
    }

    @Test
    void blockInOnboardedDistrict_isOnboardedOnlyWithASchemeOfItsOwn() {
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 3, 111)).isTrue();
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 3, 112)).isFalse();
    }

    @Test
    void departmentNode_countsOnlySchemesInOnboardedDistricts() {
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, DEPARTMENT, 2, 201)).isTrue();
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, DEPARTMENT, 2, 203)).isFalse();
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, DEPARTMENT, 2, 204)).isTrue();
    }

    @Test
    void placeholderSchemeWithoutDistrict_turnsNothingOn() {
        assertThat(repository.hasSchemeInOnboardedDistrict(TENANT, DEPARTMENT, 2, 205)).isFalse();
        assertThat(repository.findOnboardedChildIds(TENANT, DEPARTMENT, 1, 200)).doesNotContain(205);
    }

    @Test
    void findOnboardedChildIds_lgd_listsOnlyChildrenWithSchemesInOnboardedDistricts() {
        assertThat(repository.findOnboardedChildIds(TENANT, LGD, 1, 100)).containsExactly(101);
        assertThat(repository.findOnboardedChildIds(TENANT, LGD, 2, 101)).containsExactly(111);
        assertThat(repository.findOnboardedChildIds(TENANT, LGD, 2, 103)).isEmpty();
    }

    @Test
    void findOnboardedChildIds_department_listsEachChildOnceInIdOrder() {
        assertThat(repository.findOnboardedChildIds(TENANT, DEPARTMENT, 1, 200)).containsExactly(201, 204);
    }

    @Test
    void findOnboardedChildIds_deepestLevel_isEmpty() {
        assertThat(repository.findOnboardedChildIds(TENANT, LGD, RegionOnboardingRepository.MAX_LEVEL, 101))
                .isEmpty();
    }

    @Test
    void unsupportedLevel_isRejected() {
        assertThatThrownBy(() -> repository.hasSchemeInOnboardedDistrict(TENANT, LGD, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported LGD level: 0");
        assertThatThrownBy(() -> repository.findOnboardedChildIds(TENANT, DEPARTMENT, 0, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported DEPARTMENT level: 0");
    }

    @Test
    void findLevel_readsTheTenantsOwnDimensionRow() {
        assertThat(repository.findLevel(TENANT, LGD, 100)).contains(1);
        assertThat(repository.findLevel(TENANT, LGD, 111)).contains(3);
        assertThat(repository.findLevel(TENANT, DEPARTMENT, 201)).contains(2);
        assertThat(repository.findLevel(TENANT, LGD, 999)).isEmpty();
        assertThat(repository.findLevel(OTHER_TENANT, LGD, 111)).isEmpty();
        assertThat(repository.findLevel(OTHER_TENANT, DEPARTMENT, 201)).isEmpty();
    }

    @Test
    void sameIdsInAnotherTenant_doNotLeak() {
        assertThat(repository.hasSchemeInOnboardedDistrict(OTHER_TENANT, LGD, 2, 101)).isFalse();
        assertThat(repository.findOnboardedChildIds(OTHER_TENANT, LGD, 1, 100)).isEmpty();
        // Tenant 1's district 103 has a scheme but is not onboarded; tenant 2's 103 is onboarded.
        assertThat(repository.findOnboardedChildIds(TENANT, LGD, 1, 100)).doesNotContain(103);
    }

    @Test
    void newDistrict_isNotOnboardedByDefault() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, title, lgd_level, level_1_lgd_id, level_2_lgd_id)
                VALUES (105, 1, 'District 105', 2, 100, 105)
                """);

        Boolean onboarded = jdbcTemplate.queryForObject(
                "SELECT is_onboarded FROM analytics_schema.dim_lgd_location_table WHERE tenant_id = 1 AND lgd_id = 105",
                Boolean.class);
        assertThat(onboarded).isFalse();
    }

    /** V62 adds the check NOT VALID; V63 validates it. */
    @Test
    void districtOnlyCheck_isValidated() {
        Boolean validated = jdbcTemplate.queryForObject("""
                SELECT convalidated FROM pg_constraint
                WHERE conrelid = 'analytics_schema.dim_lgd_location_table'::regclass
                  AND conname = 'ck_dim_lgd_location_is_onboarded_district_only'
                """, Boolean.class);
        assertThat(validated).isTrue();
    }

    /** One violation per run: the first one aborts the test's transaction. */
    @ParameterizedTest(name = "lgd_id {0}")
    @ValueSource(ints = {100, 111})
    void onlyDistrictRowsCanBeOnboarded(int nonDistrictLgdId) {
        assertThatThrownBy(() -> setOnboarded(TENANT, nonDistrictLgdId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_dim_lgd_location_is_onboarded_district_only");
    }

    private void seed() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at)
                VALUES (1, 'pb', 'Punjab', 'IN', 1, NOW()),
                       (2, 'os', 'Other State', 'IN', 1, NOW())
                """);

        insertLgd(TENANT, 100, 1, 100, null, null, false);
        insertLgd(TENANT, 101, 2, 100, 101, null, true);
        insertLgd(TENANT, 102, 2, 100, 102, null, true);
        insertLgd(TENANT, 103, 2, 100, 103, null, false);
        insertLgd(TENANT, 111, 3, 100, 101, 111, false);
        insertLgd(TENANT, 112, 3, 100, 101, 112, false);
        insertLgd(TENANT, 131, 3, 100, 103, 131, false);
        insertLgd(OTHER_TENANT, 100, 1, 100, null, null, false);
        insertLgd(OTHER_TENANT, 101, 2, 100, 101, null, false);
        insertLgd(OTHER_TENANT, 103, 2, 100, 103, null, true);

        insertDepartment(TENANT, 200, 1, 200, null);
        insertDepartment(TENANT, 201, 2, 200, 201);
        insertDepartment(TENANT, 203, 2, 200, 203);
        insertDepartment(TENANT, 204, 2, 200, 204);
        insertDepartment(TENANT, 205, 2, 200, 205);

        insertScheme(TENANT, 1, 100, 101, 111, 200, 201);
        insertScheme(TENANT, 2, 100, 103, 131, 200, 203);
        insertScheme(TENANT, 4, 100, 101, null, 200, 204);
        insertScheme(TENANT, 5, 100, 103, null, 200, 204);
        insertScheme(TENANT, 3, 100, null, null, 200, 205);
        insertScheme(OTHER_TENANT, 1, 100, 101, null, null, null);
    }

    private void insertLgd(int tenantId, int lgdId, int level, Integer level1, Integer level2, Integer level3,
                           boolean onboarded) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, title, lgd_level, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, is_onboarded)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, lgdId, tenantId, "LGD " + lgdId, level, level1, level2, level3, onboarded);
    }

    private void insertDepartment(int tenantId, int departmentId, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, title, department_level, level_1_dept_id, level_2_dept_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, departmentId, tenantId, "Dept " + departmentId, level, level1, level2);
    }

    private void insertScheme(int tenantId, int schemeId, Integer lgd1, Integer lgd2, Integer lgd3,
                              Integer dept1, Integer dept2) {
        Integer parentLgd = lgd3 != null ? lgd3 : (lgd2 != null ? lgd2 : lgd1);
        Integer parentDept = dept2 != null ? dept2 : dept1;
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """, schemeId, tenantId, "Scheme " + schemeId, schemeId, schemeId,
                parentLgd, lgd1, lgd2, lgd3, parentDept, dept1, dept2);
    }

    private void setOnboarded(int tenantId, int lgdId) {
        jdbcTemplate.update(
                "UPDATE analytics_schema.dim_lgd_location_table SET is_onboarded = TRUE WHERE tenant_id = ? AND lgd_id = ?",
                tenantId, lgdId);
    }
}
