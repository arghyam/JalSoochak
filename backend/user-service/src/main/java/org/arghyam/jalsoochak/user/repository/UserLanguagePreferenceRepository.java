package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * The chatbot's per-contact language in {@code <tenant schema>.user_language_preference}, which
 * telemetry-service reads ahead of {@code user_table.language_id}. Contact ids are stored digits-only,
 * as telemetry stores and looks them up.
 */
@Repository
@RequiredArgsConstructor
public class UserLanguagePreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^tenant_[a-z0-9][a-z0-9_]{0,29}$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    /** Sets each contact's language to {@code languageValue}, adding a row for a contact that has none. */
    @SuppressWarnings("java:S2077")
    public void upsertAll(String schemaName, Collection<String> contactIds, String languageValue) {
        validateSchemaName(schemaName);
        if (contactIds == null || contactIds.isEmpty()) {
            return;
        }
        String sql = String.format("""
                INSERT INTO %s.user_language_preference
                    (contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (contact_id)
                DO UPDATE SET language_value = EXCLUDED.language_value,
                              updated_at = NOW()
                """, schemaName);
        List<Object[]> args = contactIds.stream()
                .map(contactId -> contactId.replaceAll("\\D", ""))
                .distinct()
                .map(contactId -> new Object[]{contactId, languageValue})
                .toList();
        jdbcTemplate.batchUpdate(sql, args);
    }
}
