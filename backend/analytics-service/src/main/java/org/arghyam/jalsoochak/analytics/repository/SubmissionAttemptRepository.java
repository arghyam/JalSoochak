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

    /**
     * Each value is bound once, with its type cast explicitly, so a null one still has a type. The state
     * id is text (alphanumeric for some states) matched trimmed and ignoring case, as scheme-service keeps
     * it unique; the centre id is an integer. The event's own tenant wins, then a state-id match.
     */
    private static final String RESOLVE_SCHEME_SQL = """
            WITH submitted AS (
                SELECT lower(CAST(? AS VARCHAR)) AS state_scheme_id,
                       CAST(? AS INT)            AS centre_scheme_id,
                       CAST(? AS INT)            AS tenant_id
            )
            SELECT s.scheme_id, s.tenant_id
            FROM analytics_schema.dim_scheme_table s
            CROSS JOIN submitted p
            WHERE lower(btrim(s.state_scheme_id)) = p.state_scheme_id
               OR s.centre_scheme_id = p.centre_scheme_id
            ORDER BY (CASE WHEN s.tenant_id = p.tenant_id THEN 0 ELSE 1 END),
                     (CASE WHEN lower(btrim(s.state_scheme_id)) = p.state_scheme_id THEN 0 ELSE 1 END),
                     s.scheme_id
            LIMIT 1
            """;

    /** Resolve a submitted gov scheme id (state or centre) to [scheme_id, tenant_id] via dim_scheme. */
    public Optional<int[]> resolveScheme(Integer preferredTenantId, String submittedStateSchemeId, String submittedCentreSchemeId) {
        String state = trimToNull(submittedStateSchemeId);
        Integer centre = parseInt(submittedCentreSchemeId);
        if (state == null && centre == null) {
            return Optional.empty();
        }
        List<int[]> rows = jdbcTemplate.query(
                RESOLVE_SCHEME_SQL,
                (rs, n) -> new int[]{rs.getInt("scheme_id"), rs.getInt("tenant_id")},
                state, centre, preferredTenantId);
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

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Integer parseInt(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
