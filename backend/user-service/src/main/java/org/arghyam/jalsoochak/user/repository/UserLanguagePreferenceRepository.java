package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The chatbot's per-contact language in {@code <tenant schema>.user_language_preference}, which
 * telemetry-service reads ahead of {@code user_table.language_id}. Contact ids are stored digits-only,
 * as telemetry stores and looks them up, and a blank language counts as none, as telemetry reads it.
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

    /** The given contacts that already have a language, each in the form it was given. */
    @SuppressWarnings("java:S2077")
    public Set<String> findContactIdsWithLanguage(String schemaName, Collection<String> contactIds) {
        validateSchemaName(schemaName);
        if (contactIds == null || contactIds.isEmpty()) {
            return Set.of();
        }
        Map<String, List<String>> givenByStored = contactIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(UserLanguagePreferenceRepository::digitsOnly,
                        LinkedHashMap::new, Collectors.toList()));
        String sql = String.format("""
                SELECT contact_id
                FROM %s.user_language_preference
                WHERE contact_id IN (%s)
                  AND NULLIF(BTRIM(language_value), '') IS NOT NULL
                """, schemaName, String.join(",", Collections.nCopies(givenByStored.size(), "?")));
        Set<String> withLanguage = new LinkedHashSet<>();
        jdbcTemplate.queryForList(sql, String.class, givenByStored.keySet().toArray())
                .forEach(stored -> withLanguage.addAll(givenByStored.get(stored)));
        return withLanguage;
    }

    /**
     * Gives each contact {@code languageValue} unless it already has a language: adds a row for a
     * contact that has none and fills a blank one, but never overwrites a language the contact chose.
     */
    @SuppressWarnings("java:S2077")
    public void setLanguageWhereUnset(String schemaName, Collection<String> contactIds, String languageValue) {
        validateSchemaName(schemaName);
        if (contactIds == null || contactIds.isEmpty()) {
            return;
        }
        String sql = String.format("""
                INSERT INTO %s.user_language_preference AS preference
                    (contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (contact_id)
                DO UPDATE SET language_value = EXCLUDED.language_value,
                              updated_at = NOW()
                WHERE NULLIF(BTRIM(preference.language_value), '') IS NULL
                """, schemaName);
        List<Object[]> args = contactIds.stream()
                .filter(Objects::nonNull)
                .map(UserLanguagePreferenceRepository::digitsOnly)
                .distinct()
                .map(contactId -> new Object[]{contactId, languageValue})
                .toList();
        jdbcTemplate.batchUpdate(sql, args);
    }

    private static String digitsOnly(String contactId) {
        return contactId.replaceAll("\\D", "");
    }
}
