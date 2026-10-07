package org.arghyam.jalsoochak.scheme.repository;

import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository.MappedLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeDbRepository#findSchemeVillagesBySchemeIds} and
 * {@link SchemeDbRepository#findSchemeSubDivisionsBySchemeIds} against a real PostgreSQL instance: each
 * mapped location comes back with its ancestors placed at the levels their location configs give.
 */
@Testcontainers
class SchemeDbRepositoryMappedLocationsIntegrationTest {

    private static final String SCHEMA = "tenant_mp";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    private SchemeDbRepository repository;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_lgd_mapping_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_department_mapping_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_master_table");
        jdbcTemplate.execute("UPDATE tenant_mp.lgd_location_master_table SET parent_id = NULL");
        jdbcTemplate.execute("DELETE FROM tenant_mp.lgd_location_master_table");
        jdbcTemplate.execute("UPDATE tenant_mp.department_location_master_table SET parent_id = NULL");
        jdbcTemplate.execute("DELETE FROM tenant_mp.department_location_master_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.location_config_master_table");
        repository = new SchemeDbRepository(jdbcTemplate);
    }

    private int insertScheme(String stateSchemeId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status)
                VALUES (?, 'CS-1', 'Scheme', 1, 1)
                RETURNING id
                """, Integer.class, stateSchemeId);
    }

    private int insertConfig(int level) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tenant_mp.location_config_master_table (level) VALUES (?) RETURNING id",
                Integer.class, level);
    }

    private int insertVillageTreeNode(Integer configId, Integer parentId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.lgd_location_master_table (lgd_location_config_id, parent_id)
                VALUES (?, ?)
                RETURNING id
                """, Integer.class, configId, parentId);
    }

    private int insertDepartmentTreeNode(Integer configId, Integer parentId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.department_location_master_table (department_location_config_id, parent_id)
                VALUES (?, ?)
                RETURNING id
                """, Integer.class, configId, parentId);
    }

    private void mapVillage(int schemeId, int lgdId, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO tenant_mp.scheme_lgd_mapping_table (scheme_id, parent_lgd_id, deleted_at)
                VALUES (?, ?, CASE WHEN ? THEN NOW() END)
                """, schemeId, lgdId, deleted);
    }

    private void mapSubDivision(int schemeId, int departmentId) {
        jdbcTemplate.update("""
                INSERT INTO tenant_mp.scheme_department_mapping_table (scheme_id, parent_department_id)
                VALUES (?, ?)
                """, schemeId, departmentId);
    }

    @Test
    void placesEachVillageAndItsAncestorsAtTheirOwnLevels() {
        int state = insertVillageTreeNode(insertConfig(1), null);
        int district = insertVillageTreeNode(insertConfig(2), state);
        int block = insertVillageTreeNode(insertConfig(3), district);
        int panchayat = insertVillageTreeNode(insertConfig(4), block);
        int cluster = insertVillageTreeNode(insertConfig(5), panchayat);
        int village = insertVillageTreeNode(insertConfig(6), cluster);
        int scheme = insertScheme("SS-1");
        mapVillage(scheme, village, false);
        mapVillage(scheme, panchayat, false);

        Map<Integer, List<MappedLocation>> villages =
                repository.findSchemeVillagesBySchemeIds(SCHEMA, List.of(scheme));

        assertThat(villages).containsOnlyKeys(scheme);
        assertThat(villages.get(scheme)).containsExactly(
                new MappedLocation(panchayat, state, district, block, panchayat, null, null),
                new MappedLocation(village, state, district, block, panchayat, cluster, village));
    }

    @Test
    void placesEachSubDivisionAndItsAncestorsAtTheirOwnLevels() {
        int zone = insertDepartmentTreeNode(insertConfig(1), null);
        int circle = insertDepartmentTreeNode(insertConfig(2), zone);
        int division = insertDepartmentTreeNode(insertConfig(3), circle);
        int subDivision = insertDepartmentTreeNode(insertConfig(4), division);
        int scheme = insertScheme("SS-1");
        mapSubDivision(scheme, subDivision);

        Map<Integer, List<MappedLocation>> subDivisions =
                repository.findSchemeSubDivisionsBySchemeIds(SCHEMA, List.of(scheme));

        assertThat(subDivisions).containsExactly(Map.entry(scheme,
                List.of(new MappedLocation(subDivision, zone, circle, division, subDivision, null, null))));
    }

    @Test
    void groupsLocationsBySchemeAndLeavesOutDeletedMappingsAndUnmappedSchemes() {
        int config = insertConfig(1);
        int first = insertVillageTreeNode(config, null);
        int second = insertVillageTreeNode(config, null);
        int schemeA = insertScheme("SS-A");
        int schemeB = insertScheme("SS-B");
        int unmapped = insertScheme("SS-C");
        int onlyDeleted = insertScheme("SS-D");
        mapVillage(schemeA, first, false);
        mapVillage(schemeA, first, false);
        mapVillage(schemeA, second, true);
        mapVillage(schemeB, second, false);
        mapVillage(onlyDeleted, first, true);

        Map<Integer, List<MappedLocation>> villages = repository.findSchemeVillagesBySchemeIds(
                SCHEMA, List.of(schemeA, schemeB, unmapped, onlyDeleted));

        assertThat(villages).containsOnly(
                Map.entry(schemeA, List.of(new MappedLocation(first, first, null, null, null, null, null))),
                Map.entry(schemeB, List.of(new MappedLocation(second, second, null, null, null, null, null))));
    }

    @Test
    void leavesTheLevelsEmptyWhenTheLocationHasNoConfig() {
        int village = insertVillageTreeNode(null, null);
        int scheme = insertScheme("SS-1");
        mapVillage(scheme, village, false);

        Map<Integer, List<MappedLocation>> villages =
                repository.findSchemeVillagesBySchemeIds(SCHEMA, List.of(scheme));

        assertThat(villages.get(scheme))
                .containsExactly(new MappedLocation(village, null, null, null, null, null, null));
    }

    @Test
    void stopsWalkingUpWhenParentIdsLoop() {
        int district = insertVillageTreeNode(insertConfig(2), null);
        int village = insertVillageTreeNode(insertConfig(6), district);
        jdbcTemplate.update("UPDATE tenant_mp.lgd_location_master_table SET parent_id = ? WHERE id = ?",
                village, district);
        int scheme = insertScheme("SS-1");
        mapVillage(scheme, village, false);

        Map<Integer, List<MappedLocation>> villages =
                repository.findSchemeVillagesBySchemeIds(SCHEMA, List.of(scheme));

        assertThat(villages.get(scheme))
                .containsExactly(new MappedLocation(village, null, district, null, null, null, village));
    }
}
