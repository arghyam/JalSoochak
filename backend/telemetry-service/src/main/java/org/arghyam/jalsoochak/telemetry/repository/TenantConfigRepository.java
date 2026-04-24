package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@RequiredArgsConstructor
public class TenantConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    @Value("${telemetry.cache.tenant-config.enabled:true}")
    private boolean tenantConfigCacheEnabled = true;
    @Value("${telemetry.cache.tenant-config.ttl-ms:120000}")
    private long tenantConfigCacheTtlMs = 120_000L;
    private final Map<Integer, TimedCacheValue<Map<String, String>>> tenantConfigCache = new ConcurrentHashMap<>();

    public Optional<Integer> findTenantIdByStateCode(String tenantCode) {
        String sql = """
                SELECT id
                FROM common_schema.tenant_master_table
                WHERE LOWER(state_code) = LOWER(?)
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getInt("id"), tenantCode);
        return rows.stream().findFirst();
    }

    public Optional<String> findLanguageSelectionPrompt(Integer tenantId) {
        return findConfigValue(tenantId, "language_selection_prompt");
    }

    public Optional<String> findLanguageSelectionPrompt(Integer tenantId, String languageKey) {
        if (languageKey == null || languageKey.isBlank()) {
            return findLanguageSelectionPrompt(tenantId);
        }
        String langSpecificKey = "language_selection_prompt_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findLanguageSelectionPrompt(tenantId));
    }

    public List<String> findLanguageOptions(Integer tenantId) {
        return findIndexedConfigValues(tenantId, "language_", null);
    }

    public Optional<String> findConfigValue(Integer tenantId, String configKey) {
        if (tenantId == null || configKey == null || configKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(getTenantConfigMap(tenantId).get(configKey));
    }

    public Optional<String> findChannelSelectionPrompt(Integer tenantId, String languageKey) {
        String langSpecificKey = "channel_selection_prompt_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "channel_selection_prompt"));
    }

    public List<String> findChannelOptions(Integer tenantId, String languageKey) {
        List<String> localized = findIndexedConfigValues(tenantId, "channel_", normalizeLanguageKey(languageKey));
        if (!localized.isEmpty()) {
            return localized;
        }
        return findIndexedConfigValues(tenantId, "channel_", null);
    }

    public Optional<String> findItemSelectionPrompt(Integer tenantId, String languageKey) {
        String langSpecificKey = "item_selection_prompt_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "item_selection_prompt"));
    }

    public List<String> findItemOptions(Integer tenantId, String languageKey) {
        List<String> localized = findIndexedConfigValues(tenantId, "item_", normalizeLanguageKey(languageKey));
        if (!localized.isEmpty()) {
            return localized;
        }
        return findIndexedConfigValues(tenantId, "item_", null);
    }

    public Optional<String> findMeterChangePrompt(Integer tenantId, String languageKey) {
        String langSpecificKey = "meter_change_prompt_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "meter_change_prompt"));
    }

    public List<String> findMeterChangeReasons(Integer tenantId, String languageKey) {
        List<String> localized = findIndexedConfigValues(tenantId, "meter_change_reason_", normalizeLanguageKey(languageKey));
        if (!localized.isEmpty()) {
            return localized;
        }
        return findIndexedConfigValues(tenantId, "meter_change_reason_", null);
    }

    public Optional<String> findTakeMeterReadingPrompt(Integer tenantId, String languageKey) {
        String langSpecificKey = "take_meter_reading_prompt_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "take_meter_reading_prompt"));
    }

    public Optional<String> findManualReadingConfirmationTemplate(Integer tenantId, String languageKey) {
        String langSpecificKey = "manual_reading_confirmation_template_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "manual_reading_confirmation_template"));
    }

    public Optional<String> findMeterChangeConfirmationTemplate(Integer tenantId, String languageKey) {
        String langSpecificKey = "meter_change_confirmation_template_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "meter_change_confirmation_template"));
    }

    public Optional<String> findIssueReportPrompt(Integer tenantId, String languageKey) {
        String langSpecificKey = "issue_report_prompt_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "issue_report_prompt"));
    }

    public List<String> findIssueReportReasons(Integer tenantId, String languageKey) {
        List<String> localized = findIndexedConfigValues(tenantId, "issue_report_reason_", normalizeLanguageKey(languageKey));
        if (!localized.isEmpty()) {
            return localized;
        }
        return findIndexedConfigValues(tenantId, "issue_report_reason_", null);
    }

    public Optional<String> findIssueReportConfirmationTemplate(Integer tenantId, String languageKey) {
        String langSpecificKey = "issue_report_confirmation_template_" + languageKey;
        return findConfigValue(tenantId, langSpecificKey)
                .or(() -> findConfigValue(tenantId, "issue_report_confirmation_template"));
    }

    public void invalidateTenantConfigCache(Integer tenantId) {
        if (tenantId == null) {
            return;
        }
        tenantConfigCache.remove(tenantId);
    }

    public void invalidateAllTenantConfigCache() {
        tenantConfigCache.clear();
    }

    private Map<String, String> getTenantConfigMap(Integer tenantId) {
        if (tenantId == null) {
            return Map.of();
        }
        if (!tenantConfigCacheEnabled || tenantConfigCacheTtlMs <= 0L) {
            return queryTenantConfigMap(tenantId);
        }
        long now = System.currentTimeMillis();
        TimedCacheValue<Map<String, String>> cached = tenantConfigCache.get(tenantId);
        if (cached != null && !cached.isExpired(now)) {
            return cached.value();
        }
        Map<String, String> refreshed = queryTenantConfigMap(tenantId);
        tenantConfigCache.put(tenantId, new TimedCacheValue<>(refreshed, now + tenantConfigCacheTtlMs));
        return refreshed;
    }

    private Map<String, String> queryTenantConfigMap(Integer tenantId) {
        String sql = """
                SELECT config_key, config_value
                FROM common_schema.tenant_config_master_table
                WHERE tenant_id = ?
                """;
        List<Map.Entry<String, String>> rows = jdbcTemplate.query(
                sql,
                (rs, n) -> new AbstractMap.SimpleEntry<>(rs.getString("config_key"), rs.getString("config_value")),
                tenantId
        );
        Map<String, String> configMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            if (row.getKey() == null || row.getKey().isBlank()) {
                continue;
            }
            configMap.putIfAbsent(row.getKey(), row.getValue());
        }
        return Collections.unmodifiableMap(configMap);
    }

    private List<String> findIndexedConfigValues(Integer tenantId, String prefix, String languageSuffix) {
        Map<String, String> configMap = getTenantConfigMap(tenantId);
        if (configMap.isEmpty()) {
            return List.of();
        }
        List<IndexedConfigValue> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            Integer index = parseIndexedKey(entry.getKey(), prefix, languageSuffix);
            if (index == null) {
                continue;
            }
            values.add(new IndexedConfigValue(index, entry.getValue()));
        }
        values.sort(Comparator.comparingInt(IndexedConfigValue::index));
        return values.stream().map(IndexedConfigValue::value).toList();
    }

    private Integer parseIndexedKey(String key, String prefix, String languageSuffix) {
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        String remainder = key.substring(prefix.length());
        if (languageSuffix == null) {
            if (remainder.isEmpty() || !remainder.chars().allMatch(Character::isDigit)) {
                return null;
            }
            return Integer.parseInt(remainder);
        }

        String expectedSuffix = "_" + languageSuffix;
        if (!remainder.endsWith(expectedSuffix)) {
            return null;
        }
        String numericPart = remainder.substring(0, remainder.length() - expectedSuffix.length());
        if (numericPart.isEmpty() || !numericPart.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.parseInt(numericPart);
    }

    private String normalizeLanguageKey(String languageKey) {
        if (languageKey == null) {
            return null;
        }
        String normalized = languageKey.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private record TimedCacheValue<T>(T value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    private record IndexedConfigValue(int index, String value) {
    }

}
