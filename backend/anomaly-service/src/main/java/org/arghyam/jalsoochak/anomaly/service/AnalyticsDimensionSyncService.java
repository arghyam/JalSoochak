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

    @Transactional
    public void upsertScheme(JsonNode event) {
        Integer schemeId = intOrNull(event, "schemeId");
        Integer tenantId = intOrNull(event, "tenantId");
        if (schemeId == null || tenantId == null) {
            log.debug("[analytics-dim-sync] skip scheme event: missing schemeId/tenantId");
            return;
        }

        String schemeName = textOrNull(event, "schemeName");
        Integer stateSchemeId = intOrDefault(event, "stateSchemeId", 0);
        Integer centreSchemeId = intOrDefault(event, "centreSchemeId", 0);
        Double longitude = doubleOrNull(event, "longitude");
        Double latitude = doubleOrNull(event, "latitude");

        Integer parentLgd = intOrDefault(event, "parentLgdLocationId", 0);
        Integer level1Lgd = intOrDefault(event, "level1LgdId", parentLgd);
        Integer level2Lgd = intOrDefault(event, "level2LgdId", parentLgd);
        Integer level3Lgd = intOrDefault(event, "level3LgdId", parentLgd);
        Integer level4Lgd = intOrDefault(event, "level4LgdId", parentLgd);
        Integer level5Lgd = intOrDefault(event, "level5LgdId", parentLgd);
        Integer level6Lgd = intOrDefault(event, "level6LgdId", parentLgd);

        Integer parentDept = intOrNull(event, "parentDepartmentLocationId");
        Integer level1Dept = intOrDefault(event, "level1DeptId", parentDept != null ? parentDept : 0);
        Integer level2Dept = intOrDefault(event, "level2DeptId", parentDept != null ? parentDept : 0);
        Integer level3Dept = intOrDefault(event, "level3DeptId", parentDept != null ? parentDept : 0);
        Integer level4Dept = intOrDefault(event, "level4DeptId", parentDept != null ? parentDept : 0);
        Integer level5Dept = intOrDefault(event, "level5DeptId", parentDept != null ? parentDept : 0);
        Integer level6Dept = intOrDefault(event, "level6DeptId", parentDept != null ? parentDept : 0);
        Integer status = intOrNull(event, "status");

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table (
                    scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                    parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                    parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (tenant_id, scheme_id) DO UPDATE SET
                    scheme_name = EXCLUDED.scheme_name,
                    state_scheme_id = EXCLUDED.state_scheme_id,
                    centre_scheme_id = EXCLUDED.centre_scheme_id,
                    longitude = EXCLUDED.longitude,
                    latitude = EXCLUDED.latitude,
                    parent_lgd_location_id = EXCLUDED.parent_lgd_location_id,
                    level_1_lgd_id = EXCLUDED.level_1_lgd_id,
                    level_2_lgd_id = EXCLUDED.level_2_lgd_id,
                    level_3_lgd_id = EXCLUDED.level_3_lgd_id,
                    level_4_lgd_id = EXCLUDED.level_4_lgd_id,
                    level_5_lgd_id = EXCLUDED.level_5_lgd_id,
                    level_6_lgd_id = EXCLUDED.level_6_lgd_id,
                    parent_department_location_id = EXCLUDED.parent_department_location_id,
                    level_1_dept_id = EXCLUDED.level_1_dept_id,
                    level_2_dept_id = EXCLUDED.level_2_dept_id,
                    level_3_dept_id = EXCLUDED.level_3_dept_id,
                    level_4_dept_id = EXCLUDED.level_4_dept_id,
                    level_5_dept_id = EXCLUDED.level_5_dept_id,
                    level_6_dept_id = EXCLUDED.level_6_dept_id,
                    status = EXCLUDED.status,
                    updated_at = NOW()
                """,
                schemeId, tenantId, schemeName, stateSchemeId, centreSchemeId, longitude, latitude,
                parentLgd, level1Lgd, level2Lgd, level3Lgd, level4Lgd, level5Lgd, level6Lgd,
                parentDept, level1Dept, level2Dept, level3Dept, level4Dept, level5Dept, level6Dept,
                status);
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

    private Integer intOrDefault(JsonNode node, String field, Integer defaultValue) {
        Integer value = intOrNull(node, field);
        return value != null ? value : defaultValue;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String text = n.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private Double doubleOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isTextual()) {
            String text = n.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
