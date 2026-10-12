package org.arghyam.jalsoochak.analytics.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Rebuilds the V59 notification rollups (agg_notification_delivery_daily_table,
 * agg_notification_failure_daily_table) from fact_notification_delivery_table for a window of
 * dispatch days.
 *
 * <p>Every count is recomputed from the facts and overwrites the stored one, never added to it, so
 * re-running a window is idempotent and absorbs late status updates. tenant_id and user_type are
 * nullable keys; GROUP BY puts NULLs together and the tables' UNIQUE NULLS NOT DISTINCT matches
 * that, so a platform-level group is one row however often it is rebuilt.</p>
 */
@Repository
public class NotificationDeliveryAggregationRepository {

    /**
     * Postgres advisory-lock key that serialises the notification rollups across analytics-service
     * pods. Arbitrary but fixed, and apart from {@link AggregationRepository#AGGREGATION_LOCK_KEY}.
     */
    public static final long NOTIFICATION_AGGREGATION_LOCK_KEY = 7_042_026_002L;

    /** Dispatch statuses that mean the send itself failed or could not be confirmed. */
    private static final String DISPATCH_FAILED_STATUSES =
            "('PROVIDER_REJECTED', 'FAILED_DELIVERY', 'FAILED_GENERATION', 'FAILED_UPLOAD', 'DELIVERY_UNCONFIRMED')";

    /** A notification that failed: at the provider after sending, or before/while sending. */
    private static final String FAILED_PREDICATE =
            "(delivery_status = 'FAILED' OR dispatch_status IN " + DISPATCH_FAILED_STATUSES + ")";

    private static final String UPSERT_DELIVERY_DAILY_SQL = """
            INSERT INTO analytics_schema.agg_notification_delivery_daily_table
                (tenant_id, stat_date, message_type, channel, provider, user_type,
                 attempted, accepted, suppressed, skipped, dispatch_failed,
                 delivered, read, delivery_failed, pending, unresolved, not_tracked,
                 account_level_failures, distinct_users, avg_latency_ms, max_latency_ms,
                 avg_time_to_deliver_s, total_cost, cost_currency, created_at, updated_at)
            SELECT tenant_id, dispatch_date, message_type, channel, provider, user_type,
                   COUNT(*),
                   COUNT(*) FILTER (WHERE dispatch_status = 'ACCEPTED'),
                   COUNT(*) FILTER (WHERE dispatch_status = 'SUPPRESSED'),
                   COUNT(*) FILTER (WHERE dispatch_status LIKE 'SKIPPED%%'),
                   COUNT(*) FILTER (WHERE dispatch_status IN %1$s),
                   COUNT(*) FILTER (WHERE delivery_status IN ('DELIVERED', 'READ')),
                   COUNT(*) FILTER (WHERE delivery_status = 'READ'),
                   COUNT(*) FILTER (WHERE delivery_status = 'FAILED'),
                   COUNT(*) FILTER (WHERE delivery_status = 'PENDING'),
                   COUNT(*) FILTER (WHERE delivery_status = 'UNRESOLVED'),
                   COUNT(*) FILTER (WHERE delivery_status = 'NOT_TRACKED'),
                   COUNT(*) FILTER (WHERE %2$s
                                      AND provider_error_code = ANY (string_to_array(?, ','))),
                   COUNT(DISTINCT user_id),
                   ROUND(AVG(latency_ms))::INTEGER,
                   MAX(latency_ms),
                   ROUND(AVG(time_to_deliver_s))::INTEGER,
                   SUM(cost_amount),
                   MAX(cost_currency),
                   NOW(), NOW()
            FROM analytics_schema.fact_notification_delivery_table
            WHERE dispatch_date BETWEEN ? AND ?
            GROUP BY tenant_id, dispatch_date, message_type, channel, provider, user_type
            ON CONFLICT (tenant_id, stat_date, message_type, channel, provider, user_type) DO UPDATE SET
                attempted = EXCLUDED.attempted,
                accepted = EXCLUDED.accepted,
                suppressed = EXCLUDED.suppressed,
                skipped = EXCLUDED.skipped,
                dispatch_failed = EXCLUDED.dispatch_failed,
                delivered = EXCLUDED.delivered,
                read = EXCLUDED.read,
                delivery_failed = EXCLUDED.delivery_failed,
                pending = EXCLUDED.pending,
                unresolved = EXCLUDED.unresolved,
                not_tracked = EXCLUDED.not_tracked,
                account_level_failures = EXCLUDED.account_level_failures,
                distinct_users = EXCLUDED.distinct_users,
                avg_latency_ms = EXCLUDED.avg_latency_ms,
                max_latency_ms = EXCLUDED.max_latency_ms,
                avg_time_to_deliver_s = EXCLUDED.avg_time_to_deliver_s,
                total_cost = EXCLUDED.total_cost,
                cost_currency = EXCLUDED.cost_currency,
                updated_at = NOW()
            """.formatted(DISPATCH_FAILED_STATUSES, FAILED_PREDICATE);

