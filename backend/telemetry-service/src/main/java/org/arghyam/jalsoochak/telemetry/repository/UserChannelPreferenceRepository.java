package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserChannelPreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public void upsert(Integer tenantId, Long schemeId, String channelValue) {
        String sql = """
                INSERT INTO common_schema.user_channel_preference
                    (tenant_id, scheme_id, channel_value, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                ON CONFLICT (tenant_id, scheme_id)
                DO UPDATE SET channel_value = EXCLUDED.channel_value,
                              updated_at = NOW()
                """;
        jdbcTemplate.update(sql, tenantId, schemeId, channelValue);
    }

    public Optional<String> findChannelValue(Integer tenantId, Long schemeId) {
        String sql = """
                SELECT channel_value
                FROM common_schema.user_channel_preference
                WHERE tenant_id = ?
                  AND scheme_id = ?
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, (rs, n) -> rs.getString("channel_value"), tenantId, schemeId)
                .stream()
                .findFirst();
    }
}
