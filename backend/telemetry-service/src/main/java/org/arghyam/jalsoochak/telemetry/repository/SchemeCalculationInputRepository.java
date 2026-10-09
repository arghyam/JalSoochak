package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * A scheme's inputs to the ELM and PDU water-quantity formulas, from the tenant schema: its active
 * pumps' ratings and, for ELM, its {@code k_factor}.
 */
@Repository
@RequiredArgsConstructor
public class SchemeCalculationInputRepository {

    /** {@code asset_pump_registry_table.status} of a pump in service. */
    private static final int ACTIVE_PUMP_STATUS = 1;

    private final JdbcTemplate jdbcTemplate;

    /** The scheme's {@code k_factor}; empty when it isn't set or the scheme doesn't exist. */
    public Optional<BigDecimal> findKFactor(String schemaName, Long schemeId) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                SELECT k_factor
                FROM %s.scheme_master_table
                WHERE id = ?
                """, schemaName);
        List<BigDecimal> rows = jdbcTemplate.query(sql, (rs, n) -> decimal(rs, "k_factor"), schemeId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** The scheme's pumps that are in service and not deleted, in registration order. */
    public List<ActivePump> findActivePumps(String schemaName, Long schemeId) {
        SchemaNames.validate(schemaName);
        String sql = String.format("""
                SELECT id, pump_discharge_capacity, pump_efficiency, pump_head,
                       motor_power, motor_power_unit, motor_efficiency, units_consumed_per_hour,
                       power_factor
                FROM %s.asset_pump_registry_table
                WHERE scheme_id = ?
                  AND status = ?
                  AND deleted_at IS NULL
                ORDER BY id
                """, schemaName);
        return jdbcTemplate.query(sql, (rs, n) -> new ActivePump(
                rs.getLong("id"),
                decimal(rs, "pump_discharge_capacity"),
                decimal(rs, "pump_efficiency"),
                decimal(rs, "pump_head"),
                decimal(rs, "motor_power"),
                rs.getString("motor_power_unit"),
                decimal(rs, "motor_efficiency"),
                decimal(rs, "units_consumed_per_hour"),
                decimal(rs, "power_factor")
        ), schemeId, ACTIVE_PUMP_STATUS);
    }

    /**
     * A {@code FLOAT} column as the decimal it was entered as. {@link BigDecimal#valueOf(double)}
     * takes the shortest decimal that round-trips to the stored double, so 0.7 stays 0.7 instead of
     * becoming 0.6999999999999999555910790149937….
     */
    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        Double value = rs.getObject(column, Double.class);
        return value != null ? BigDecimal.valueOf(value) : null;
    }
}
