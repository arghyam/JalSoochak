package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate-backed repository over the per-tenant {@code reports_table}.
 *
 * <p>Caches generated report files keyed by
 * {@code (report_type, format, params_hash, data_version)}; on cache hit the
 * caller produces a fresh presigned URL from the stored
 * {@code bucket}/{@code object_key} without regenerating the file.
 *
 * <p>Schema name is the only value interpolated with {@code String.format};
 * it is validated against {@code ^[a-z_][a-z0-9_]*$}. All user-supplied
 * values bind as {@code ?} parameters.
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
public class ReportsRepository {

    private final JdbcTemplate jdbcTemplate;

    public record ReportRecord(
            UUID id,
            String reportType,
            String format,
            String paramsHash,
            long dataVersion,
            String bucket,
            String objectKey,
            Integer rowCount,
            Long fileSizeBytes,
            Long generatedBy,
            OffsetDateTime generatedAt
    ) {}

    private static void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    private RowMapper<ReportRecord> rowMapper() {
        return (rs, rowNum) -> {
            Object generatedBy = rs.getObject("generated_by");
            Long generatedById = generatedBy == null ? null : ((Number) generatedBy).longValue();
            return new ReportRecord(
                    (UUID) rs.getObject("id"),
                    rs.getString("report_type"),
                    rs.getString("format"),
                    rs.getString("params_hash"),
                    rs.getLong("data_version"),
                    rs.getString("bucket"),
                    rs.getString("object_key"),
                    (Integer) rs.getObject("row_count"),
                    (Long) rs.getObject("file_size_bytes"),
                    generatedById,
                    rs.getObject("generated_at", OffsetDateTime.class)
            );
        };
    }

    /** Cache lookup keyed on the unique constraint. */
    public Optional<ReportRecord> findByCacheKey(String schemaName, String reportType, String format,
                                                 String paramsHash, long dataVersion) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, report_type, format, params_hash, data_version,
                       bucket, object_key, row_count, file_size_bytes,
                       generated_by, generated_at
                FROM %s.reports_table
                WHERE report_type = ?
                  AND format = ?
                  AND params_hash = ?
                  AND data_version = ?
                LIMIT 1
                """, schemaName);
        return jdbcTemplate.query(sql, rs ->
                        rs.next() ? Optional.of(rowMapper().mapRow(rs, 0)) : Optional.<ReportRecord>empty(),
                reportType, format, paramsHash, dataVersion);
    }

    /**
     * Inserts a new cache row. Returns {@code true} if the row was written;
     * {@code false} if a concurrent request beat us to the unique key —
     * the caller should re-select to fetch the winning row.
     */
    public boolean insertIfAbsent(String schemaName, ReportRecord record, String paramsJson) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                INSERT INTO %s.reports_table
                    (id, report_type, format, params_hash, params_json, data_version,
                     bucket, object_key, row_count, file_size_bytes, generated_by, generated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (report_type, format, params_hash, data_version) DO NOTHING
                """, schemaName);
        int affected = jdbcTemplate.update(sql,
                record.id(),
                record.reportType(),
                record.format(),
                record.paramsHash(),
                paramsJson,
                record.dataVersion(),
                record.bucket(),
                record.objectKey(),
                record.rowCount(),
                record.fileSizeBytes(),
                record.generatedBy()
        );
        return affected > 0;
    }
}
