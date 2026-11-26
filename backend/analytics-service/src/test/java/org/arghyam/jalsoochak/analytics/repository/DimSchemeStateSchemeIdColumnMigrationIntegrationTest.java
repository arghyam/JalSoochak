package org.arghyam.jalsoochak.analytics.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests for the V64 migration, which widens {@code dim_scheme_table.state_scheme_id} from
 * INT to VARCHAR(255) so alphanumeric state ids (Punjab's {@code TW20826}) fit. Flyway runs the real
 * migrations up to V63 and rows are seeded the way production holds them; V64 first runs while another
 * session reads the table, so it has to give up on its lock, then runs again once the read ends.
 */
@Testcontainers
@DisplayName("V64 dim_scheme_table.state_scheme_id to VARCHAR migration")
class DimSchemeStateSchemeIdColumnMigrationIntegrationTest {

    private static final String TABLE = "analytics_schema.dim_scheme_table";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbcTemplate;
    private static Throwable lockedOutFailure;
    private static String typeAfterLockedOutRun;

    @BeforeAll
    static void seedThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        flyway(MigrationVersion.fromVersion("63")).migrate();
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'as', 'State 1', 'IN', 1, NOW(), NOW())
                """);
        // 5001 as a real numeric id; 0 as scheme-service sends an id it cannot parse.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, state_scheme_id, centre_scheme_id, parent_lgd_location_id, created_at)
                VALUES (1, 101, 5001, 6001, 0, NOW()),
                       (1, 102, 0, 0, 0, NOW())
                """);

        try (Connection reader = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = reader.createStatement()) {
            reader.setAutoCommit(false);
            statement.execute("LOCK TABLE " + TABLE + " IN ACCESS SHARE MODE");
            lockedOutFailure = catchThrowable(() -> flyway(MigrationVersion.LATEST).migrate());
            reader.commit();
        }
        typeAfterLockedOutRun = column("state_scheme_id").get("data_type").toString();

        flyway(MigrationVersion.LATEST).migrate();
    }

    private static Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("analytics_schema")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Map<String, Object> column(String name) {
        return jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'analytics_schema' AND table_name = 'dim_scheme_table'
                  AND column_name = ?
                """, name);
    }

    @Test
    @DisplayName("state_scheme_id becomes a NOT NULL varchar(255)")
    void widensTheColumnToVarchar() {
        assertThat(column("state_scheme_id"))
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 255)
                .containsEntry("is_nullable", "NO");
    }

    @Test
    @DisplayName("Existing numeric ids, 0 included, are kept as their text")
    void keepsExistingIdsAsText() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT scheme_id, state_scheme_id FROM " + TABLE + " WHERE scheme_id IN (101, 102) ORDER BY scheme_id");

        assertThat(rows).containsExactly(
                Map.of("scheme_id", 101, "state_scheme_id", "5001"),
                Map.of("scheme_id", 102, "state_scheme_id", "0"));
    }

    @Test
    @DisplayName("An alphanumeric id can be stored")
    void storesAnAlphanumericId() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, state_scheme_id, centre_scheme_id, parent_lgd_location_id, created_at)
                VALUES (1, 103, ?, 0, 0, NOW())
                """, "TW20826");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT state_scheme_id FROM " + TABLE + " WHERE scheme_id = 103", String.class))
                .isEqualTo("TW20826");
    }

    @Test
    @DisplayName("centre_scheme_id stays an integer")
    void leavesTheCentreIdAnInteger() {
        assertThat(column("centre_scheme_id")).containsEntry("data_type", "integer");
    }

    @Test
    @DisplayName("The column comment says the id is the state's own, possibly alphanumeric")
    void commentsTheColumn() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT col_description(?::regclass, ?)", String.class, TABLE,
                jdbcTemplate.queryForObject("""
                        SELECT attnum FROM pg_attribute
                        WHERE attrelid = ?::regclass AND attname = 'state_scheme_id'
                        """, Integer.class, TABLE)))
                .containsIgnoringCase("alphanumeric");
    }

    @Test
    @DisplayName("A read holding the table makes V64 give up on its lock and leave the column as it was")
    void givesUpOnAHeldLock() {
        assertThat(lockedOutFailure).as("first V64 run").isNotNull()
                .rootCause().hasMessageContaining("lock timeout");
        assertThat(typeAfterLockedOutRun).isEqualTo("integer");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM analytics_schema.flyway_schema_history
                WHERE version = '64' AND success
                """, Integer.class)).isEqualTo(1);
    }
}
