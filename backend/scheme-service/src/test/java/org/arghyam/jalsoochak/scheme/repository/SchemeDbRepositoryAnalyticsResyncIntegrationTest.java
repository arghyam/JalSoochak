package org.arghyam.jalsoochak.scheme.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeDbRepository#findTenantIdBySchemaName} and {@link SchemeDbRepository#findAllSchemeIds}
 * against a real PostgreSQL instance: the two lookups the analytics resync starts from.
 */
@Testcontainers
class SchemeDbRepositoryAnalyticsResyncIntegrationTest {

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
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_master_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_master_table");
        repository = new SchemeDbRepository(jdbcTemplate);
    }

    private int insertTenant(String stateCode, boolean deleted) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO common_schema.tenant_master_table (state_code, deleted_at)
                VALUES (?, CASE WHEN ? THEN NOW() END)
                RETURNING id
                """, Integer.class, stateCode, deleted);
    }

    private int insertScheme(String stateSchemeId, boolean deleted) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status, deleted_at)
                VALUES (?, 'CS-1', 'Scheme', 1, 1, CASE WHEN ? THEN NOW() END)
                RETURNING id
                """, Integer.class, stateSchemeId, deleted);
    }

    @Test
    void findsTheTenantWhoseUpperCaseStateCodeNamesTheSchema() {
        insertTenant("UP", false);
        int mp = insertTenant("MP", false);

        assertThat(repository.findTenantIdBySchemaName(SCHEMA)).isEqualTo(mp);
    }

    @Test
    void findsNoTenantForADeletedOrUnknownStateCode() {
        insertTenant("MP", true);

        assertThat(repository.findTenantIdBySchemaName(SCHEMA)).isNull();
        assertThat(repository.findTenantIdBySchemaName("tenant_zz")).isNull();
    }

    @Test
    void findsEverySchemeThatIsNotDeleted_inIdOrder() {
        int first = insertScheme("SS-1", false);
        insertScheme("SS-2", true);
        int third = insertScheme("SS-3", false);

        assertThat(repository.findAllSchemeIds(SCHEMA)).containsExactly(first, third);
    }

    @Test
    void findsNoSchemesInAnEmptyTenant() {
        assertThat(repository.findAllSchemeIds(SCHEMA)).isEmpty();
    }
}
