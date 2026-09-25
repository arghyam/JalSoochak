package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * USER-PREFERENCE-TENANT-SCHEMA: per-contact language preferences in
 * {@code <tenant schema>.user_language_preference}, where the schema is the tenant boundary.
 * Contact ids are stored and looked up digits-only, so every shape the chatbot sends resolves to one row.
 */
@Repository
@RequiredArgsConstructor
public class UserLanguagePreferenceRepository {

    private static final String TABLE_NAME = "user_language_preference";

    private final JdbcTemplate jdbcTemplate;
    private final TelemetryTenantRepository telemetryTenantRepository;

    public void upsert(String schemaName, String contactId, String languageValue) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                INSERT INTO %s.user_language_preference
                    (contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (contact_id)
                DO UPDATE SET language_value = EXCLUDED.language_value,
                              updated_at = NOW()
                """, schemaName);
        jdbcTemplate.update(sql, normalizeContactId(contactId), languageValue);
    }

    public Optional<String> findLanguage(String schemaName, String contactId) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                SELECT language_value
                FROM %s.user_language_preference
                WHERE contact_id = ?
                """, schemaName);
        return jdbcTemplate.query(sql, (rs, n) -> rs.getString("language_value"), normalizeContactId(contactId))
                .stream()
                .findFirst();
    }

    /**
     * The live tenant whose schema holds the contact's most recently updated language preference,
     * used to pick a tenant first when the same number is registered in several. One query spans
     * every tenant schema that has the table.
     */
    public Optional<Integer> findPreferredTenantIdByContactId(String contactId) {
        String normalizedContactId = normalizeContactId(contactId);
        if (normalizedContactId == null || normalizedContactId.isEmpty()) {
            return Optional.empty();
        }
        List<String> schemaNames = telemetryTenantRepository.findTenantSchemasWithColumn(TABLE_NAME, "contact_id");
        if (schemaNames.isEmpty()) {
            return Optional.empty();
        }

        List<String> branches = new ArrayList<>(schemaNames.size());
        List<Object> args = new ArrayList<>(schemaNames.size() * 2);
        for (String schemaName : schemaNames) {
            SchemaNames.validate(schemaName);
            branches.add(String.format("""
                    SELECT CAST(? AS TEXT) AS schema_name, updated_at, created_at
                    FROM %s.user_language_preference
                    WHERE contact_id = ?
                    """, schemaName));
            args.add(schemaName);
            args.add(normalizedContactId);
        }
        String sql = String.format("""
                SELECT t.id
                FROM (
                %s) preference
                JOIN common_schema.tenant_master_table t
                  ON 'tenant_' || lower(trim(t.state_code)) = preference.schema_name
                 AND t.deleted_at IS NULL
                ORDER BY preference.updated_at DESC, preference.created_at DESC
                LIMIT 1
                """, String.join("UNION ALL\n", branches));
        return jdbcTemplate.query(sql, (rs, n) -> rs.getInt("id"), args.toArray())
                .stream()
                .findFirst();
    }

    private String normalizeContactId(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\D", "");
    }
}
