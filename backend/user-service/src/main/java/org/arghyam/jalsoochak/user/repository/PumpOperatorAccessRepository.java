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
     * Returns {@code true} when {@code targetUserId} is a pump operator whose user record is not
     * soft-deleted, and {@code callerUserId} and that operator are both actively mapped to at least
     * one common scheme — the condition under which an officer may read a pump operator's record.
     *
     * <p>The operator's own {@code status} is deliberately not checked: the officer console lists
     * inactive operators too, and an officer must still be able to open them.
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
                    JOIN %s.user_table tu
                      ON tu.id = target.user_id
                     AND tu.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = tu.user_type
                     AND upper(COALESCE(ut.c_name, '')) = 'PUMP_OPERATOR'
                    WHERE caller.deleted_at IS NULL
                      AND caller.status = 1
                      AND caller.user_id = ?
                )
                """, schemaName, schemaName, schemaName);
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
