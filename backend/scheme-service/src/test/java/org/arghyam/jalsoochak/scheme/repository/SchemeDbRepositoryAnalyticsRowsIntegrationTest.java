package org.arghyam.jalsoochak.scheme.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeDbRepository#findSchemeAnalyticsRowsBySchemeIds} and
 * {@link SchemeDbRepository#findSchemeAnalyticsRowsByStateSchemeIds} against a real PostgreSQL
 * instance: each live scheme comes back once, with its details and FHTC counts.
 */
@Testcontainers
class SchemeDbRepositoryAnalyticsRowsIntegrationTest {

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
    void clearSchemes() {
        jdbcTemplate.execute("DELETE FROM tenant_mp.flow_reading_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_scheme_mapping_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_master_table");
        repository = new SchemeDbRepository(jdbcTemplate);
    }

    private int insertScheme(String stateSchemeId, boolean deleted) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, fhtc_count, planned_fhtc, house_hold_count,
                     latitude, longitude, work_status, operating_status, deleted_at)
                VALUES (?, 'CS-1', 'Scheme One', 10, 12, 30, 11.11, 22.22, 2, 1,
                        CASE WHEN ? THEN NOW() END)
                RETURNING id
                """, Integer.class, stateSchemeId, deleted);
    }

    @Test
    void findsALiveSchemeByIdWithItsDetailsAndFhtcCounts() {
        int live = insertScheme("SS-1", false);
        int deleted = insertScheme("SS-2", true);

        List<SchemeDbRepository.SchemeAnalyticsRow> rows =
                repository.findSchemeAnalyticsRowsBySchemeIds(SCHEMA, List.of(live, deleted));

        assertThat(rows).containsExactly(new SchemeDbRepository.SchemeAnalyticsRow(
                live, "SS-1", "CS-1", "Scheme One", 10, 12, 30, 11.11, 22.22, 2, 1));
    }

    @Test
    void findsASchemeByStateSchemeIdIgnoringCase() {
        int id = insertScheme("SS-Upper", false);

        List<SchemeDbRepository.SchemeAnalyticsRow> rows =
                repository.findSchemeAnalyticsRowsByStateSchemeIds(SCHEMA, List.of("ss-upper"));

        assertThat(rows).containsExactly(new SchemeDbRepository.SchemeAnalyticsRow(
                id, "SS-Upper", "CS-1", "Scheme One", 10, 12, 30, 11.11, 22.22, 2, 1));
    }
}
