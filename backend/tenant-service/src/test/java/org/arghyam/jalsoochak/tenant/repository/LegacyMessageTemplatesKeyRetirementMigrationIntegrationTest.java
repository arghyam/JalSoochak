package org.arghyam.jalsoochak.tenant.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the V47 migration, which leaves no live row under the retired
 * {@code GLIFIC_MESSAGE_TEMPLATES} tenant config key, against real PostgreSQL via Testcontainers.
 *
 * <p>The SQL under test is the <b>real V47 migration</b>, read from {@code classpath:db/migration/}
 * (tenant-service's pom copies {@code ../database/V*.sql} there). It runs once, on a table that
 * already holds every kind of row it has to handle. No Spring context is needed, because the
 * migration is the only code under test.
 */
@Testcontainers
@DisplayName("V47 legacy message-templates config key retirement migration")
class LegacyMessageTemplatesKeyRetirementMigrationIntegrationTest {

    private static final String LEGACY_KEY = "GLIFIC_MESSAGE_TEMPLATES";
    private static final String CANONICAL_KEY = "WHATSAPP_MESSAGE_TEMPLATES";

    /** A live legacy row only — written by an old instance after V45 — plus a soft-deleted one. */
    private static final int TENANT_ONLY_LEGACY = 101;
    /** A live row under both names, as V45's copy left them — the legacy one was being ignored. */
    private static final int TENANT_BOTH_NAMES = 102;
    /** Only a soft-deleted legacy row. */
    private static final int TENANT_ONLY_DELETED = 103;
    /** Only a live canonical row, as a tenant first configured after V45 has. */
    private static final int TENANT_CANONICAL_ONLY = 104;
    /** No templates at all; an unrelated key only. */
    private static final int TENANT_OTHER_KEY = 105;

    private static final String LIVE_UPDATED_AT = "2026-01-02 03:04:05";
    private static final String DELETED_AT = "2025-12-01 00:00:00";

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

        insert(TENANT_ONLY_LEGACY, LEGACY_KEY, "{\"version\":2}", LIVE_UPDATED_AT, null);
        insert(TENANT_ONLY_LEGACY, LEGACY_KEY, "{\"version\":1}", "2025-06-01 00:00:00", DELETED_AT);
        insert(TENANT_BOTH_NAMES, CANONICAL_KEY, "{\"version\":7}", LIVE_UPDATED_AT, null);
        insert(TENANT_BOTH_NAMES, LEGACY_KEY, "{\"version\":6}", LIVE_UPDATED_AT, null);
        insert(TENANT_ONLY_DELETED, LEGACY_KEY, "{\"version\":1}", "2025-06-01 00:00:00", DELETED_AT);
        insert(TENANT_CANONICAL_ONLY, CANONICAL_KEY, "{\"version\":4}", LIVE_UPDATED_AT, null);
        insert(TENANT_OTHER_KEY, "WATER_NORM", "{\"value\":\"55\"}", LIVE_UPDATED_AT, null);

        String migration = new String(
                new ClassPathResource("db/migration/V47__retire_legacy_message_templates_config_key.sql")
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

    private static List<Map<String, Object>> deletedRows(int tenantId, String key) {
        return jdbcTemplate.queryForList("""
                SELECT config_value,
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                       to_char(deleted_at, 'YYYY-MM-DD HH24:MI:SS') AS deleted_at,
                       updated_at = deleted_at AS stamped_together,
                       deleted_by
                FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NOT NULL
                """, tenantId, key);
    }

    private static String liveUpdatedAt(int tenantId, String key) {
        return jdbcTemplate.queryForObject("""
                SELECT to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS')
                FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NULL
                """, String.class, tenantId, key);
    }

    @Test
    @DisplayName("No live row is left under the legacy key")
    void leavesNoLiveLegacyRow() {
        Integer live = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM common_schema.tenant_config_master_table
                WHERE config_key = ? AND deleted_at IS NULL
                """, Integer.class, LEGACY_KEY);

        assertThat(live).isZero();
    }

    @Test
    @DisplayName("A tenant's only live legacy row is renamed to the canonical key, keeping its updated_at")
    void renamesAnOnlyLiveLegacyRow() {
        assertThat(liveValues(TENANT_ONLY_LEGACY, CANONICAL_KEY)).containsExactly("{\"version\":2}");
        assertThat(liveUpdatedAt(TENANT_ONLY_LEGACY, CANONICAL_KEY)).isEqualTo(LIVE_UPDATED_AT);
    }

    @Test
    @DisplayName("A legacy row beside a live canonical row is soft-deleted, and the canonical row is kept")
    void softDeletesAShadowedLegacyRow() {
        assertThat(liveValues(TENANT_BOTH_NAMES, CANONICAL_KEY)).containsExactly("{\"version\":7}");
        assertThat(liveUpdatedAt(TENANT_BOTH_NAMES, CANONICAL_KEY)).isEqualTo(LIVE_UPDATED_AT);

        List<Map<String, Object>> deleted = deletedRows(TENANT_BOTH_NAMES, LEGACY_KEY);
        assertThat(deleted).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("config_value", "{\"version\":6}");
            assertThat(row).containsEntry("stamped_together", true);
            assertThat(row.get("updated_at")).isNotEqualTo(LIVE_UPDATED_AT);
            assertThat(row.get("deleted_by")).isNull();
        });
    }

    @Test
    @DisplayName("Rows already soft-deleted under the legacy key are left as they are")
    void leavesSoftDeletedLegacyRowsAlone() {
        assertThat(deletedRows(TENANT_ONLY_LEGACY, LEGACY_KEY)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("config_value", "{\"version\":1}")
                        .containsEntry("deleted_at", DELETED_AT));
        assertThat(deletedRows(TENANT_ONLY_DELETED, LEGACY_KEY)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("deleted_at", DELETED_AT));
        assertThat(liveValues(TENANT_ONLY_DELETED, CANONICAL_KEY)).isEmpty();
    }

    @Test
    @DisplayName("Canonical rows and rows under other keys are untouched")
    void leavesOtherRowsAlone() {
        assertThat(liveValues(TENANT_CANONICAL_ONLY, CANONICAL_KEY)).containsExactly("{\"version\":4}");
        assertThat(liveUpdatedAt(TENANT_CANONICAL_ONLY, CANONICAL_KEY)).isEqualTo(LIVE_UPDATED_AT);
        assertThat(liveValues(TENANT_OTHER_KEY, "WATER_NORM")).containsExactly("{\"value\":\"55\"}");
        assertThat(liveValues(TENANT_OTHER_KEY, CANONICAL_KEY)).isEmpty();
    }
}