    private static final String UPSERT_FAILURE_DAILY_SQL = """
            INSERT INTO analytics_schema.agg_notification_failure_daily_table
                (tenant_id, stat_date, message_type, channel, provider, failure_stage, provider_error_code,
                 failures, created_at, updated_at)
            SELECT tenant_id, dispatch_date, message_type, channel, provider, failure_stage, provider_error_code,
                   COUNT(*), NOW(), NOW()
            FROM analytics_schema.fact_notification_delivery_table
            WHERE dispatch_date BETWEEN ? AND ?
              AND %s
            GROUP BY tenant_id, dispatch_date, message_type, channel, provider, failure_stage, provider_error_code
            ON CONFLICT (tenant_id, stat_date, message_type, channel, provider, failure_stage, provider_error_code)
            DO UPDATE SET
                failures = EXCLUDED.failures,
                updated_at = NOW()
            """.formatted(FAILED_PREDICATE);

    /*
     * An upsert alone would leave a group behind once no fact belongs to it any more (a send that
     * was unconfirmed and later confirmed leaves its failure group), so the window's vanished
     * groups are deleted after it.
     */
    private static final String DELETE_VANISHED_DELIVERY_DAILY_SQL = """
            DELETE FROM analytics_schema.agg_notification_delivery_daily_table a
            WHERE a.stat_date BETWEEN ? AND ?
              AND NOT EXISTS (
                  SELECT 1 FROM analytics_schema.fact_notification_delivery_table f
                  WHERE f.dispatch_date = a.stat_date
                    AND f.tenant_id IS NOT DISTINCT FROM a.tenant_id
                    AND f.message_type = a.message_type
                    AND f.channel = a.channel
                    AND f.provider = a.provider
                    AND f.user_type IS NOT DISTINCT FROM a.user_type)
            """;

    private static final String DELETE_VANISHED_FAILURE_DAILY_SQL = """
            DELETE FROM analytics_schema.agg_notification_failure_daily_table a
            WHERE a.stat_date BETWEEN ? AND ?
              AND NOT EXISTS (
                  SELECT 1 FROM analytics_schema.fact_notification_delivery_table f
                  WHERE f.dispatch_date = a.stat_date
                    AND f.tenant_id IS NOT DISTINCT FROM a.tenant_id
                    AND f.message_type = a.message_type
                    AND f.channel = a.channel
                    AND f.provider = a.provider
                    AND f.failure_stage IS NOT DISTINCT FROM a.failure_stage
                    AND f.provider_error_code IS NOT DISTINCT FROM a.provider_error_code
                    AND (f.delivery_status = 'FAILED' OR f.dispatch_status IN %s))
            """.formatted(DISPATCH_FAILED_STATUSES);

    private final JdbcTemplate jdbcTemplate;
    /** Comma-separated provider error codes that mean the sending account, not the recipient, failed. */
    private final String accountLevelErrorCodesCsv;

    public NotificationDeliveryAggregationRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${analytics.notification-delivery.account-level-error-codes:9999}") String accountLevelErrorCodes) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountLevelErrorCodesCsv = Arrays.stream(accountLevelErrorCodes.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.joining(","));
    }

    /**
     * Takes the notification-rollup lock for the current transaction if it is free, without waiting.
     * {@code false} means another pod is rebuilding right now.
     */
    public boolean tryLock() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, NOTIFICATION_AGGREGATION_LOCK_KEY));
    }

    public int upsertDeliveryDaily(LocalDate from, LocalDate to) {
        return jdbcTemplate.update(UPSERT_DELIVERY_DAILY_SQL, accountLevelErrorCodesCsv, from, to);
    }

    public int deleteVanishedDeliveryDaily(LocalDate from, LocalDate to) {
        return jdbcTemplate.update(DELETE_VANISHED_DELIVERY_DAILY_SQL, from, to);
    }

    public int upsertFailureDaily(LocalDate from, LocalDate to) {
        return jdbcTemplate.update(UPSERT_FAILURE_DAILY_SQL, from, to);
    }

    public int deleteVanishedFailureDaily(LocalDate from, LocalDate to) {
        return jdbcTemplate.update(DELETE_VANISHED_FAILURE_DAILY_SQL, from, to);
    }
}
