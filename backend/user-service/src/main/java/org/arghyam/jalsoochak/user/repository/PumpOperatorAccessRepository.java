package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scope lookups backing object-level authorization on {@code /api/v1/pumpoperator/**}.
 *
 * <p>A tenant staff member (section officer, sub-divisional officer, …) may only read the
 * schemes assigned to them and the pump operators working on those schemes. Both facts live in
 * {@code <tenant>.user_scheme_mapping_table}, which maps every tenant user — officers and
 * operators alike — to the schemes they are responsible for.
 *
 * <p>All queries fail closed: a missing mapping table (older tenant schemas) or an absent row
 * yields {@code false}, i.e. no access.
 *
 * <p>SQL uses {@code String.format} only to inject {@code schemaName}, which is validated
 * against {@code ^[a-z_][a-z0-9_]*$} before use. All caller-supplied values are bound as
 * {@code ?} parameters.
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
public class PumpOperatorAccessRepository {

    private final JdbcTemplate jdbcTemplate;

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    private boolean mappingTableExists(String schemaName) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = ?
                      AND table_name = 'user_scheme_mapping_table'
                )
                """;
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, schemaName));
    }

    /**
     * Returns {@code true} when {@code callerUserId} and {@code targetUserId} are both actively
     * mapped to at least one common scheme — the condition under which an officer may read a
     * pump operator's record.
     */
    public boolean sharesActiveSchemeWith(String schemaName, long callerUserId, long targetUserId) {
        validateSchemaName(schemaName);
        if (!mappingTableExists(schemaName)) {
            return false;
        }
        String sql = String.format("""
                SELECT EXISTS (
                    SELECT 1
                    FROM %s.user_scheme_mapping_table caller
                    JOIN %s.user_scheme_mapping_table target
                      ON target.scheme_id = caller.scheme_id
                     AND target.deleted_at IS NULL
                     AND target.status = 1
                     AND target.user_id = ?
                    WHERE caller.deleted_at IS NULL
                      AND caller.status = 1
                      AND caller.user_id = ?
                )
                """, schemaName, schemaName);
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(sql, Boolean.class, targetUserId, callerUserId));
    }

    /**
     * Returns {@code true} when {@code schemeId} is actively assigned to {@code callerUserId}.
     */
    public boolean isMappedToScheme(String schemaName, long callerUserId, long schemeId) {
        validateSchemaName(schemaName);
        if (!mappingTableExists(schemaName)) {
            return false;
        }
        String sql = String.format("""
                SELECT EXISTS (
                    SELECT 1
                    FROM %s.user_scheme_mapping_table usm
                    WHERE usm.deleted_at IS NULL
                      AND usm.status = 1
                      AND usm.user_id = ?
                      AND usm.scheme_id = ?
                )
                """, schemaName);
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(sql, Boolean.class, callerUserId, schemeId));
    }
}
