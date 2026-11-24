package org.arghyam.jalsoochak.message.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reads a tenant's {@code SITUATION_REPORT_LAYOUT} config from
 * {@code common_schema.tenant_config_master_table}, where tenant-service writes it.
 *
 * <p>Returns the stored JSON unparsed: deciding whether it is usable, and what to do when it is
 * not, belongs to {@code ReportLayoutResolver}. Same query shape as
 * {@link TenantProviderConfigRepository}, including {@code deleted_at IS NULL}.</p>
 *
 * <p>Unlike that repository, a database error is not swallowed. Every other step of a report reads
 * the same database, so the event could not be delivered anyway, and rendering the built-in layout
 * for a tenant that has its own would be worse than the Kafka retry the exception leads to.</p>
 */
@Repository
@RequiredArgsConstructor
public class TenantReportLayoutRepository {

    /** Matches tenant-service's {@code TenantConfigKeyEnum.SITUATION_REPORT_LAYOUT}. */
    static final String CONFIG_KEY = "SITUATION_REPORT_LAYOUT";

    private static final String FIND_CONFIG_SQL = """
            SELECT config_value
              FROM common_schema.tenant_config_master_table
             WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NULL
             ORDER BY updated_at DESC, id DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * The tenant's stored layout config, or empty when it has none (or only a blank value).
     *
     * @throws org.springframework.dao.DataAccessException if the table cannot be read
     */
    public Optional<String> findConfigValue(int tenantId) {
        List<String> rows = jdbcTemplate.query(FIND_CONFIG_SQL,
                (rs, rowNum) -> rs.getString("config_value"), tenantId, CONFIG_KEY);
        // Not Stream.findFirst(): it throws on a null element, and config_value is nullable.
        return rows.isEmpty() ? Optional.empty()
                : Optional.ofNullable(rows.get(0)).filter(value -> !value.isBlank());
    }
}
