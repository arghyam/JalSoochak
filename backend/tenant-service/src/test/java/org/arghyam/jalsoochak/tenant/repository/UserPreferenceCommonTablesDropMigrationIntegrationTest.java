package org.arghyam.jalsoochak.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
 * Integration tests for the V48 migration, which drops the {@code common_schema} user preference
 * tables and the triggers V47 bridged the rollout with, against real PostgreSQL via Testcontainers.
 *
 * <p>Flyway runs the <b>real migrations</b> from {@code classpath:db/migration/}: up to V46, where a
 * tenant is provisioned and common_schema rows seeded, then V47, which moves them. Writes are made
 * during the rollout with a mirror disabled, standing in for one that failed. V48 then runs while
 * another tenant is still being provisioned by V47's function.
 */
@Testcontainers
@DisplayName("V48 drop common_schema user preference tables migration")
class UserPreferenceCommonTablesDropMigrationIntegrationTest {

    private static final String CONTACT = "919999900001";
    /** Written by a pre-V47 pod during the rollout, never mirrored into the tenant schema. */
    private static final String MISSED_INSERT_CONTACT = "919999900011";
    /** Mirrored into the tenant schema once, then updated by a pre-V47 pod without being mirrored. */
    private static final String MISSED_UPDATE_CONTACT = "919999900012";
    /** Updated by a post-V47 pod without being mirrored back, so its common_schema row is older. */
    private static final String TENANT_NEWER_CONTACT = "919999900013";

    /** Provisioned before V47, with rows V47 copies into its schema. */
    private static final int LIVE_TENANT_ID = 1;
    private static final String LIVE_TENANT = "tenant_aa";
    /** Written to tenant_master_table and provisioned by V47's function in a transaction that
     *  commits only once V48 has started. */
    private static final int IN_FLIGHT_TENANT_ID = 2;
    private static final String IN_FLIGHT_TENANT = "tenant_if";
    /** Provisioned after V48. */
    private static final int NEW_TENANT_ID = 3;
    private static final String NEW_TENANT = "tenant_nw";
    /** No tenant row at all: its rows are dropped. */
    private static final int UNKNOWN_TENANT_ID = 99;
    private static final List<String> TENANT_SCHEMAS = List.of(LIVE_TENANT, IN_FLIGHT_TENANT, NEW_TENANT);
    private static final List<String> PREFERENCE_TABLES = List.of("user_channel_preference", "user_language_preference");

    /** The statements telemetry-service ran against common_schema before V47. */
    private static final String PRE_V47_LANGUAGE_UPSERT = """
            INSERT INTO common_schema.user_language_preference
                (tenant_id, contact_id, language_value, created_at, updated_at)
            VALUES (?, ?, ?, NOW(), NOW())
            ON CONFLICT (tenant_id, contact_id)
            DO UPDATE SET language_value = EXCLUDED.language_value,
                          updated_at = NOW()
            """;
    private static final String PRE_V47_CHANNEL_UPSERT = """
            INSERT INTO common_schema.user_channel_preference
                (tenant_id, contact_id, channel_value, created_at, updated_at)
            VALUES (?, ?, ?, NOW(), NOW())
            ON CONFLICT (tenant_id, contact_id)
            DO UPDATE SET channel_value = EXCLUDED.channel_value,
                          updated_at = NOW()
            """;

