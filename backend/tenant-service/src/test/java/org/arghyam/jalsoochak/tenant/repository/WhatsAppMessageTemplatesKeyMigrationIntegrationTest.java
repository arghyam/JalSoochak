package org.arghyam.jalsoochak.tenant.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the V45 migration, which renames the {@code GLIFIC_MESSAGE_TEMPLATES} tenant
 * config key to {@code WHATSAPP_MESSAGE_TEMPLATES}, against real PostgreSQL via Testcontainers.
 *
 * <p>The SQL under test is the <b>real V45 migration</b>, read from {@code classpath:db/migration/}
 * (tenant-service's pom copies {@code ../database/V*.sql} there). It runs once, on a table that
 * already holds every kind of row it has to handle. No Spring context is needed, because the
 * migration is the only code under test.
 */
@Testcontainers
@DisplayName("V45 WhatsApp message-templates config key migration")
class WhatsAppMessageTemplatesKeyMigrationIntegrationTest {

    private static final String LEGACY_KEY = "GLIFIC_MESSAGE_TEMPLATES";
    private static final String CANONICAL_KEY = "WHATSAPP_MESSAGE_TEMPLATES";

    /** A live legacy row, plus a soft-deleted legacy row from an earlier value. */
    private static final int TENANT_LIVE_AND_DELETED = 101;
    /** A live row under both names — the case the migration must not fail on. */
    private static final int TENANT_BOTH_NAMES = 102;
    /** Only a soft-deleted legacy row. */
    private static final int TENANT_ONLY_DELETED = 103;
    /** No templates at all; an unrelated key only. */
    private static final int TENANT_OTHER_KEY = 104;

    private static final String LIVE_UPDATED_AT = "2026-01-02 03:04:05";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/tenant-common-test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void seedThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        insert(TENANT_LIVE_AND_DELETED, LEGACY_KEY, "{\"version\":2}", LIVE_UPDATED_AT, null);
        insert(TENANT_LIVE_AND_DELETED, LEGACY_KEY, "{\"version\":1}", "2025-06-01 00:00:00",
                "2025-12-01 00:00:00");
        insert(TENANT_BOTH_NAMES, CANONICAL_KEY, "{\"version\":7}", LIVE_UPDATED_AT, null);
        insert(TENANT_BOTH_NAMES, LEGACY_KEY, "{\"version\":6}", LIVE_UPDATED_AT, null);
        insert(TENANT_ONLY_DELETED, LEGACY_KEY, "{\"version\":1}", "2025-06-01 00:00:00",
                "2025-12-01 00:00:00");
        insert(TENANT_OTHER_KEY, "WATER_NORM", "{\"value\":\"55\"}", LIVE_UPDATED_AT, null);

        String migration = new String(
                new ClassPathResource("db/migration/V45__rename_whatsapp_message_templates_config_key.sql")
                        .getInputStream().readAllBytes(), UTF_8);
        jdbcTemplate.execute(migration);
    }

    private static void insert(int tenantId, String key, String value, String updatedAt, String deletedAt) {
        jdbcTemplate.update("""
                INSERT INTO common_schema.tenant_config_master_table
                    (tenant_id, config_key, config_value, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?::timestamp, ?::timestamp, ?::timestamp)
                """, tenantId, key, value, updatedAt, updatedAt, deletedAt);
    }

    private static List<String> liveValues(int tenantId, String key) {
        return jdbcTemplate.queryForList("""
                SELECT config_value FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NULL
                """, String.class, tenantId, key);
    }

    private static List<String> deletedValues(int tenantId, String key) {
        return jdbcTemplate.queryForList("""
                SELECT config_value FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NOT NULL
                """, String.class, tenantId, key);
    }

    @Test
    @DisplayName("A live legacy row becomes exactly one live row under the canonical key")
    void renamesTheLiveLegacyRow() {
        assertThat(liveValues(TENANT_LIVE_AND_DELETED, CANONICAL_KEY)).containsExactly("{\"version\":2}");
        assertThat(liveValues(TENANT_LIVE_AND_DELETED, LEGACY_KEY)).isEmpty();
    }

    @Test
    @DisplayName("The renamed row keeps its updated_at, because its value has not changed")
    void keepsUpdatedAtOfTheRenamedRow() {
        String updatedAt = jdbcTemplate.queryForObject("""
                SELECT to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS')
                FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NULL
                """, String.class, TENANT_LIVE_AND_DELETED, CANONICAL_KEY);

        assertThat(updatedAt).isEqualTo(LIVE_UPDATED_AT);
    }

    @Test
    @DisplayName("Soft-deleted legacy rows keep the legacy name")
    void leavesSoftDeletedRowsAlone() {
        assertThat(deletedValues(TENANT_LIVE_AND_DELETED, LEGACY_KEY)).containsExactly("{\"version\":1}");
        assertThat(deletedValues(TENANT_ONLY_DELETED, LEGACY_KEY)).containsExactly("{\"version\":1}");
        assertThat(liveValues(TENANT_ONLY_DELETED, CANONICAL_KEY)).isEmpty();
    }

    @Test
    @DisplayName("A tenant already holding the canonical key keeps it, and its legacy row is not renamed")
    void keepsAnExistingCanonicalRow() {
        assertThat(liveValues(TENANT_BOTH_NAMES, CANONICAL_KEY)).containsExactly("{\"version\":7}");
        assertThat(liveValues(TENANT_BOTH_NAMES, LEGACY_KEY)).containsExactly("{\"version\":6}");
    }

    @Test
    @DisplayName("Rows under other keys are untouched")
    void leavesOtherKeysAlone() {
        assertThat(liveValues(TENANT_OTHER_KEY, "WATER_NORM")).containsExactly("{\"value\":\"55\"}");
        assertThat(liveValues(TENANT_OTHER_KEY, CANONICAL_KEY)).isEmpty();
    }

    @Test
    @DisplayName("The partial unique index still allows one live row per tenant and key")
    void partialUniqueIndexIntact() {
        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'common_schema' AND indexname = 'uq_tenant_config_key'
                """, String.class);
        assertThat(indexDef).contains("UNIQUE").contains("WHERE (deleted_at IS NULL)");

        assertThatThrownBy(() -> insert(TENANT_LIVE_AND_DELETED, CANONICAL_KEY, "{\"version\":3}",
                LIVE_UPDATED_AT, null))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
