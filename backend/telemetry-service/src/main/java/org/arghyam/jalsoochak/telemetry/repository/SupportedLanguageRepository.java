package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A tenant's {@code SUPPORTED_LANGUAGES} (the portal's Language page): the active rows of
 * {@code <tenant schema>.language_master_table}, which tenant-service writes.
 */
@Repository
@RequiredArgsConstructor
public class SupportedLanguageRepository {

    private static final int ACTIVE = 1;

    private final JdbcTemplate jdbcTemplate;

    /** The supported language names, most preferred first. */
    public List<String> findLanguageNames(String schemaName) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                SELECT language_name
                FROM %s.language_master_table
                WHERE status = ?
                ORDER BY preference, id
                """, schemaName);
        return jdbcTemplate.queryForList(sql, String.class, ACTIVE);
    }
}
