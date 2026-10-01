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
 * {@code create_tenant_schema()} wrapper chain V46 has to patch is the one production holds. Three
 * tenant schemas are then provisioned — two fully, one left bare — and V46 runs while another session
 * holds a lock on the later full tenant's {@code flow_reading_table} for longer than V46's
 * {@code lock_timeout}, so the migration only succeeds if it retries. PostgreSQL logs notices here,
 * so the retry can be read back from its log. A fourth tenant is still being provisioned, its
 * transaction open, when V46 starts.
 */
@Testcontainers
@DisplayName("V46 OCR correlation column rename migration")
class OcrCorrelationIdColumnMigrationIntegrationTest {

    private static final String PRE_V46_COLUMN = "flowvision_correlation_id";
    private static final String COLUMN = "ocr_correlation_id";

    /** Provisioned before V46, so it carries the pre-V46 column and index; locked while V46 renames it. */
    private static final String EXISTING_TENANT = "tenant_aa";
    /** Provisioned before V46 and ordered before {@link #EXISTING_TENANT}, so V46 renames it first. */
    private static final String EARLIER_TENANT = "tenant_a0";
    /** A partially provisioned schema with no flow_reading_table, which V46 must skip. */
    private static final String BARE_TENANT = "tenant_bb";
    /** Provisioned after V46, through the patched function chain. */
    private static final String NEW_TENANT = "tenant_cc";
    /** Provisioned by the unpatched function in a transaction that commits only once V46 has started. */
    private static final String IN_FLIGHT_TENANT = "tenant_dd";

    /** Longer than V46's 3s lock_timeout, and well inside its five attempts. */
    private static final Duration LOCK_HELD_FOR = Duration.ofMillis(4_500);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(3);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "fsync=off", "-c", "log_min_messages=notice");

    private static JdbcTemplate jdbcTemplate;
    private static Integer preV46AttributeNumber;
    private static Duration v46Duration;
    private static boolean earlierTenantRenamedWhileV46Waited;
    private static boolean v46WaitedForInFlightProvisioning;

    @BeforeAll
    static void provisionThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        flyway(MigrationVersion.fromVersion("45")).migrate();
        jdbcTemplate.execute("SELECT common_schema.create_tenant_schema('" + EARLIER_TENANT + "')");
        jdbcTemplate.execute("SELECT common_schema.create_tenant_schema('" + EXISTING_TENANT + "')");
        jdbcTemplate.execute("CREATE SCHEMA " + BARE_TENANT);
        preV46AttributeNumber = attributeNumber(EXISTING_TENANT, PRE_V46_COLUMN);

        long start;
        CompletableFuture<Void> migration;
        CompletableFuture<Void> lockHolder;
        try (Connection provisioning = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = provisioning.createStatement()) {
            // Stands in for createTenant's transaction: its tenant_master_table write, then provisioning.
            provisioning.setAutoCommit(false);
            statement.execute("LOCK TABLE common_schema.tenant_master_table IN ROW EXCLUSIVE MODE");
            statement.execute("SELECT common_schema.create_tenant_schema('" + IN_FLIGHT_TENANT + "')");

            start = System.nanoTime();
            migration = CompletableFuture.runAsync(() -> flyway(MigrationVersion.LATEST).migrate());
            v46WaitedForInFlightProvisioning =
                    awaitLockWaiterOn("common_schema.tenant_master_table", migration);

            CountDownLatch locked = new CountDownLatch(1);
            lockHolder = CompletableFuture.runAsync(() -> holdLock(locked));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("lock acquired").isTrue();
            provisioning.commit();
        }
        earlierTenantRenamedWhileV46Waited =
                awaitLockWaiterOn(EXISTING_TENANT + ".flow_reading_table", migration)
                        && attributeNumber(EARLIER_TENANT, COLUMN) != null;
        migration.join();
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

    /** Whether a session queued for a lock on the table before the migration ended. */
    private static boolean awaitLockWaiterOn(String table, CompletableFuture<Void> migration)
            throws InterruptedException {
        long deadline = System.nanoTime() + LOCK_HELD_FOR.toNanos();
        while (!migration.isDone() && System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks
                        WHERE relation = to_regclass(?) AND NOT granted)
                    """, Boolean.class, table))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
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
    @DisplayName("A tenant still being provisioned when V46 starts is waited for, then renamed")
    void renamesATenantProvisionedWhileV46Starts() {
        assertThat(attributeNumber(IN_FLIGHT_TENANT, PRE_V46_COLUMN)).isNull();
        assertThat(attributeNumber(IN_FLIGHT_TENANT, COLUMN)).isNotNull();
        assertThat(indexesOn(IN_FLIGHT_TENANT, COLUMN)).containsExactly("idx_tenant_dd_flow_ocr_corr");
        assertThat(v46WaitedForInFlightProvisioning).as("V46 waited for the open provisioning").isTrue();
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
        assertThat(postgres.getLogs()).as("V46 timed out on the held lock and retried")
                .contains("V46: lock not available for \"ALTER TABLE " + EXISTING_TENANT
                        + ".flow_reading_table RENAME COLUMN");
        assertThat(v46Duration).as("V46 waited on the held lock").isGreaterThan(LOCK_TIMEOUT);
    }

    @Test
    @DisplayName("Each tenant's renames commit before V46 waits on the next tenant's lock")
    void commitsEachTenantBeforeTheNext() {
        assertThat(earlierTenantRenamedWhileV46Waited)
                .as("%s renamed and visible while V46 waited on %s", EARLIER_TENANT, EXISTING_TENANT)
                .isTrue();
    }
}
