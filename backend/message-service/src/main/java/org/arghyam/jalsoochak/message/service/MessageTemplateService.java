package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Reads the tenant values message-service puts into WhatsApp flows — the welcome flow id and the
 * state name — from {@code common_schema.tenant_config_master_table} and
 * {@code common_schema.tenant_master_table}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageTemplateService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns the tenant-specific welcome flow ID from {@code welcome_flow_id}, if configured.
     */
    public Optional<String> findWelcomeFlowId(int tenantId) {
        return findConfigValue(tenantId, "welcome_flow_id")
                .map(String::trim)
                .filter(value -> !value.isBlank());
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
}
