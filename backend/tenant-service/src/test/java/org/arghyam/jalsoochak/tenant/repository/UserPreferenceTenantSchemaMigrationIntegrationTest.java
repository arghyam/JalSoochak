package org.arghyam.jalsoochak.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the V47 migration, which moves {@code user_channel_preference} and
 * {@code user_language_preference} from {@code common_schema} into every tenant schema, against real
 * PostgreSQL via Testcontainers.
 *
 * <p>Flyway runs the <b>real migrations</b> from {@code classpath:db/migration/} up to V46, the
 * common_schema tables are seeded with rows for live, soft-deleted, unprovisioned and unknown
 * tenants, and V47 runs while another tenant is still being provisioned by the unpatched function.
 * Flyway stops at V47, so the triggers that bridge the rollout until V48, in both directions, are
 * still there to test.
 */
@Testcontainers
@DisplayName("V47 user preference tables to tenant schemas migration")
class UserPreferenceTenantSchemaMigrationIntegrationTest {

    private static final String RAW_CONTACT = "+91 99999-00001";
    private static final String CONTACT = "919999900001";

    private static final int LIVE_TENANT_ID = 1;
    private static final String LIVE_TENANT = "tenant_aa";
    /** A second live tenant with a row for {@link #CONTACT}, which must stay in its own schema. */
    private static final int SECOND_TENANT_ID = 2;
    private static final String SECOND_TENANT = "tenant_ab";
    /** Soft-deleted, though its schema was provisioned: its rows are skipped. */
    private static final int DELETED_TENANT_ID = 3;
    private static final String DELETED_TENANT = "tenant_de";
    /** Live, but its schema was never provisioned: its rows are skipped. */
    private static final int UNPROVISIONED_TENANT_ID = 4;
    /** No tenant row at all: its rows are skipped. */
    private static final int UNKNOWN_TENANT_ID = 99;
    /** Written to tenant_master_table and provisioned by the unpatched function in a transaction
     *  that commits only once V47 has started. */
    private static final int IN_FLIGHT_TENANT_ID = 5;
    private static final String IN_FLIGHT_TENANT = "tenant_if";
    /** Provisioned before V47; its channel table loses its unique key to make the mirror fail. */
    private static final int BROKEN_MIRROR_TENANT_ID = 6;
    private static final String BROKEN_MIRROR_TENANT = "tenant_bm";
    /** Provisioned after V47, through the patched function chain. */
    private static final int NEW_TENANT_ID = 7;
    private static final String NEW_TENANT = "tenant_nw";
    /** Provisioned after V47, then soft-deleted: writes to its schema have no tenant to mirror back to. */
    private static final int RETIRED_TENANT_ID = 8;
    private static final String RETIRED_TENANT = "tenant_rt";
    private static final List<String> TENANT_SCHEMAS =
            List.of(LIVE_TENANT, SECOND_TENANT, DELETED_TENANT, IN_FLIGHT_TENANT, NEW_TENANT);

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

