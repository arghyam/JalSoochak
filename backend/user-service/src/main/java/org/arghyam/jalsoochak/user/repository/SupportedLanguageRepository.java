package org.arghyam.jalsoochak.user.repository;

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

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^tenant_[a-z0-9][a-z0-9_]{0,29}$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    /** The supported language names, most preferred first. */
    @SuppressWarnings("java:S2077")
    public List<String> findLanguageNames(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT language_name
                FROM %s.language_master_table
                WHERE status = ?
                ORDER BY preference, id
                """, schemaName);
        return jdbcTemplate.queryForList(sql, String.class, ACTIVE);
    }
}
