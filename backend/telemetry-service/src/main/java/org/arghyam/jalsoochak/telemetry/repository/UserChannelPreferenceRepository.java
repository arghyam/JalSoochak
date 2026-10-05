package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * USER-PREFERENCE-TENANT-SCHEMA: per-contact reading-channel preferences in
 * {@code <tenant schema>.user_channel_preference}, where the schema is the tenant boundary.
 * Contact ids are stored and looked up digits-only, so every shape the chatbot sends resolves to one row.
 */
@Repository
@RequiredArgsConstructor
public class UserChannelPreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public void upsert(String schemaName, String contactId, String channelValue) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                INSERT INTO %s.user_channel_preference
                    (contact_id, channel_value, created_at, updated_at)
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (contact_id)
                DO UPDATE SET channel_value = EXCLUDED.channel_value,
                              updated_at = NOW()
                """, schemaName);
        jdbcTemplate.update(sql, normalizeContactId(contactId), channelValue);
    }

    public Optional<String> findChannelValue(String schemaName, String contactId) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                SELECT channel_value
                FROM %s.user_channel_preference
                WHERE contact_id = ?
                """, schemaName);
        return jdbcTemplate.query(sql, (rs, n) -> rs.getString("channel_value"), normalizeContactId(contactId))
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