    /** The statements telemetry-service runs against the tenant schema from V47 on. */
    private static final String POST_V47_LANGUAGE_UPSERT = """
            INSERT INTO %s.user_language_preference
                (contact_id, language_value, created_at, updated_at)
            VALUES (?, ?, NOW(), NOW())
            ON CONFLICT (contact_id)
            DO UPDATE SET language_value = EXCLUDED.language_value,
                          updated_at = NOW()
            """;
    private static final String POST_V47_CHANNEL_UPSERT = """
            INSERT INTO %s.user_channel_preference
                (contact_id, channel_value, created_at, updated_at)
            VALUES (?, ?, NOW(), NOW())
            ON CONFLICT (contact_id)
            DO UPDATE SET channel_value = EXCLUDED.channel_value,
                          updated_at = NOW()
            """;

    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(10);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "fsync=off", "-c", "log_min_messages=notice");

    private static JdbcTemplate jdbcTemplate;
    private static boolean v47WaitedForInFlightProvisioning;

    @BeforeAll
    static void seedThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        flyway(MigrationVersion.fromVersion("46")).migrate();
        provisionTenant(LIVE_TENANT_ID, LIVE_TENANT);
        provisionTenant(SECOND_TENANT_ID, SECOND_TENANT);
        provisionTenant(DELETED_TENANT_ID, DELETED_TENANT);
        provisionTenant(BROKEN_MIRROR_TENANT_ID, BROKEN_MIRROR_TENANT);
        jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                DELETED_TENANT_ID);
        insertTenant(UNPROVISIONED_TENANT_ID, "tenant_up");

        // The same contact twice for one tenant, once in a pre-V33 unnormalised form: the later wins.
        seedLanguage(LIVE_TENANT_ID, RAW_CONTACT, "Hindi", "2026-01-01");
        seedLanguage(LIVE_TENANT_ID, CONTACT, "Bengali", "2026-02-01");
        seedLanguage(SECOND_TENANT_ID, CONTACT, "Tamil", "2026-01-01");
        seedLanguage(DELETED_TENANT_ID, CONTACT, "Odia", "2026-01-01");
        seedLanguage(UNPROVISIONED_TENANT_ID, CONTACT, "Odia", "2026-01-01");
        seedLanguage(UNKNOWN_TENANT_ID, CONTACT, "Odia", "2026-01-01");
        seedLanguage(IN_FLIGHT_TENANT_ID, CONTACT, "Assamese", "2026-01-01");
        seedChannel(LIVE_TENANT_ID, CONTACT, "Iot", "2026-01-01");
        seedChannel(DELETED_TENANT_ID, CONTACT, "BFM", "2026-01-01");

        CompletableFuture<Void> migration;
        try (Connection provisioning = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = provisioning.createStatement()) {
            // Stands in for createTenant's transaction: its tenant_master_table write, then provisioning.
            provisioning.setAutoCommit(false);
            statement.execute(insertTenantSql(IN_FLIGHT_TENANT_ID, IN_FLIGHT_TENANT));
            statement.execute("SELECT common_schema.create_tenant_schema('" + IN_FLIGHT_TENANT + "')");

            migration = CompletableFuture.runAsync(() -> flyway(MigrationVersion.fromVersion("47")).migrate());
            v47WaitedForInFlightProvisioning = awaitLockWaiterOn("common_schema.tenant_master_table", migration);
            provisioning.commit();
        }
        migration.join();

        provisionTenant(NEW_TENANT_ID, NEW_TENANT);
        provisionTenant(RETIRED_TENANT_ID, RETIRED_TENANT);
        jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                RETIRED_TENANT_ID);
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
        insertTenant(id, schema);
        jdbcTemplate.execute("SELECT common_schema.create_tenant_schema('" + schema + "')");
    }

    private static void insertTenant(int id, String schema) {
        jdbcTemplate.execute(insertTenantSql(id, schema));
    }

    private static String insertTenantSql(int id, String schema) {
        String stateCode = schema.substring("tenant_".length()).toUpperCase();
        return String.format("""
                INSERT INTO common_schema.tenant_master_table (id, state_code, title, status, lgd_code)
                VALUES (%d, '%s', '%s', 1, %d)
                """, id, stateCode, stateCode, 1000 + id);
    }

    private static void seedLanguage(int tenantId, String contactId, String language, String updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO common_schema.user_language_preference
                    (tenant_id, contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, ?, ?::timestamp, ?::timestamp)
                """, tenantId, contactId, language, updatedAt, updatedAt);
    }

    private static void seedChannel(int tenantId, String contactId, String channel, String updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO common_schema.user_channel_preference
                    (tenant_id, contact_id, channel_value, created_at, updated_at)
                VALUES (?, ?, ?, ?::timestamp, ?::timestamp)
                """, tenantId, contactId, channel, updatedAt, updatedAt);
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

    private static List<String> columnsOf(String schema, String table) {
        return jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """, String.class, schema, table);
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

    private static List<String> commonLanguagesOf(int tenantId, String contactId) {
        return jdbcTemplate.queryForList(
                "SELECT language_value FROM common_schema.user_language_preference WHERE tenant_id = ? AND contact_id = ?",
                String.class, tenantId, contactId);
    }

    private static List<String> commonChannelsOf(int tenantId, String contactId) {
        return jdbcTemplate.queryForList(
                "SELECT channel_value FROM common_schema.user_channel_preference WHERE tenant_id = ? AND contact_id = ?",
                String.class, tenantId, contactId);
    }

    private static List<String> triggersOn(String schema, String table) {
        return jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = to_regclass(?) AND NOT tgisinternal
                ORDER BY tgname
                """, String.class, schema + "." + table);
    }

    private record TupleCounts(long inserted, long updated) {
    }

    /**
     * The rows one statement inserts and updates in each table, the writes of the triggers it fires
     * included. The statement runs in its own transaction, which is rolled back.
     */
    private static Map<String, TupleCounts> tupleCountsOf(List<String> tables, String sql, Object... args)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                    }
                    statement.executeUpdate();
                }
                Map<String, TupleCounts> counts = new LinkedHashMap<>();
                try (PreparedStatement stats = connection.prepareStatement("""
                        SELECT n_tup_ins, n_tup_upd FROM pg_stat_xact_user_tables
                        WHERE schemaname || '.' || relname = ?
                        """)) {
                    for (String table : tables) {
                        stats.setString(1, table);
                        try (ResultSet row = stats.executeQuery()) {
                            row.next();
                            counts.put(table, new TupleCounts(row.getLong("n_tup_ins"), row.getLong("n_tup_upd")));
                        }
                    }
                }
                return counts;
            } finally {
                connection.rollback();
            }
        }
    }

    private static Integer rowCount(String schema, String table) {
        return jdbcTemplate.queryForObject(
                String.format("SELECT count(*) FROM %s.%s", schema, table), Integer.class);
    }

    @Test
    @DisplayName("Every tenant schema gets both tables, keyed by contact alone, with no tenant_id")
    void createsBothTablesInEveryTenantSchema() {
        for (String schema : TENANT_SCHEMAS) {
            assertThat(columnsOf(schema, "user_language_preference")).as(schema)
                    .containsExactly("id", "contact_id", "language_value", "created_at", "updated_at");
            assertThat(columnsOf(schema, "user_channel_preference")).as(schema)
                    .containsExactly("id", "contact_id", "channel_value", "created_at", "updated_at");
        }
    }

    @Test
    @DisplayName("A tenant provisioned after V47 gets tables that reject a second row for a contact")
    void provisionsNewTenantsWithUniqueContactIds() {
        String insert = "INSERT INTO " + NEW_TENANT + ".user_channel_preference (contact_id, channel_value) VALUES (?, ?)";
        jdbcTemplate.update(insert, CONTACT, "Iot");

        assertThatThrownBy(() -> jdbcTemplate.update(insert, CONTACT, "BFM"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("Rows are copied into their tenant's schema with the contact id reduced to digits")
    void copiesRowsIntoTheirTenantSchema() {
        assertThat(languagesOf(SECOND_TENANT, CONTACT)).containsExactly("Tamil");
        assertThat(channelsOf(LIVE_TENANT, CONTACT)).containsExactly("Iot");
    }

    @Test
    @DisplayName("Rows of one tenant that reduce to the same contact are folded, the latest winning")
    void foldsRowsOfTheSameContactKeepingTheLatest() {
        assertThat(languagesOf(LIVE_TENANT, CONTACT)).containsExactly("Bengali");
        assertThat(languagesOf(LIVE_TENANT, RAW_CONTACT)).isEmpty();
    }

    @Test
    @DisplayName("Rows of a soft-deleted, unprovisioned or unknown tenant are skipped and counted")
    void skipsRowsWithNoLiveProvisionedTenant() {
        assertThat(rowCount(DELETED_TENANT, "user_language_preference")).isZero();
        assertThat(rowCount(DELETED_TENANT, "user_channel_preference")).isZero();
        assertThat(postgres.getLogs())
                .contains("V47: copied 4 of 7 row(s) from common_schema.user_language_preference, skipped 3")
                .contains("V47: copied 1 of 2 row(s) from common_schema.user_channel_preference, skipped 1");
    }

    @Test
    @DisplayName("A tenant still being provisioned when V47 starts is waited for, then given its tables and rows")
    void waitsForATenantProvisionedWhileV47Starts() {
        assertThat(v47WaitedForInFlightProvisioning).as("V47 waited for the open provisioning").isTrue();
        assertThat(languagesOf(IN_FLIGHT_TENANT, CONTACT)).containsExactly("Assamese");
    }

    @Test
    @DisplayName("A pre-V47 language upsert is mirrored into the tenant schema")
    void mirrorsALanguageWriteFromPreV47Code() {
        String contact = "919999900011";
        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, contact, "Hindi");
        assertThat(languagesOf(LIVE_TENANT, contact)).containsExactly("Hindi");

        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, contact, "Marathi");
        assertThat(languagesOf(LIVE_TENANT, contact)).containsExactly("Marathi");
    }

    @Test
    @DisplayName("A pre-V47 channel upsert is mirrored into the tenant schema")
    void mirrorsAChannelWriteFromPreV47Code() {
        String contact = "919999900012";
        jdbcTemplate.update(PRE_V47_CHANNEL_UPSERT, SECOND_TENANT_ID, contact, "BFM");
        jdbcTemplate.update(PRE_V47_CHANNEL_UPSERT, SECOND_TENANT_ID, contact, "Iot");

        assertThat(channelsOf(SECOND_TENANT, contact)).containsExactly("Iot");
    }

    @Test
    @DisplayName("A mirrored write older than the tenant row does not overwrite it")
    void doesNotMirrorAWriteOlderThanTheTenantRow() {
        String contact = "919999900013";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT), contact, "Hindi");

        // An upsert, as the tenant write above was already mirrored back into common_schema.
        jdbcTemplate.update("""
                INSERT INTO common_schema.user_language_preference
                    (tenant_id, contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, ?, '2020-01-01', '2020-01-01')
                ON CONFLICT (tenant_id, contact_id)
                DO UPDATE SET language_value = EXCLUDED.language_value,
                              updated_at = EXCLUDED.updated_at
                """, LIVE_TENANT_ID, contact, "Tamil");

        assertThat(languagesOf(LIVE_TENANT, contact)).containsExactly("Hindi");
    }

    @Test
    @DisplayName("A write for a tenant with no live provisioned schema still succeeds and is not mirrored")
    void keepsWritesWithNoTenantToMirrorInto() {
        String contact = "919999900014";
        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, UNKNOWN_TENANT_ID, contact, "Hindi");
        jdbcTemplate.update(PRE_V47_LANGUAGE_UPSERT, DELETED_TENANT_ID, contact, "Hindi");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM common_schema.user_language_preference WHERE contact_id = ?",
                Integer.class, contact)).isEqualTo(2);
        assertThat(languagesOf(DELETED_TENANT, contact)).isEmpty();
    }

    @Test
    @DisplayName("A mirror that fails logs a warning instead of failing the write it mirrors")
    void neverFailsTheWriteItMirrors() {
        String contact = "919999900015";
        jdbcTemplate.execute("ALTER TABLE " + BROKEN_MIRROR_TENANT
                + ".user_channel_preference DROP CONSTRAINT uq_user_channel_pref_contact");

        jdbcTemplate.update(PRE_V47_CHANNEL_UPSERT, BROKEN_MIRROR_TENANT_ID, contact, "Iot");

        assertThat(jdbcTemplate.queryForList(
                "SELECT channel_value FROM common_schema.user_channel_preference WHERE tenant_id = ? AND contact_id = ?",
                String.class, BROKEN_MIRROR_TENANT_ID, contact)).containsExactly("Iot");
        assertThat(channelsOf(BROKEN_MIRROR_TENANT, contact)).isEmpty();
        assertThat(postgres.getLogs())
                .contains("V47: could not mirror a write on common_schema.user_channel_preference for tenant "
                        + BROKEN_MIRROR_TENANT_ID);
    }

    @Test
    @DisplayName("Every tenant schema, including one provisioned after V47, mirrors its writes back to common_schema")
    void createsTheMirrorBackInEveryTenantSchema() {
        for (String schema : TENANT_SCHEMAS) {
            assertThat(triggersOn(schema, "user_language_preference")).as(schema)
                    .containsExactly("trg_user_language_preference_mirror_to_common");
            assertThat(triggersOn(schema, "user_channel_preference")).as(schema)
                    .containsExactly("trg_user_channel_preference_mirror_to_common");
        }
    }

    @Test
    @DisplayName("A post-V47 language upsert is mirrored back to common_schema for pre-V47 pods")
    void mirrorsALanguageWriteFromPostV47CodeBack() {
        String contact = "919999900021";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT), contact, "Hindi");
        assertThat(commonLanguagesOf(LIVE_TENANT_ID, contact)).containsExactly("Hindi");

        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT), contact, "Marathi");
        assertThat(commonLanguagesOf(LIVE_TENANT_ID, contact)).containsExactly("Marathi");
    }

    @Test
    @DisplayName("A post-V47 channel upsert is mirrored back to common_schema for pre-V47 pods")
    void mirrorsAChannelWriteFromPostV47CodeBack() {
        String contact = "919999900022";
        jdbcTemplate.update(String.format(POST_V47_CHANNEL_UPSERT, SECOND_TENANT), contact, "BFM");
        jdbcTemplate.update(String.format(POST_V47_CHANNEL_UPSERT, SECOND_TENANT), contact, "Iot");

        assertThat(commonChannelsOf(SECOND_TENANT_ID, contact)).containsExactly("Iot");
    }

    @Test
    @DisplayName("A tenant provisioned after V47 mirrors its writes back as well")
    void mirrorsBackTheWritesOfATenantProvisionedAfterV47() {
        String contact = "919999900023";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, NEW_TENANT), contact, "Hindi");

        assertThat(commonLanguagesOf(NEW_TENANT_ID, contact)).containsExactly("Hindi");
    }

    @Test
    @DisplayName("A tenant write older than the common_schema row does not overwrite it")
    void doesNotMirrorBackAWriteOlderThanTheCommonRow() {
        String contact = "919999900024";
        seedLanguage(LIVE_TENANT_ID, contact, "Tamil", "2026-06-01");

        jdbcTemplate.update(String.format("""
                INSERT INTO %s.user_language_preference (contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, '2020-01-01', '2020-01-01')
                ON CONFLICT (contact_id)
                DO UPDATE SET language_value = EXCLUDED.language_value,
                              updated_at = EXCLUDED.updated_at
                """, LIVE_TENANT), contact, "Hindi");

        assertThat(commonLanguagesOf(LIVE_TENANT_ID, contact)).containsExactly("Tamil");
    }

    @Test
    @DisplayName("A write to the schema of a soft-deleted tenant still succeeds and is not mirrored back")
    void keepsTenantWritesWithNoLiveTenantToMirrorInto() {
        String contact = "919999900025";
        jdbcTemplate.update(String.format(POST_V47_LANGUAGE_UPSERT, RETIRED_TENANT), contact, "Hindi");

        assertThat(languagesOf(RETIRED_TENANT, contact)).containsExactly("Hindi");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM common_schema.user_language_preference WHERE contact_id = ?",
                Integer.class, contact)).isZero();
    }

    @Test
    @DisplayName("A mirrored write is not mirrored back to the table it came from")
    void doesNotMirrorAMirroredWriteBack() throws SQLException {
        String tenantTable = LIVE_TENANT + ".user_language_preference";
        String commonTable = "common_schema.user_language_preference";
        TupleCounts insertedOnce = new TupleCounts(1, 0);

        // Bounced back, either write would also update the row its own statement inserted.
        assertThat(tupleCountsOf(List.of(tenantTable, commonTable),
                String.format(POST_V47_LANGUAGE_UPSERT, LIVE_TENANT), "919999900026", "Hindi"))
                .containsEntry(tenantTable, insertedOnce)
                .containsEntry(commonTable, insertedOnce);
        assertThat(tupleCountsOf(List.of(tenantTable, commonTable),
                PRE_V47_LANGUAGE_UPSERT, LIVE_TENANT_ID, "919999900027", "Hindi"))
                .containsEntry(commonTable, insertedOnce)
                .containsEntry(tenantTable, insertedOnce);
    }

    @Test
    @DisplayName("A mirror back that fails logs a warning instead of failing the tenant write")
    void neverFailsTheTenantWriteItMirrorsBack() {
        String contact = "919999900028";
        jdbcTemplate.execute("ALTER TABLE common_schema.user_channel_preference"
                + " ADD CONSTRAINT reject_test_contact CHECK (contact_id <> '" + contact + "')");
        try {
            jdbcTemplate.update(String.format(POST_V47_CHANNEL_UPSERT, SECOND_TENANT), contact, "Iot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE common_schema.user_channel_preference DROP CONSTRAINT reject_test_contact");
        }

        assertThat(channelsOf(SECOND_TENANT, contact)).containsExactly("Iot");
        assertThat(commonChannelsOf(SECOND_TENANT_ID, contact)).isEmpty();
        assertThat(postgres.getLogs())
                .contains("V47: could not mirror a write on " + SECOND_TENANT
                        + ".user_channel_preference back to common_schema");
    }
}
