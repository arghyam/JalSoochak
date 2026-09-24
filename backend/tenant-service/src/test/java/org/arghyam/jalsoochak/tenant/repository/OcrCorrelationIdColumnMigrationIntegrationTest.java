package org.arghyam.jalsoochak.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

/**
 * Integration tests for the V46 migration, which renames the column holding the OCR provider's own
 * correlation id, and its index, to {@code ocr_correlation_id}, against real PostgreSQL via
 * Testcontainers.
 *
 * <p>Flyway runs the <b>real migrations</b> from {@code classpath:db/migration/} up to V45, so the
 * {@code create_tenant_schema()} wrapper chain V46 has to patch is the one production holds. Two
 * tenant schemas are then provisioned — one fully, one left bare — and V46 runs while another session
 * holds a lock on the full tenant's {@code flow_reading_table} for longer than V46's
 * {@code lock_timeout}, so the migration only succeeds if it retries.
 */
@Testcontainers
@DisplayName("V46 OCR correlation column rename migration")
class OcrCorrelationIdColumnMigrationIntegrationTest {

    private static final String PRE_V46_COLUMN = "flowvision_correlation_id";
    private static final String COLUMN = "ocr_correlation_id";

    /** Provisioned before V46, so it carries the pre-V46 column and index. */
    private static final String EXISTING_TENANT = "tenant_aa";
    /** A partially provisioned schema with no flow_reading_table, which V46 must skip. */
    private static final String BARE_TENANT = "tenant_bb";
    /** Provisioned after V46, through the patched function chain. */
    private static final String NEW_TENANT = "tenant_cc";

    /** Longer than V46's 3s lock_timeout, and well inside its five attempts. */
    private static final Duration LOCK_HELD_FOR = Duration.ofMillis(4_500);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(3);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static Integer preV46AttributeNumber;
    private static Duration v46Duration;

    @BeforeAll
    static void provisionThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        flyway(MigrationVersion.fromVersion("45")).migrate();
        jdbcTemplate.execute("SELECT common_schema.create_tenant_schema('" + EXISTING_TENANT + "')");
        jdbcTemplate.execute("CREATE SCHEMA " + BARE_TENANT);
        preV46AttributeNumber = attributeNumber(EXISTING_TENANT, PRE_V46_COLUMN);

        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> lockHolder = CompletableFuture.runAsync(() -> holdLock(locked));
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("lock acquired").isTrue();

        long start = System.nanoTime();
        flyway(MigrationVersion.LATEST).migrate();
        v46Duration = Duration.ofNanos(System.nanoTime() - start);
        lockHolder.join();

        jdbcTemplate.execute("SELECT common_schema.create_tenant_schema('" + NEW_TENANT + "')");
    }

    private static Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("common_schema")
                .defaultSchema("common_schema")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void holdLock(CountDownLatch locked) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE " + EXISTING_TENANT + ".flow_reading_table IN ACCESS SHARE MODE");
            locked.countDown();
            Thread.sleep(LOCK_HELD_FOR.toMillis());
            connection.commit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Integer attributeNumber(String schema, String column) {
        List<Integer> rows = jdbcTemplate.queryForList("""
                SELECT attnum FROM pg_attribute
                WHERE attrelid = to_regclass(format('%I.flow_reading_table', ?::text))
                  AND attname = ?
                  AND NOT attisdropped
                """, Integer.class, schema, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> indexesOn(String schema, String column) {
        return jdbcTemplate.queryForList("""
                SELECT i.relname
                FROM pg_index x
                JOIN pg_class i     ON i.oid = x.indexrelid
                JOIN pg_attribute a ON a.attrelid = x.indrelid AND a.attnum = ANY (x.indkey)
                WHERE x.indrelid = to_regclass(format('%I.flow_reading_table', ?::text))
                  AND a.attname = ?
                """, String.class, schema, column);
    }

    @Test
    @DisplayName("An existing tenant's column is renamed in place, not dropped and re-added")
    void renamesTheColumnInPlace() {
        assertThat(preV46AttributeNumber).as("pre-V46 column provisioned by V45").isNotNull();
        assertThat(attributeNumber(EXISTING_TENANT, COLUMN)).isEqualTo(preV46AttributeNumber);
        assertThat(attributeNumber(EXISTING_TENANT, PRE_V46_COLUMN)).isNull();
    }

    @Test
    @DisplayName("An existing tenant's index is renamed rather than left under the old name")
    void renamesTheIndex() {
        assertThat(indexesOn(EXISTING_TENANT, COLUMN)).containsExactly("idx_tenant_aa_flow_ocr_corr");
    }

    @Test
    @DisplayName("A schema without flow_reading_table is skipped")
    void skipsABareSchema() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NULL", Boolean.class, BARE_TENANT + ".flow_reading_table"))
                .isTrue();
    }

    @Test
    @DisplayName("A tenant provisioned after V46 gets only the new column and index")
    void provisionsNewTenantsWithTheNewNames() {
        assertThat(attributeNumber(NEW_TENANT, COLUMN)).isNotNull();
        assertThat(attributeNumber(NEW_TENANT, PRE_V46_COLUMN)).isNull();
        assertThat(indexesOn(NEW_TENANT, COLUMN)).containsExactly("idx_tenant_cc_flow_ocr_corr");
    }

    @Test
    @DisplayName("No function in common_schema names the pre-V46 column any more")
    void leavesNoFunctionNamingTheOldColumn() {
        Integer functions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'common_schema' AND p.prosrc LIKE '%' || ? || '%'
                """, Integer.class, PRE_V46_COLUMN);

        assertThat(functions).isZero();
    }

    @Test
    @DisplayName("A lock held past lock_timeout is waited out by retrying, not failed on")
    void retriesALockHeldPastItsLockTimeout() {
        Boolean succeeded = jdbcTemplate.queryForObject(
                "SELECT success FROM common_schema.flyway_schema_history WHERE version = '46'", Boolean.class);

        assertThat(succeeded).isTrue();
        assertThat(v46Duration).as("V46 waited on the held lock").isGreaterThan(LOCK_TIMEOUT);
    }
}
