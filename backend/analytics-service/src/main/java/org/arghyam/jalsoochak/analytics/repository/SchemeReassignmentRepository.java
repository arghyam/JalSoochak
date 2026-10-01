package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Moves a tenant's facts from one scheme to another — the placeholder lenient ingestion made, to the
 * real scheme the state sync matched it to. Every statement is scoped to the tenant and touches only
 * rows of {@code from}, so a repeat is a no-op.
 */
@Repository
@RequiredArgsConstructor
public class SchemeReassignmentRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Re-points every meter-reading fact, identified by {@code source_reading_id} or not — rows written
     * before V56 have none, which is why this does not go through a republish.
     *
     * @return the distinct reading dates that moved, ascending
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LocalDate> moveMeterReadings(int tenantId, int from, int to) {
        return jdbcTemplate.queryForList("""
                        WITH moved AS (
                            UPDATE analytics_schema.fact_meter_reading_table SET scheme_id = ?
                            WHERE tenant_id = ? AND scheme_id = ?
                            RETURNING reading_date)
                        SELECT DISTINCT reading_date FROM moved WHERE reading_date IS NOT NULL ORDER BY 1
                        """,
                LocalDate.class, to, tenantId, from);
    }

    /** Moves attendance; a day the operator already has on {@code to} keeps that row and drops the other. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveAttendance(int tenantId, int from, int to) {
        int moved = jdbcTemplate.update("""
                UPDATE analytics_schema.fact_operator_attendance_table a SET scheme_id = ?, updated_at = NOW()
                WHERE a.tenant_id = ? AND a.scheme_id = ?
                  AND NOT EXISTS (SELECT 1 FROM analytics_schema.fact_operator_attendance_table b
                                  WHERE b.tenant_id = a.tenant_id AND b.scheme_id = ?
                                    AND b.user_id = a.user_id AND b.date_key = a.date_key)
                """, to, tenantId, from, to);
        jdbcTemplate.update("DELETE FROM analytics_schema.fact_operator_attendance_table WHERE tenant_id = ? AND scheme_id = ?",
                tenantId, from);
        return moved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int moveAnomaliesAndEscalations(int tenantId, int from, int to) {
        return jdbcTemplate.update("UPDATE analytics_schema.fact_anomaly_table SET scheme_id = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND scheme_id = ?", to, tenantId, from)
                + jdbcTemplate.update("UPDATE analytics_schema.fact_escalation_table SET scheme_id = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND scheme_id = ?", to, tenantId, from);
    }

    /**
     * Drops what is left of {@code from}: its pre-aggregated days (the re-aggregation rebuilds
     * {@code to}'s) and its dim rows. The scheme has no facts left by now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dropScheme(int tenantId, int from) {
        jdbcTemplate.update("DELETE FROM analytics_schema.fact_scheme_daily_table WHERE tenant_id = ? AND scheme_id = ?",
                tenantId, from);
        jdbcTemplate.update("DELETE FROM analytics_schema.dim_scheme_table WHERE tenant_id = ? AND scheme_id = ?",
                tenantId, from);
    }
}
