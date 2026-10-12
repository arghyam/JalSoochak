package org.arghyam.jalsoochak.tenant.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.arghyam.jalsoochak.tenant.enums.ScheduledJobType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Repository for {@code common_schema.scheduled_job_run_table}: the record that lets exactly one
 * tenant-service pod run each notification job once per period.
 *
 * <p>Every method commits in a transaction of its own ({@code REQUIRES_NEW}). A claim must be
 * visible to the other pods before its job starts, and must survive that job failing.</p>
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ScheduledJobRunRepository {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Claims the run of {@code jobType} for {@code tenantId} in {@code periodKey}.
     *
     * @return the new row's id when this call won the claim; empty when the run was already claimed,
     *         by this pod or another, in which case the caller must not run the job
     */
    public Optional<Long> claim(ScheduledJobType jobType, int tenantId, LocalDate periodKey,
            LocalDateTime dueAtIst, String claimedBy) {
        return jdbcTemplate.query("""
                        INSERT INTO common_schema.scheduled_job_run_table
                            (job_type, tenant_id, period_key, due_at_ist, claimed_by)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (job_type, tenant_id, period_key) DO NOTHING
                        RETURNING id
                        """,
                        (rs, rowNum) -> rs.getLong("id"),
                        jobType.name(), tenantId, periodKey, dueAtIst, claimedBy)
                .stream()
                .findFirst();
    }

    public void markSucceeded(long id) {
        finish(id, STATUS_SUCCEEDED, null);
    }

    public void markFailed(long id, String errorMessage) {
        finish(id, STATUS_FAILED, errorMessage);
    }

    private void finish(long id, String status, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE common_schema.scheduled_job_run_table
                SET status = ?, finished_at = NOW(), error_message = ?
                WHERE id = ?
                """, status, errorMessage, id);
    }
}
