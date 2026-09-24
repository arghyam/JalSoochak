package org.arghyam.jalsoochak.tenant.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * Integration tests for the V48 migration, which retires the {@code glific_welcome_flow_id} tenant
 * config key, against real PostgreSQL via Testcontainers.
 *
 * <p>The SQL under test is the <b>real V48 migration</b>, read from {@code classpath:db/migration/}
 * (tenant-service's pom copies {@code ../database/V*.sql} there). It runs once, on a table that
 * already holds every kind of row it has to handle. No Spring context is needed, because the
 * migration is the only code under test.
 *
 * <p>message-service's read ignores {@code deleted_at} and takes the newest row by
 * {@code updated_at}, then {@code id}; {@link #read} repeats it, so the tests can show that every
 * tenant's welcome flow id is the same before and after the migration.
 */
@Testcontainers
@DisplayName("V48 legacy welcome flow id config key retirement migration")
class LegacyWelcomeFlowIdKeyRetirementMigrationIntegrationTest {

    private static final String LEGACY_KEY = "glific_welcome_flow_id";
    private static final String CANONICAL_KEY = "welcome_flow_id";

    /** A live legacy row plus an older soft-deleted one; no canonical row. */
    private static final int TENANT_ONLY_LEGACY = 201;
    /** No canonical row, and the newest legacy row is soft-deleted, so it is the one read. */
    private static final int TENANT_NEWEST_LEGACY_DELETED = 202;
    /** A live row under both names, plus a soft-deleted legacy row. */
    private static final int TENANT_BOTH_NAMES = 203;
    /** Only a soft-deleted canonical row, which the read still returns, beside a live legacy row. */
    private static final int TENANT_DELETED_CANONICAL = 204;
    /** A blank canonical row, which shadowed the legacy row and read as no flow id. */
    private static final int TENANT_BLANK_CANONICAL = 205;
    /** Only a live canonical row. */
    private static final int TENANT_CANONICAL_ONLY = 206;
    /** No welcome flow id at all; an unrelated key only. */
    private static final int TENANT_OTHER_KEY = 207;

    private static final List<Integer> TENANTS = List.of(
            TENANT_ONLY_LEGACY, TENANT_NEWEST_LEGACY_DELETED, TENANT_BOTH_NAMES,
            TENANT_DELETED_CANONICAL, TENANT_BLANK_CANONICAL, TENANT_CANONICAL_ONLY, TENANT_OTHER_KEY);

    private static final String OLDER = "2025-06-01 00:00:00";
    private static final String NEWER = "2026-01-02 03:04:05";
    private static final String DELETED_AT = "2025-12-01 00:00:00";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/tenant-common-test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    /** Each tenant's welcome flow id as message-service read it before V48, with the fallback. */
    private static final Map<Integer, Optional<String>> readBefore = new HashMap<>();

    @BeforeAll
    static void seedThenMigrate() throws Exception {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        insert(TENANT_ONLY_LEGACY, LEGACY_KEY, "legacy-live", NEWER, null);
        insert(TENANT_ONLY_LEGACY, LEGACY_KEY, "legacy-old", OLDER, DELETED_AT);
        insert(TENANT_NEWEST_LEGACY_DELETED, LEGACY_KEY, "legacy-live", OLDER, null);
        insert(TENANT_NEWEST_LEGACY_DELETED, LEGACY_KEY, "legacy-deleted", NEWER, NEWER);
        insert(TENANT_BOTH_NAMES, CANONICAL_KEY, "canonical", NEWER, null);
        insert(TENANT_BOTH_NAMES, LEGACY_KEY, "legacy-live", NEWER, null);
        insert(TENANT_BOTH_NAMES, LEGACY_KEY, "legacy-old", OLDER, DELETED_AT);
        insert(TENANT_DELETED_CANONICAL, CANONICAL_KEY, "canonical-deleted", OLDER, DELETED_AT);
        insert(TENANT_DELETED_CANONICAL, LEGACY_KEY, "legacy-live", NEWER, null);
        insert(TENANT_BLANK_CANONICAL, CANONICAL_KEY, "   ", NEWER, null);
        insert(TENANT_BLANK_CANONICAL, LEGACY_KEY, "legacy-live", NEWER, null);
        insert(TENANT_CANONICAL_ONLY, CANONICAL_KEY, "canonical", NEWER, null);
        insert(TENANT_OTHER_KEY, "WATER_NORM", "{\"value\":\"55\"}", NEWER, null);

        TENANTS.forEach(tenantId -> readBefore.put(tenantId,
                read(tenantId, CANONICAL_KEY).or(() -> read(tenantId, LEGACY_KEY))));

        String migration = new String(
                new ClassPathResource("db/migration/V48__retire_legacy_welcome_flow_id_config_key.sql")
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

    /** The row message-service's {@code findConfigValue} returns: any state, newest first. */
    private static Optional<String> read(int tenantId, String key) {
        return jdbcTemplate.queryForList("""
                SELECT config_value FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ?
                ORDER BY updated_at DESC, id DESC LIMIT 1
                """, String.class, tenantId, key).stream().findFirst();
    }

    private static List<Map<String, Object>> rows(int tenantId, String key) {
        return jdbcTemplate.queryForList("""
                SELECT config_value,
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                       to_char(deleted_at, 'YYYY-MM-DD HH24:MI:SS') AS deleted_at,
                       updated_at = deleted_at AS stamped_together,
                       deleted_by
                FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ? AND config_key = ?
                ORDER BY id
                """, tenantId, key);
    }

    @Test
    @DisplayName("Every tenant reads the same welcome flow id from the canonical key alone as it did with the fallback")
    void everyTenantReadsTheSameWelcomeFlowId() {
        TENANTS.forEach(tenantId -> assertThat(read(tenantId, CANONICAL_KEY))
                .as("tenant %d", tenantId)
                .isEqualTo(readBefore.get(tenantId)));
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
    @DisplayName("Every legacy row of a tenant with no canonical row is renamed, soft-deleted ones included, keeping its timestamps")
    void renamesEveryLegacyRowOfATenantWithNoCanonicalRow() {
        assertThat(rows(TENANT_ONLY_LEGACY, LEGACY_KEY)).isEmpty();
        assertThat(rows(TENANT_ONLY_LEGACY, CANONICAL_KEY)).satisfiesExactly(
                row -> assertThat(row)
                        .containsEntry("config_value", "legacy-live")
                        .containsEntry("updated_at", NEWER)
                        .containsEntry("deleted_at", null),
                row -> assertThat(row)
                        .containsEntry("config_value", "legacy-old")
                        .containsEntry("updated_at", OLDER)
                        .containsEntry("deleted_at", DELETED_AT));

        assertThat(rows(TENANT_NEWEST_LEGACY_DELETED, LEGACY_KEY)).isEmpty();
        assertThat(rows(TENANT_NEWEST_LEGACY_DELETED, CANONICAL_KEY)).hasSize(2);
        assertThat(read(TENANT_NEWEST_LEGACY_DELETED, CANONICAL_KEY)).contains("legacy-deleted");
    }

    @Test
    @DisplayName("A live legacy row beside a canonical row in any state is soft-deleted, and the canonical row is kept")
    void softDeletesALiveLegacyRowBesideACanonicalRow() {
        for (int tenantId : List.of(TENANT_BOTH_NAMES, TENANT_DELETED_CANONICAL, TENANT_BLANK_CANONICAL)) {
            assertThat(rows(tenantId, LEGACY_KEY))
                    .as("tenant %d", tenantId)
                    .filteredOn(row -> "legacy-live".equals(row.get("config_value")))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row).containsEntry("stamped_together", true);
                        assertThat(row.get("updated_at")).isNotEqualTo(NEWER);
                        assertThat(row.get("deleted_by")).isNull();
                    });
        }

        assertThat(rows(TENANT_BOTH_NAMES, CANONICAL_KEY)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("config_value", "canonical")
                        .containsEntry("updated_at", NEWER)
                        .containsEntry("deleted_at", null));
        assertThat(rows(TENANT_DELETED_CANONICAL, CANONICAL_KEY)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("config_value", "canonical-deleted")
                        .containsEntry("deleted_at", DELETED_AT));
        assertThat(rows(TENANT_BLANK_CANONICAL, CANONICAL_KEY)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("config_value", "   "));
    }

    @Test
    @DisplayName("A legacy row already soft-deleted beside a canonical row is left as it is")
    void leavesASoftDeletedLegacyRowBesideACanonicalRowAlone() {
        assertThat(rows(TENANT_BOTH_NAMES, LEGACY_KEY))
                .filteredOn(row -> "legacy-old".equals(row.get("config_value")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("updated_at", OLDER)
                        .containsEntry("deleted_at", DELETED_AT));
    }

    @Test
    @DisplayName("Canonical rows and rows under other keys are untouched")
    void leavesOtherRowsAlone() {
        assertThat(rows(TENANT_CANONICAL_ONLY, CANONICAL_KEY)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("config_value", "canonical")
                        .containsEntry("updated_at", NEWER)
                        .containsEntry("deleted_at", null));
        assertThat(rows(TENANT_OTHER_KEY, "WATER_NORM")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("updated_at", NEWER));
        assertThat(rows(TENANT_OTHER_KEY, CANONICAL_KEY)).isEmpty();
    }
}
