package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fetches localized nudge and escalation message templates from
 * {@code common_schema.tenant_config_master_table}.
 *
 * <p>Language resolution mirrors {@code GlificWebhookService.normalizeLanguageKey()}
 * in telemetry-service: {@code user_table.language_id} (int) → {@code language_N}
 * config key → language name → normalized key.</p>
 *
 * <p>Fallback chain (nudge): {@code nudge_message_{langKey}} →
 * {@code nudge_message_english} → {@code nudge_message} → hardcoded default.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageTemplateService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns the localized nudge message for the given tenant/user, with
     * {@code {name}} and {@code {scheme}} placeholders filled in.
     */
    public String findNudgeMessage(int tenantId, int languageId,
                                   String operatorName, String schemeId) {
        String langKey = resolveLanguageKey(tenantId, languageId);
        String msg = findConfigValue(tenantId, "nudge_message_" + langKey)
                .or(() -> findConfigValue(tenantId, "nudge_message_english"))
                .or(() -> findConfigValue(tenantId, "nudge_message"))
                .orElse("Dear {name}, please submit your daily water reading for scheme {scheme}. Thank you.");
        return msg.replace("{name}", orEmpty(operatorName))
                  .replace("{scheme}", orEmpty(schemeId));
    }

    /**
     * Returns the localized escalation body text for the given tenant/officer.
     */
    public String findEscalationMessage(int tenantId, int officerLanguageId) {
        String langKey = resolveLanguageKey(tenantId, officerLanguageId);
        return findConfigValue(tenantId, "escalation_message_" + langKey)
                .or(() -> findConfigValue(tenantId, "escalation_message_english"))
                .or(() -> findConfigValue(tenantId, "escalation_message"))
                .orElse("Please find the escalation report attached.");
    }

    /**
     * Returns the tenant-specific Glific welcome flow ID if configured.
     *
     * <p>Looks up the config keys in order:
     * {@code glific_welcome_flow_id} → {@code welcome_flow_id}.</p>
     */
    public Optional<String> findWelcomeFlowId(int tenantId) {
        return findConfigValue(tenantId, "glific_welcome_flow_id")
                .or(() -> findConfigValue(tenantId, "welcome_flow_id"))
                .map(String::trim)
                .filter(value -> !value.isBlank());
    }

    private String resolveLanguageKey(int tenantId, int languageId) {
        if (languageId <= 0) return "english";
        String name = findConfigValue(tenantId, "language_" + languageId).orElse("English");
        return normalizeLanguageKey(name);
    }

    private String normalizeLanguageKey(String language) {
        if (language == null) return "";
        String lower = language.trim().toLowerCase(Locale.ROOT);
        String raw = language.trim();
        if ("हिंदी".equals(raw) || "हिन्दी".equals(raw) || "hindi".equals(lower)) return "hindi";
        if ("english".equals(lower)) return "english";
        String normalized = lower
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "english" : normalized;
    }

    public String findStateName(int tenantId) {
        return findConfigValue(tenantId, "state_name")
                .filter(s -> !s.isBlank())
                .or(() -> findTenantTitleById(tenantId))
                .orElse("");
    }

    public Optional<String> findTenantTitleById(int tenantId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT title FROM common_schema.tenant_master_table WHERE id = ? LIMIT 1",
                (rs, n) -> rs.getString("title"),
                tenantId
        );
        return rows.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .findFirst();
    }

    private Optional<String> findConfigValue(int tenantId, String key) {
        List<String> rows = jdbcTemplate.query(
                "SELECT config_value " +
                        "FROM common_schema.tenant_config_master_table " +
                        "WHERE tenant_id=? AND config_key=? " +
                        "ORDER BY updated_at DESC, id DESC LIMIT 1",
                (rs, n) -> rs.getString("config_value"), tenantId, key);
        return rows.stream().findFirst();
    }

    private String orEmpty(String s) {
        return s != null ? s : "";
    }
}
