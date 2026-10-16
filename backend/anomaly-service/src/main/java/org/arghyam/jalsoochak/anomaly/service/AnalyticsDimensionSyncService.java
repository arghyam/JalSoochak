package org.arghyam.jalsoochak.anomaly.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsDimensionSyncService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void upsertUser(JsonNode event) {
        Integer userId = intOrNull(event, "userId");
        Integer tenantId = intOrNull(event, "tenantId");
        if (userId == null || tenantId == null) {
            log.debug("[analytics-dim-sync] skip user event: missing userId/tenantId");
            return;
        }

        String email = textOrNull(event, "email");
        Integer userType = intOrNull(event, "userType");
        UUID uuid = uuidOrNull(event, "uuid");
        String title = textOrNull(event, "title");
        Integer status = intOrNull(event, "status");

        int updated = 0;
        if (uuid != null) {
            updated = jdbcTemplate.update("""
                            UPDATE analytics_schema.dim_user_table
                            SET user_id = ?, tenant_id = ?, email = ?, user_type = ?, title = ?, status = ?, updated_at = NOW()
                            WHERE uuid = ?
                            """,
                    userId, tenantId, email, userType, title, status, uuid);
        }
        if (updated == 0) {
            updated = jdbcTemplate.update("""
                            UPDATE analytics_schema.dim_user_table
                            SET email = ?, user_type = ?, uuid = ?, title = ?, status = ?, updated_at = NOW()
                            WHERE tenant_id = ? AND user_id = ?
                            """,
                    email, userType, uuid, title, status, tenantId, userId);
        }
        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO analytics_schema.dim_user_table
                                (user_id, tenant_id, email, user_type, uuid, title, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                            """,
                    userId, tenantId, email, userType, uuid, title, status);
        }
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.canConvertToInt()) {
            return n.asInt();
        }
        if (n.isTextual()) {
            String text = n.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String text = n.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private UUID uuidOrNull(JsonNode node, String field) {
        String text = textOrNull(node, field);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
