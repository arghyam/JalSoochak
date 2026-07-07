package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * REPORTED-METRIC: persists submissions rejected before any reading/anomaly was written, so the
 * "reported" scheme counts can include them. Resolves the submitted (gov) scheme id to our internal
 * scheme_id + tenant_id via dim_scheme when possible. Remove this class (and its callers) to revert.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class SubmissionAttemptRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Resolve a submitted gov scheme id (state or centre) to [scheme_id, tenant_id] via dim_scheme. */
    public Optional<int[]> resolveScheme(Integer preferredTenantId, String submittedStateSchemeId, String submittedCentreSchemeId) {
        Integer state = parseInt(submittedStateSchemeId);
        Integer centre = parseInt(submittedCentreSchemeId);
        if (state == null && centre == null) {
            return Optional.empty();
        }
        // Prefer the tenant from the event when present, else take the first match. state before centre.
        String sql = """
                SELECT scheme_id, tenant_id
                FROM analytics_schema.dim_scheme_table
                WHERE (? IS NOT NULL AND state_scheme_id = ?)
                   OR (? IS NOT NULL AND centre_scheme_id = ?)
                ORDER BY (CASE WHEN ? IS NOT NULL AND tenant_id = ? THEN 0 ELSE 1 END),
                         (CASE WHEN ? IS NOT NULL AND state_scheme_id = ? THEN 0 ELSE 1 END),
                         scheme_id
                LIMIT 1
                """;
        List<int[]> rows = jdbcTemplate.query(
                sql,
                (rs, n) -> new int[]{rs.getInt("scheme_id"), rs.getInt("tenant_id")},
                state, state, centre, centre, preferredTenantId, preferredTenantId, state, state);
        return rows.stream().findFirst();
    }

    public void insert(Integer tenantId,
                       Integer schemeId,
                       String submittedStateSchemeId,
                       String submittedCentreSchemeId,
                       String phoneHash,
                       String reason,
                       LocalDateTime attemptedAt) {
        String sql = """
                INSERT INTO analytics_schema.submission_attempt_table
                    (tenant_id, scheme_id, submitted_state_scheme_id, submitted_centre_scheme_id,
                     phone_hash, reason, attempted_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                """;
        jdbcTemplate.update(sql, tenantId, schemeId, submittedStateSchemeId, submittedCentreSchemeId,
                phoneHash, reason, attemptedAt);
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
