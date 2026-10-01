package org.arghyam.jalsoochak.scheme.statesync.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code common_schema.state_sync_run_table} / {@code state_sync_issue_table} (V60).
 *
 * <p><b>The run row is the cross-pod lock.</b> {@link #claim} inserts a RUNNING row, and the partial
 * unique index {@code uq_state_sync_run_one_running_per_tenant} lets exactly one such row exist per
 * tenant, so of several replicas firing the same cron only one gets an id back. Every method here
 * runs in its own auto-committed statement — never inside the run's data transaction — so a claim
 * is visible to other pods at once and survives the data transaction rolling back.
 */
@Repository
public class StateSyncRunRepository {

    public record Tenant(int id, String stateCode, String schemaName) {
    }

    public record RunRow(long id, String runKind, String mode, String status, String triggeredBy,
                         LocalDateTime startedAt, LocalDateTime heartbeatAt, LocalDateTime finishedAt,
                         LocalDateTime sourceWatermark, String counts, String error) {
    }

    public record IssueRow(long id, long runId, String entity, String upstreamCode, String category,
                           String detail, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StateSyncRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<Tenant> findTenant(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, state_code FROM common_schema.tenant_master_table
                        WHERE lower(state_code) = lower(?) AND deleted_at IS NULL
                        """,
                (rs, n) -> new Tenant(rs.getInt("id"), rs.getString("state_code"),
                        "tenant_" + rs.getString("state_code").trim().toLowerCase(Locale.ROOT)),
                stateCode.trim()).stream().findFirst();
    }

    /**
     * Takes the tenant's run lock. First retires a RUNNING row whose heartbeat is older than
     * {@code staleAfter} (its pod died mid-run), then inserts this run's row; the unique index turns a
     * lost race into "no row inserted".
     *
     * @return the new run id, or empty when another run holds the lock
     */
    public Optional<Long> claim(int tenantId, RunKind kind, StateSyncProperties.Mode mode,
                                String triggeredBy, String owner, Duration staleAfter) {
        jdbcTemplate.update("""
                        UPDATE common_schema.state_sync_run_table
                        SET status = 'ABANDONED', finished_at = NOW(),
                            error = 'claim taken over: no heartbeat since ' || heartbeat_at
                        WHERE tenant_id = ? AND status = 'RUNNING'
                          AND heartbeat_at < NOW() - make_interval(secs => ?)
                        """,
                tenantId, staleAfter.toSeconds());
        List<Long> ids = jdbcTemplate.query("""
                        INSERT INTO common_schema.state_sync_run_table
                            (tenant_id, run_kind, mode, status, triggered_by, owner)
                        VALUES (?, ?, ?, 'RUNNING', ?, ?)
                        ON CONFLICT (tenant_id) WHERE status = 'RUNNING' DO NOTHING
                        RETURNING id
                        """,
                (rs, n) -> rs.getLong(1),
                tenantId, kind.name(), mode.name(), triggeredBy, owner);
        return ids.stream().findFirst();
    }

    public void heartbeat(long runId) {
        jdbcTemplate.update("""
                UPDATE common_schema.state_sync_run_table SET heartbeat_at = NOW()
                WHERE id = ? AND status = 'RUNNING'
                """, runId);
    }

    /** Closes the run. A run already marked ABANDONED by another pod keeps that status. */
    public void finish(long runId, boolean succeeded, SyncReport report, String error) {
        jdbcTemplate.update("""
                        UPDATE common_schema.state_sync_run_table
                        SET status = ?, finished_at = NOW(), heartbeat_at = NOW(),
                            counts = ?::jsonb, source_watermark = ?, error = ?
                        WHERE id = ? AND status = 'RUNNING'
                        """,
                succeeded ? "SUCCEEDED" : "FAILED",
                json(report.counts()),
                report.sourceWatermark() == null ? null : Timestamp.valueOf(report.sourceWatermark()),
                error == null ? null : truncate(error, 4000),
                runId);
    }

    public void insertIssues(long runId, int tenantId, List<SyncIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(issues.size());
        for (SyncIssue issue : issues) {
            args.add(new Object[]{runId, tenantId, issue.entity(), issue.upstreamCode(), issue.category(),
                    json(issue.detail())});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO common_schema.state_sync_issue_table
                    (run_id, tenant_id, entity, upstream_code, category, detail)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, args);
    }

    /** Newest upstream {@code updated_at} a successful APPLY run recorded — where the next delta starts. */
    public Optional<LocalDateTime> lastAppliedWatermark(int tenantId) {
        Timestamp ts = jdbcTemplate.queryForObject("""
                SELECT MAX(source_watermark) FROM common_schema.state_sync_run_table
                WHERE tenant_id = ? AND mode = 'APPLY' AND status = 'SUCCEEDED'
                  AND run_kind IN ('FULL', 'DELTA')
                """, Timestamp.class, tenantId);
        return Optional.ofNullable(ts).map(Timestamp::toLocalDateTime);
    }

    public List<RunRow> listRuns(int tenantId, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, run_kind, mode, status, triggered_by, started_at, heartbeat_at,
                               finished_at, source_watermark, counts::text AS counts, error
                        FROM common_schema.state_sync_run_table
                        WHERE tenant_id = ? ORDER BY started_at DESC, id DESC LIMIT ?
                        """,
                (rs, n) -> new RunRow(rs.getLong("id"), rs.getString("run_kind"), rs.getString("mode"),
                        rs.getString("status"), rs.getString("triggered_by"),
                        toLocal(rs.getTimestamp("started_at")), toLocal(rs.getTimestamp("heartbeat_at")),
                        toLocal(rs.getTimestamp("finished_at")), toLocal(rs.getTimestamp("source_watermark")),
                        rs.getString("counts"), rs.getString("error")),
                tenantId, limit);
    }

    public List<IssueRow> listIssues(int tenantId, Long runId, String category, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, run_id, entity, upstream_code, category, detail::text AS detail, created_at
                FROM common_schema.state_sync_issue_table WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (runId != null) {
            sql.append(" AND run_id = ?");
            args.add(runId);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(),
                (rs, n) -> new IssueRow(rs.getLong("id"), rs.getLong("run_id"), rs.getString("entity"),
                        rs.getString("upstream_code"), rs.getString("category"), rs.getString("detail"),
                        toLocal(rs.getTimestamp("created_at"))),
                args.toArray());
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise state-sync bookkeeping", e);
        }
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