    /** The statement telemetry-service runs against the tenant schema from V47 on. */
    private static final String POST_V47_LANGUAGE_UPSERT = """
            INSERT INTO %s.user_language_preference
                (contact_id, language_value, created_at, updated_at)
            VALUES (?, ?, NOW(), NOW())
            ON CONFLICT (contact_id)
            DO UPDATE SET language_value = EXCLUDED.language_value,
                          updated_at = NOW()
            """;

    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(10);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "fsync=off", "-c", "log_min_messages=notice");

    private static JdbcTemplate jdbcTemplate;
    private static boolean v48WaitedForInFlightProvisioning;

    @BeforeAll
    static void seedThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        flyway(MigrationVersion.fromVersion("46")).migrate();
        provisionTenant(LIVE_TENANT_ID, LIVE_TENANT);
        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, CONTACT, "Hindi");
        jdbcTemplate.update(PRE_V47_CHANNEL_UPSERT, LIVE_TENANT_ID, CONTACT, "Iot");
        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, UNKNOWN_TENANT_ID, CONTACT, "Odia");
        flyway(MigrationVersion.fromVersion("47")).migrate();

        withTriggerDisabled("common_schema.user_language_preference",
                "trg_user_language_preference_mirror_to_tenant",
                () -> jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, MISSED_INSERT_CONTACT, "Bengali"));
        withTriggerDisabled("common_schema.user_channel_preference",
                "trg_user_channel_preference_mirror_to_tenant",
                () -> jdbcTemplate.update(PRE_V47_CHANNEL_UPSERT, LIVE_TENANT_ID, MISSED_INSERT_CONTACT, "BFM"));
        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, MISSED_UPDATE_CONTACT, "Hindi");
        withTriggerDisabled("common_schema.user_language_preference",
                "trg_user_language_preference_mirror_to_tenant",
                () -> jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, MISSED_UPDATE_CONTACT, "Marathi"));
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT), TENANT_NEWER_CONTACT, "Hindi");
        withTriggerDisabled(LIVE_TENANT + ".user_language_preference",
                "trg_user_language_preference_mirror_to_common",
                () -> jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT),
                        TENANT_NEWER_CONTACT, "Tamil"));

        CompletableFuture<Void> migration;
        try (Connection provisioning = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = provisioning.createStatement()) {
            // Stands in for createTenant's transaction: its tenant_master_table write, then provisioning.
            provisioning.setAutoCommit(false);
            statement.execute(insertTenantSql(IN_FLIGHT_TENANT_ID, IN_FLIGHT_TENANT));
            statement.execute("SELECT common_schema.create_tenant_schema('" + IN_FLIGHT_TENANT + "')");

            migration = CompletableFuture.runAsync(() -> flyway(MigrationVersion.fromVersion("48")).migrate());
            v48WaitedForInFlightProvisioning = awaitLockWaiterOn("common_schema.tenant_master_table", migration);
            provisioning.commit();
        }
        migration.join();

        provisionTenant(NEW_TENANT_ID, NEW_TENANT);
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

    private static void provisionTenant(int id, String schema) {
        jdbcTemplate.execute(insertTenantSql(id, schema));
        jdbcTemplate.execute("SELECT common_schema.create_tenant_schema('" + schema + "')");
    }

    private static String insertTenantSql(int id, String schema) {
        String stateCode = schema.substring("tenant_".length()).toUpperCase();
        return String.format("""
                INSERT INTO common_schema.tenant_master_table (id, state_code, title, status, lgd_code)
                VALUES (%d, '%s', '%s', 1, %d)
                """, id, stateCode, stateCode, 1000 + id);
    }

    /** Runs the write with the trigger disabled, standing in for a mirror that failed. */
    private static void withTriggerDisabled(String table, String trigger, Runnable write) {
        jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
        try {
            write.run();
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
        }
    }

    /** Whether a session queued for a lock on the table before the migration ended. */
    private static boolean awaitLockWaiterOn(String table, CompletableFuture<Void> migration)
            throws InterruptedException {
        long deadline = System.nanoTime() + LOCK_WAIT_DEADLINE.toNanos();
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

    private static boolean tableExists(String schema, String table) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL",
                Boolean.class, schema + "." + table);
    }

    private static List<String> commonSchemaFunctionsNamed(List<String> names) {
        return jdbcTemplate.queryForList("""
                SELECT p.proname
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'common_schema' AND p.proname = ANY (?)
                """, String.class, (Object) names.toArray(String[]::new));
    }

    private static List<String> triggersOn(String schema, String table) {
        return jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = to_regclass(?) AND NOT tgisinternal
                """, String.class, schema + "." + table);
    }

    private static List<String> languagesOf(String schema, String contactId) {
        return jdbcTemplate.queryForList(String.format(
                "SELECT language_value FROM %s.user_language_preference WHERE contact_id = ?", schema),
                String.class, contactId);
    }

    private static List<String> channelsOf(String schema, String contactId) {
        return jdbcTemplate.queryForList(String.format(
                "SELECT channel_value FROM %s.user_channel_preference WHERE contact_id = ?", schema),
                String.class, contactId);
    }

    @Test
    @DisplayName("Both common_schema tables are dropped")
    void dropsTheCommonSchemaTables() {
        for (String table : PREFERENCE_TABLES) {
            assertThat(tableExists("common_schema", table)).as(table).isFalse();
        }
    }

    @Test
    @DisplayName("The rollout bridge's functions are dropped, and the one that provisions the tables is kept")
    void dropsTheBridgeFunctions() {
        assertThat(commonSchemaFunctionsNamed(List.of(
                "mirror_user_preference_to_tenant",
                "mirror_user_preference_to_common",
                "create_user_preference_common_mirror",
                "create_user_preference_tables")))
                .containsExactly("create_user_preference_tables");
    }

    @Test
    @DisplayName("Rows V47 moved into a tenant schema are kept")
    void keepsTheTenantRows() {
        assertThat(languagesOf(LIVE_TENANT, CONTACT)).containsExactly("Hindi");
        assertThat(channelsOf(LIVE_TENANT, CONTACT)).containsExactly("Iot");
    }

    @Test
    @DisplayName("A write that was never mirrored into the tenant schema is carried over before the drop")
    void carriesOverAnInsertAFailedMirrorMissed() {
        assertThat(languagesOf(LIVE_TENANT, MISSED_INSERT_CONTACT)).containsExactly("Bengali");
        assertThat(channelsOf(LIVE_TENANT, MISSED_INSERT_CONTACT)).containsExactly("BFM");
    }

    @Test
    @DisplayName("An update that was never mirrored into the tenant schema overwrites the older tenant row")
    void carriesOverAnUpdateAFailedMirrorMissed() {
        assertThat(languagesOf(LIVE_TENANT, MISSED_UPDATE_CONTACT)).containsExactly("Marathi");
    }

    @Test
    @DisplayName("A tenant row newer than its common_schema row is kept")
    void keepsATenantRowNewerThanTheCommonRow() {
        assertThat(languagesOf(LIVE_TENANT, TENANT_NEWER_CONTACT)).containsExactly("Tamil");
    }

    @Test
    @DisplayName("Only the rows carried over are counted, and the rows with no live tenant are counted as dropped")
    void countsTheRowsCarriedOverAndDropped() {
        assertThat(postgres.getLogs())
                .contains("V48: carried 2 row(s) of common_schema.user_language_preference over to the tenant schemas, dropping 1")
                .contains("V48: carried 1 row(s) of common_schema.user_channel_preference over to the tenant schemas, dropping 0");
    }

    @Test
    @DisplayName("No tenant table keeps a mirror trigger, and writes to it succeed")
    void leavesNoMirrorTriggerOnAnyTenantTable() {
        for (String schema : TENANT_SCHEMAS) {
            for (String table : PREFERENCE_TABLES) {
                assertThat(triggersOn(schema, table)).as(schema + "." + table).isEmpty();
            }
        }

        String contact = "919999900002";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT), contact, "Marathi");
        assertThat(languagesOf(LIVE_TENANT, contact)).containsExactly("Marathi");
    }

    @Test
    @DisplayName("A tenant still being provisioned by V47's function when V48 starts is waited for, then unbridged")
    void waitsForATenantProvisionedWhileV48Starts() {
        assertThat(v48WaitedForInFlightProvisioning).as("V48 waited for the open provisioning").isTrue();
        for (String table : PREFERENCE_TABLES) {
            assertThat(tableExists(IN_FLIGHT_TENANT, table)).as(table).isTrue();
            assertThat(triggersOn(IN_FLIGHT_TENANT, table)).as(table).isEmpty();
        }

        String contact = "919999900003";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, IN_FLIGHT_TENANT), contact, "Assamese");
        assertThat(languagesOf(IN_FLIGHT_TENANT, contact)).containsExactly("Assamese");
    }

    @Test
    @DisplayName("A tenant provisioned after V48 still gets both tables")
    void provisionsNewTenantsWithTheTables() {
        for (String table : PREFERENCE_TABLES) {
            assertThat(tableExists(NEW_TENANT, table)).as(table).isTrue();
        }

        String contact = "919999900004";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, NEW_TENANT), contact, "Tamil");
        assertThat(languagesOf(NEW_TENANT, contact)).containsExactly("Tamil");
    }
}
