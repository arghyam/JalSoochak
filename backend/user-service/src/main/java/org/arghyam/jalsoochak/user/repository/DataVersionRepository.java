package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tracks monotonically-increasing version counters per resource type in
 * {@code tenant_<code>.data_versions_table}.
 *
 * <p>Used by the report cache to invalidate previously-generated files: every
 * mutation bumps the counter; the report cache key includes the version, so
 * any change forces regeneration on the next request.
 *
 * <p>{@link #bump(String, ResourceType)} is an atomic upsert — concurrent
 * bumps cannot lose updates because the increment happens inside a single
 * SQL statement with row-level locking. The first bump for an unseen
 * resource type also initializes the row.
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
public class DataVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    /**
     * @return current version for {@code resourceType}, or {@code 0} if no row
     *         has been written yet (treated as "no data has changed").
     */
    public long getCurrent(String schemaName, ResourceType resourceType) {
        validateSchemaName(schemaName);
        String sql = String.format(
                "SELECT version FROM %s.data_versions_table WHERE resource_type = ?",
                schemaName);
        Long version = jdbcTemplate.query(sql, rs -> rs.next() ? rs.getLong(1) : null, resourceType.key());
        return version == null ? 0L : version;
    }

    /**
     * Atomically increments the counter for {@code resourceType} and returns
     * the new value. Initializes the row to {@code 1} on first call.
     */
    public long bump(String schemaName, ResourceType resourceType) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                INSERT INTO %s.data_versions_table (resource_type, version, updated_at)
                VALUES (?, 1, NOW())
                ON CONFLICT (resource_type) DO UPDATE
                    SET version = %s.data_versions_table.version + 1,
                        updated_at = NOW()
                RETURNING version
                """, schemaName, schemaName);
        Long version = jdbcTemplate.queryForObject(sql, Long.class, resourceType.key());
        return version == null ? 0L : version;
    }
}
