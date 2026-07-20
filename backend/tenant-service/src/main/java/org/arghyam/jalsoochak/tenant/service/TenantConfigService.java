package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.DailyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads per-tenant configuration from {@code common_schema.tenant_config_master_table}.
 * Config values are stored as JSON blobs under well-known keys.
 * Falls back to application-level defaults if the row is absent or parsing fails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantConfigService {

    private static final String NUDGE_KEY = "PUMP_OPERATOR_REMINDER_NUDGE_TIME";
    private static final String ESCALATION_KEY = "FIELD_STAFF_ESCALATION_RULES";
    private static final String DAILY_REPORT_KEY = "DAILY_SITUATION_REPORT_TIME";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${nudge.schedule.hour:8}")
    private int defaultNudgeHour;

    @Value("${nudge.schedule.minute:0}")
    private int defaultNudgeMinute;

    @Value("${escalation.schedule.hour:9}")
    private int defaultEscalationHour;

    @Value("${escalation.schedule.minute:0}")
    private int defaultEscalationMinute;

    @Value("${escalation.level1.threshold.days:3}")
    private int defaultLevel1Days;

    @Value("${escalation.level2.threshold.days:7}")
    private int defaultLevel2Days;

    @Value("${escalation.level1.officer.user_type:SECTION_OFFICER}")
    private String defaultLevel1OfficerType;

    @Value("${escalation.level2.officer.user_type:DISTRICT_OFFICER}")
    private String defaultLevel2OfficerType;

    @Value("${daily-report.schedule.hour:6}")
    private int defaultDailyReportHour;

    @Value("${daily-report.schedule.minute:0}")
    private int defaultDailyReportMinute;

    public NudgeScheduleConfig getNudgeConfig(int tenantId) {
        String json = fetchConfigValue(tenantId, NUDGE_KEY);
        if (json == null) return defaultNudgeConfig();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode sched = root.path("nudge").path("schedule");
            return NudgeScheduleConfig.builder()
                    .hour(sched.path("hour").asInt(defaultNudgeHour))
                    .minute(sched.path("minute").asInt(defaultNudgeMinute))
                    .build();
        } catch (Exception e) {
            log.warn("[TenantConfig] Failed to parse nudge config for tenant={}: {}", tenantId, e.getMessage());
            return defaultNudgeConfig();
        }
    }

    public EscalationScheduleConfig getEscalationConfig(int tenantId) {
        String json = fetchConfigValue(tenantId, ESCALATION_KEY);
        if (json == null) return defaultEscalationConfig();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode esc = root.path("escalation");
            JsonNode sched = esc.path("schedule");
            JsonNode l1 = esc.path("level1");
            JsonNode l2 = esc.path("level2");
            return EscalationScheduleConfig.builder()
                    .hour(sched.path("hour").asInt(defaultEscalationHour))
                    .minute(sched.path("minute").asInt(defaultEscalationMinute))
                    .level1Days(l1.path("threshold").path("days").asInt(defaultLevel1Days))
                    .level1OfficerType(officerType(l1.path("officer"), defaultLevel1OfficerType))
                    .level2Days(l2.path("threshold").path("days").asInt(defaultLevel2Days))
                    .level2OfficerType(officerType(l2.path("officer"), defaultLevel2OfficerType))
                    .build();
        } catch (Exception e) {
            log.warn("[TenantConfig] Failed to parse escalation config for tenant={}: {}", tenantId, e.getMessage());
            return defaultEscalationConfig();
        }
    }

    public DailyReportScheduleConfig getDailyReportConfig(int tenantId) {
        String json = fetchConfigValue(tenantId, DAILY_REPORT_KEY);
        if (json == null) return defaultDailyReportConfig();
        try {
            JsonNode sched = objectMapper.readTree(json).path("dailyReport").path("schedule");
            return DailyReportScheduleConfig.builder()
                    .hour(sched.path("hour").asInt(defaultDailyReportHour))
                    .minute(sched.path("minute").asInt(defaultDailyReportMinute))
                    .build();
        } catch (Exception e) {
            log.warn("[TenantConfig] Failed to parse daily-report config for tenant={}: {}", tenantId, e.getMessage());
            return defaultDailyReportConfig();
        }
    }

    private DailyReportScheduleConfig defaultDailyReportConfig() {
        return DailyReportScheduleConfig.builder()
                .hour(defaultDailyReportHour)
                .minute(defaultDailyReportMinute)
                .build();
    }

    private String fetchConfigValue(int tenantId, String key) {
        try {
            String sql = "SELECT config_value FROM common_schema.tenant_config_master_table " +
                    "WHERE tenant_id = ? AND config_key = ? " +
                    "ORDER BY updated_at DESC, id DESC LIMIT 1";
            return jdbcTemplate.queryForObject(sql, String.class, tenantId, key);
        } catch (EmptyResultDataAccessException e) {
            log.debug("[TenantConfig] Key '{}' not found for tenant={}", key, tenantId);
            return null;
        } catch (Exception e) {
            log.warn("[TenantConfig] Error reading key '{}' for tenant={}: {}", key, tenantId, e.getMessage());
            return null;
        }
    }

    /** Reads officer user-type from a JSON node, accepting both camelCase and snake_case keys. */
    private String officerType(JsonNode officer, String defaultValue) {
        JsonNode node = officer.path("userType");
        if (!node.isMissingNode() && !node.isNull()) return node.asText(defaultValue);
        return officer.path("user_type").asText(defaultValue);
    }

    private NudgeScheduleConfig defaultNudgeConfig() {
        return NudgeScheduleConfig.builder()
                .hour(defaultNudgeHour)
                .minute(defaultNudgeMinute)
                .build();
    }

    private EscalationScheduleConfig defaultEscalationConfig() {
        return EscalationScheduleConfig.builder()
                .hour(defaultEscalationHour)
                .minute(defaultEscalationMinute)
                .level1Days(defaultLevel1Days)
                .level1OfficerType(defaultLevel1OfficerType)
                .level2Days(defaultLevel2Days)
                .level2OfficerType(defaultLevel2OfficerType)
                .build();
    }
}
