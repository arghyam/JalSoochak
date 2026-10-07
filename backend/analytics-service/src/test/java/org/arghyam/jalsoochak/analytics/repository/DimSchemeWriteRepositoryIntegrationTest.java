package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeMappingsReplacedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeMappingsReplacedEvent.Location;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how a scheme reaches {@code dim_scheme_table}. Its details are written to every row of the scheme,
 * never moving a row to another village or sub-division, and a scheme that has no rows yet is placed
 * under its state. Its villages and sub-divisions, when they change, become one row per pair.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({DimSchemeWriteRepository.class, SchemeRegularityRepository.class})
class DimSchemeWriteRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_dim_scheme_write_test")
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
        // Dashboards count only Handed Over schemes, so a status change moves a scheme in or out.
        registry.add("analytics.dashboard.included-work-statuses", () -> String.valueOf(HANDED_OVER));
    }

    @Autowired
    private DimSchemeWriteRepository repository;

    @Autowired
    private SchemeRegularityRepository schemeRegularityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final int OTHER_TENANT = 2;
    private static final int SCHEME = 1;
    private static final int OTHER_SCHEME = 2;
    private static final int PARENT_LGD = 100;
    private static final int PARENT_DEPT = 200;

    private static final int COMPLETED = 2;
    private static final int HANDED_OVER = 4;
    private static final int OPERATIVE = 1;
    private static final int NON_OPERATIVE = 0;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE analytics_schema.dim_scheme_table,
                         analytics_schema.dim_lgd_location_table,
                         analytics_schema.dim_department_location_table,
                         analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
        insertTenant(TENANT);
        insertTenant(OTHER_TENANT);
        insertLgd(TENANT, PARENT_LGD, 1, PARENT_LGD, null);
        insertLgd(TENANT, 101, 2, PARENT_LGD, 101);
        insertLgd(TENANT, 102, 2, PARENT_LGD, 102);
    }

    @Test
    void upsertDetails_writesTheDetailsToEveryRowOfTheScheme() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);
        insertSchemeRow(TENANT, SCHEME, 102, 202);

        int written = repository.upsertDetails(details(TENANT, SCHEME, COMPLETED, NON_OPERATIVE, 30, 40, 50));

        assertThat(written).isEqualTo(2);
        assertThat(rows(TENANT, SCHEME)).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("scheme_name")).isEqualTo("Renamed");
            assertThat(row.get("state_scheme_id")).isEqualTo(7001);
            assertThat(row.get("centre_scheme_id")).isEqualTo(8001);
            assertThat(row.get("latitude")).isEqualTo(23.5);
            assertThat(row.get("longitude")).isEqualTo(77.5);
            assertThat(row.get("work_status")).isEqualTo(COMPLETED);
            assertThat(row.get("operating_status")).isEqualTo(NON_OPERATIVE);
            assertThat(row.get("fhtc_count")).isEqualTo(30);
            assertThat(row.get("planned_fhtc")).isEqualTo(40);
            assertThat(row.get("house_hold_count")).isEqualTo(50);
        });
    }

    @Test
    void upsertDetails_leavesEveryRowsVillageAndSubDivisionAsTheyWere() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);
        insertSchemeRow(TENANT, SCHEME, 102, 202);

        repository.upsertDetails(details(TENANT, SCHEME, COMPLETED, NON_OPERATIVE, 30, 40, 50));

        assertThat(rows(TENANT, SCHEME))
                .extracting(DimSchemeWriteRepositoryIntegrationTest::location)
                .containsExactlyInAnyOrder(
                        List.of(101, PARENT_LGD, 101, 201, PARENT_DEPT, 201),
                        List.of(102, PARENT_LGD, 102, 202, PARENT_DEPT, 202));
    }

    @Test
    void upsertDetails_keepsTheStoredFhtcCounts_whenTheMessageCarriesNone() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);

        repository.upsertDetails(details(TENANT, SCHEME, COMPLETED, NON_OPERATIVE, null, null, null));

        assertThat(rows(TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(row.get("work_status")).isEqualTo(COMPLETED);
            assertThat(row.get("fhtc_count")).isEqualTo(10);
            assertThat(row.get("planned_fhtc")).isEqualTo(11);
            assertThat(row.get("house_hold_count")).isEqualTo(12);
        });
    }

    @Test
    void upsertDetails_touchesNoOtherSchemeAndNoOtherTenant() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);
        insertSchemeRow(TENANT, OTHER_SCHEME, 101, 201);
        insertSchemeRow(OTHER_TENANT, SCHEME, 101, 201);

        repository.upsertDetails(details(TENANT, SCHEME, COMPLETED, NON_OPERATIVE, 30, 40, 50));

        assertThat(rows(TENANT, OTHER_SCHEME)).singleElement()
                .satisfies(row -> assertThat(row.get("scheme_name")).isEqualTo("Original"));
        assertThat(rows(OTHER_TENANT, SCHEME)).singleElement()
                .satisfies(row -> assertThat(row.get("scheme_name")).isEqualTo("Original"));
    }

    @Test
    void upsertDetails_insertsOnePlaceholderRowUnderTheState_forASchemeWithNoRows() {
        insertLgd(OTHER_TENANT, 50, 1, 50, null); // another tenant's state, which must not be picked

        repository.upsertDetails(details(TENANT, SCHEME, COMPLETED, NON_OPERATIVE, 30, 40, 50));
        repository.upsertDetails(details(TENANT, SCHEME, HANDED_OVER, OPERATIVE, null, null, null));

        assertThat(rows(TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(row.get("parent_lgd_location_id")).isEqualTo(PARENT_LGD);
            assertThat(row.get("level_1_lgd_id")).isEqualTo(PARENT_LGD);
            for (int level = 2; level <= 6; level++) {
                assertThat(row.get("level_" + level + "_lgd_id")).isNull();
            }
            assertThat(row.get("parent_department_location_id")).isNull();
            for (int level = 1; level <= 6; level++) {
                assertThat(row.get("level_" + level + "_dept_id")).isNull();
            }
            assertThat(row.get("scheme_name")).isEqualTo("Renamed");
            assertThat(row.get("work_status")).isEqualTo(HANDED_OVER);
            assertThat(row.get("operating_status")).isEqualTo(OPERATIVE);
            assertThat(row.get("fhtc_count")).isEqualTo(30);
            assertThat(row.get("created_at")).isNotNull();
            assertThat(row.get("updated_at")).isNotNull();
        });
    }

    @Test
    void upsertDetails_placeholderFallsBackToVillageZero_whenTheTenantsStateIsNotLoaded() {
        repository.upsertDetails(details(OTHER_TENANT, SCHEME, COMPLETED, NON_OPERATIVE, 30, 40, 50));

        assertThat(rows(OTHER_TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(row.get("parent_lgd_location_id")).isEqualTo(0);
            assertThat(row.get("level_1_lgd_id")).isNull();
        });
    }

    @Test
    void stateSchemeCount_includesANewSchemeBeforeItsVillagesAreKnown() {
        insertSchemeRow(TENANT, OTHER_SCHEME, 101, 201);

        repository.upsertDetails(details(TENANT, SCHEME, HANDED_OVER, OPERATIVE, null, null, null));

        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, PARENT_LGD)).isEqualTo(2);
        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, 101))
                .as("the placeholder is in no district")
                .isEqualTo(1);
    }

    @Test
    void filteredSchemeCount_followsTheNewStatusOnEveryRow() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);
        insertSchemeRow(TENANT, SCHEME, 102, 202);
        insertSchemeRow(TENANT, OTHER_SCHEME, 101, 201);
        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, PARENT_LGD))
                .as("two rows, one scheme: counted once")
                .isEqualTo(2);

        repository.upsertDetails(details(TENANT, SCHEME, COMPLETED, OPERATIVE, null, null, null));

        // Had any row kept HANDED_OVER, the scheme would still be counted.
        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, PARENT_LGD)).isEqualTo(1);
    }

    @Test
    void replaceMappings_givesTheSchemeOneRowPerVillageAndSubDivisionPair() {
        int written = repository.replaceMappings(mappings(TENANT, SCHEME,
                List.of(village(101), village(102)), List.of(subDivision(201), subDivision(202))));

        assertThat(written).isEqualTo(4);
        assertThat(rows(TENANT, SCHEME))
                .extracting(DimSchemeWriteRepositoryIntegrationTest::location)
                .containsExactlyInAnyOrder(
                        List.of(101, PARENT_LGD, 101, 201, PARENT_DEPT, 201),
                        List.of(101, PARENT_LGD, 101, 202, PARENT_DEPT, 202),
                        List.of(102, PARENT_LGD, 102, 201, PARENT_DEPT, 201),
                        List.of(102, PARENT_LGD, 102, 202, PARENT_DEPT, 202));
        assertThat(rows(TENANT, SCHEME)).allSatisfy(row -> {
            assertThat(row.get("scheme_name")).isEqualTo("Renamed");
            assertThat(row.get("state_scheme_id")).isEqualTo(7001);
            assertThat(row.get("work_status")).isEqualTo(HANDED_OVER);
            assertThat(row.get("fhtc_count")).isEqualTo(30);
            assertThat(row.get("created_at")).isNotNull();
            assertThat(row.get("updated_at")).isNotNull();
        });
    }

    @Test
    void replaceMappings_removesThePairsThatAreGone_andKeepsTheRowsOfThoseThatStay() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);
        insertSchemeRow(TENANT, SCHEME, 102, 202);
        Object keptRowId = rows(TENANT, SCHEME).stream()
                .filter(row -> row.get("parent_lgd_location_id").equals(101))
                .findFirst().orElseThrow().get("id");

        repository.replaceMappings(mappings(TENANT, SCHEME, List.of(village(101)), List.of(subDivision(201))));

        assertThat(rows(TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(row.get("id")).isEqualTo(keptRowId);
            assertThat(location(row)).isEqualTo(List.of(101, PARENT_LGD, 101, 201, PARENT_DEPT, 201));
            assertThat(row.get("scheme_name")).isEqualTo("Renamed");
            assertThat(row.get("fhtc_count")).isEqualTo(30);
        });
    }

    @Test
    void replaceMappings_updatesTheLevelIdsOfAPairThatStays() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);

        repository.replaceMappings(mappings(TENANT, SCHEME,
                List.of(new Location(101, PARENT_LGD, 102, 101, null, null, null)),
                List.of(new Location(201, PARENT_DEPT, 202, 201, null, null, null))));

        assertThat(rows(TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(row.get("level_2_lgd_id")).isEqualTo(102);
            assertThat(row.get("level_3_lgd_id")).isEqualTo(101);
            assertThat(row.get("level_2_dept_id")).isEqualTo(202);
            assertThat(row.get("level_3_dept_id")).isEqualTo(201);
        });
    }

    @Test
    void replaceMappings_removesThePlaceholder_onceTheSchemesVillagesAreKnown() {
        repository.upsertDetails(details(TENANT, SCHEME, HANDED_OVER, OPERATIVE, null, null, null));

        repository.replaceMappings(mappings(TENANT, SCHEME, List.of(village(101)), List.of(subDivision(201))));

        assertThat(rows(TENANT, SCHEME))
                .extracting(DimSchemeWriteRepositoryIntegrationTest::location)
                .containsExactly(List.of(101, PARENT_LGD, 101, 201, PARENT_DEPT, 201));
    }

    @Test
    void replaceMappings_placesASchemeWithNoVillagesUnderItsState_onceForEachSubDivision() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);

        repository.replaceMappings(mappings(TENANT, SCHEME,
                List.of(), List.of(subDivision(201), subDivision(202))));

        assertThat(rows(TENANT, SCHEME))
                .extracting(DimSchemeWriteRepositoryIntegrationTest::location)
                .containsExactlyInAnyOrder(
                        Arrays.asList(PARENT_LGD, PARENT_LGD, null, 201, PARENT_DEPT, 201),
                        Arrays.asList(PARENT_LGD, PARENT_LGD, null, 202, PARENT_DEPT, 202));
    }

    @Test
    void replaceMappings_leavesTheSubDivisionEmpty_forASchemeWithNone() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);

        repository.replaceMappings(mappings(TENANT, SCHEME, List.of(village(101)), List.of()));

        assertThat(rows(TENANT, SCHEME))
                .extracting(DimSchemeWriteRepositoryIntegrationTest::location)
                .containsExactly(Arrays.asList(101, PARENT_LGD, 101, null, null, null));
    }

    @Test
    void replaceMappings_leavesOnePlaceholderUnderTheState_forASchemeWithNoVillagesOrSubDivisions() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);
        insertSchemeRow(TENANT, SCHEME, 102, 202);

        repository.replaceMappings(mappings(TENANT, SCHEME, List.of(), List.of()));

        assertThat(rows(TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(location(row)).isEqualTo(Arrays.asList(PARENT_LGD, PARENT_LGD, null, null, null, null));
            assertThat(row.get("scheme_name")).isEqualTo("Renamed");
        });
    }

    @Test
    void replaceMappings_placesASchemeWithNoVillagesUnderVillageZero_whenTheTenantsStateIsNotLoaded() {
        repository.replaceMappings(mappings(OTHER_TENANT, SCHEME, List.of(), List.of(subDivision(201))));

        assertThat(rows(OTHER_TENANT, SCHEME))
                .extracting(DimSchemeWriteRepositoryIntegrationTest::location)
                .containsExactly(Arrays.asList(0, null, null, 201, PARENT_DEPT, 201));
    }

    @Test
    void replaceMappings_touchesNoOtherSchemeAndNoOtherTenant() {
        insertSchemeRow(TENANT, OTHER_SCHEME, 101, 201);
        insertSchemeRow(OTHER_TENANT, SCHEME, 101, 201);

        repository.replaceMappings(mappings(TENANT, SCHEME, List.of(village(102)), List.of(subDivision(202))));

        assertThat(rows(TENANT, OTHER_SCHEME)).singleElement().satisfies(row -> {
            assertThat(location(row)).isEqualTo(List.of(101, PARENT_LGD, 101, 201, PARENT_DEPT, 201));
            assertThat(row.get("scheme_name")).isEqualTo("Original");
        });
        assertThat(rows(OTHER_TENANT, SCHEME)).singleElement().satisfies(row -> {
            assertThat(location(row)).isEqualTo(List.of(101, PARENT_LGD, 101, 201, PARENT_DEPT, 201));
            assertThat(row.get("scheme_name")).isEqualTo("Original");
        });
    }

    @Test
    void districtSchemeCount_followsTheSchemeToItsNewVillages() {
        insertSchemeRow(TENANT, SCHEME, 101, 201);

        repository.replaceMappings(mappings(TENANT, SCHEME, List.of(village(102)), List.of(subDivision(201))));

        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, 101)).isZero();
        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, 102)).isEqualTo(1);
        assertThat(schemeRegularityRepository.getSchemeCountByLgdInScope(TENANT, PARENT_LGD)).isEqualTo(1);
    }

    private static SchemeEvent details(int tenantId, int schemeId, Integer workStatus, Integer operatingStatus,
                                       Integer fhtcCount, Integer plannedFhtc, Integer houseHoldCount) {
        return withDetails(new SchemeEvent(), "SCHEME_UPDATED", tenantId, schemeId, workStatus, operatingStatus,
                fhtcCount, plannedFhtc, houseHoldCount);
    }

    /** The scheme's villages and sub-divisions, sent with HANDED_OVER, OPERATIVE, FHTC 30/40/50. */
    private static SchemeMappingsReplacedEvent mappings(int tenantId, int schemeId, List<Location> villages,
                                                        List<Location> subDivisions) {
        SchemeMappingsReplacedEvent event = withDetails(new SchemeMappingsReplacedEvent(),
                "SCHEME_MAPPINGS_REPLACED", tenantId, schemeId, HANDED_OVER, OPERATIVE, 30, 40, 50);
        event.setVillages(villages);
        event.setSubDivisions(subDivisions);
        return event;
    }

    private static <T extends SchemeEvent> T withDetails(T event, String eventType, int tenantId, int schemeId,
                                                         Integer workStatus, Integer operatingStatus,
                                                         Integer fhtcCount, Integer plannedFhtc,
                                                         Integer houseHoldCount) {
        event.setEventType(eventType);
        event.setTenantId(tenantId);
        event.setSchemeId(schemeId);
        event.setSchemeName("Renamed");
        event.setStateSchemeId(7001);
        event.setCentreSchemeId(8001);
        event.setLatitude(23.5);
        event.setLongitude(77.5);
        event.setWorkStatus(workStatus);
        event.setStatus(operatingStatus);
        event.setFhtcCount(fhtcCount);
        event.setPlannedFhtc(plannedFhtc);
        event.setHouseHoldCount(houseHoldCount);
        return event;
    }

    /** A village in a district: its state at level 1, itself at level 2. */
    private static Location village(int lgdId) {
        return new Location(lgdId, PARENT_LGD, lgdId, null, null, null, null);
    }

    /** A sub-division under the top department at level 1, itself at level 2. */
    private static Location subDivision(int departmentId) {
        return new Location(departmentId, PARENT_DEPT, departmentId, null, null, null, null);
    }

    /** The row's village, its level 1 and 2 ids, then its sub-division and that one's level 1 and 2 ids. */
    private static List<Object> location(Map<String, Object> row) {
        return Arrays.asList(
                row.get("parent_lgd_location_id"), row.get("level_1_lgd_id"), row.get("level_2_lgd_id"),
                row.get("parent_department_location_id"), row.get("level_1_dept_id"), row.get("level_2_dept_id"));
    }

    private List<Map<String, Object>> rows(int tenantId, int schemeId) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM analytics_schema.dim_scheme_table
                WHERE tenant_id = ? AND scheme_id = ?
                """, tenantId, schemeId);
    }

    private void insertTenant(int tenantId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, 'IN', 1, NOW(), NOW())
                """, tenantId, "s" + tenantId, "State " + tenantId);
    }

    private void insertLgd(int tenantId, int lgdId, int level, Integer level1, Integer level2) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, lgdId, tenantId, "L" + lgdId, "LGD " + lgdId, "LGD " + lgdId, level, level1, level2);
    }

    /** A row as an earlier message left it: HANDED_OVER, OPERATIVE, FHTC 10/11/12. */
    private void insertSchemeRow(int tenantId, int schemeId, int village, int subDivision) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id,
                 operating_status, work_status, fhtc_count, planned_fhtc, house_hold_count,
                 created_at, updated_at)
                VALUES (?, ?, 'Original', 1000, 2000, 0.0, 0.0, ?, ?, ?, ?, ?, ?, ?, ?, 10, 11, 12,
                        TIMESTAMP '2026-01-01 00:00', TIMESTAMP '2026-01-01 00:00')
                """, tenantId, schemeId, village, PARENT_LGD, village, subDivision, PARENT_DEPT, subDivision,
                OPERATIVE, HANDED_OVER);
    }
}
