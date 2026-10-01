package org.arghyam.jalsoochak.scheme.kafka;

import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code SCHEME_UPDATED} payload analytics consumes from {@code scheme-service-topic}.
 * Shared by the scheme upload / status paths and the state sync so both describe a scheme the same way.
 */
public final class SchemeDimensionEventPayloads {

    private SchemeDimensionEventPayloads() {
    }

    public static Map<String, Object> schemeUpdated(Integer tenantId, SchemeDbRepository.SchemeAnalyticsRow row) {
        Integer parentLgd = row.parentLgdId() != null ? row.parentLgdId() : 0;
        Integer parentDept = row.parentDepartmentId();
        int deptLevelFallback = parentDept != null ? parentDept : 0;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "SCHEME_UPDATED");
        payload.put("schemeId", row.schemeId());
        payload.put("tenantId", tenantId);
        payload.put("schemeName", row.schemeName());
        payload.put("stateSchemeId", safeParseInt(row.stateSchemeId()));
        payload.put("centreSchemeId", safeParseInt(row.centreSchemeId()));
        payload.put("longitude", row.longitude());
        payload.put("latitude", row.latitude());
        payload.put("parentLgdLocationId", parentLgd);
        payload.put("level1LgdId", parentLgd);
        payload.put("level2LgdId", parentLgd);
        payload.put("level3LgdId", parentLgd);
        payload.put("level4LgdId", parentLgd);
        payload.put("level5LgdId", parentLgd);
        payload.put("level6LgdId", parentLgd);
        payload.put("parentDepartmentLocationId", parentDept);
        payload.put("level1DeptId", deptLevelFallback);
        payload.put("level2DeptId", deptLevelFallback);
        payload.put("level3DeptId", deptLevelFallback);
        payload.put("level4DeptId", deptLevelFallback);
        payload.put("level5DeptId", deptLevelFallback);
        payload.put("level6DeptId", deptLevelFallback);
        payload.put("status", row.operatingStatus());
        payload.put("operating_status", row.operatingStatus());
        payload.put("work_status", row.workStatus());
        return payload;
    }

    static Integer safeParseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
