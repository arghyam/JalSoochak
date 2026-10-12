package org.arghyam.jalsoochak.analytics.repository;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Writes fact_notification_delivery_table, one row per notification (V59).
 */
@Repository
@RequiredArgsConstructor
public class FactNotificationDeliveryRepository {

    /**
     * Only a strictly higher {@code status_version} overwrites the stored row, so a redelivered
     * event (same version) and an out-of-order one (lower version) change nothing; the update then
     * affects no row, which is how {@link #upsert} recognises them.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO analytics_schema.fact_notification_delivery_table
                (notification_uuid, status_version, tenant_id, message_type, channel, provider, user_id,
                 user_type, dispatch_status, failure_stage, delivery_status, provider_error_code,
                 created_at_source, dispatched_at, delivered_at, read_at, settled_at, dispatch_date,
                 subject_date, latency_ms, time_to_deliver_s, cost_amount, cost_currency,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (notification_uuid) DO UPDATE SET
                status_version = EXCLUDED.status_version,
                tenant_id = EXCLUDED.tenant_id,
                message_type = EXCLUDED.message_type,
                channel = EXCLUDED.channel,
                provider = EXCLUDED.provider,
                user_id = EXCLUDED.user_id,
                user_type = EXCLUDED.user_type,
                dispatch_status = EXCLUDED.dispatch_status,
                failure_stage = EXCLUDED.failure_stage,
                delivery_status = EXCLUDED.delivery_status,
                provider_error_code = EXCLUDED.provider_error_code,
                created_at_source = EXCLUDED.created_at_source,
                dispatched_at = EXCLUDED.dispatched_at,
                delivered_at = EXCLUDED.delivered_at,
                read_at = EXCLUDED.read_at,
                settled_at = EXCLUDED.settled_at,
                dispatch_date = EXCLUDED.dispatch_date,
                subject_date = EXCLUDED.subject_date,
                latency_ms = EXCLUDED.latency_ms,
                time_to_deliver_s = EXCLUDED.time_to_deliver_s,
                cost_amount = EXCLUDED.cost_amount,
                cost_currency = EXCLUDED.cost_currency,
                updated_at = NOW()
            WHERE fact_notification_delivery_table.status_version < EXCLUDED.status_version
            """;

    private final JdbcTemplate jdbcTemplate;

    /** One fact row. Timestamps are IST wall clock; {@code dispatchDate} is already derived. */
    @Builder
    public record NotificationDeliveryFact(
            String notificationUuid,
            int statusVersion,
            Integer tenantId,
            String messageType,
            String channel,
            String provider,
            Long userId,
            String userType,
            String dispatchStatus,
            String failureStage,
            String deliveryStatus,
            String providerErrorCode,
            LocalDateTime createdAtSource,
            LocalDateTime dispatchedAt,
            LocalDateTime deliveredAt,
            LocalDateTime readAt,
            LocalDateTime settledAt,
            LocalDate dispatchDate,
            LocalDate subjectDate,
            Integer latencyMs,
            Integer timeToDeliverS,
            BigDecimal costAmount,
            String costCurrency) {
    }

    /**
     * Inserts the notification, or overwrites it when {@code fact} carries a higher status version.
     *
     * @return {@code false} when the stored row's version was the same or higher and nothing changed
     */
    public boolean upsert(NotificationDeliveryFact fact) {
        int rows = jdbcTemplate.update(UPSERT_SQL,
                fact.notificationUuid(), fact.statusVersion(), fact.tenantId(), fact.messageType(),
                fact.channel(), fact.provider(), fact.userId(), fact.userType(), fact.dispatchStatus(),
                fact.failureStage(), fact.deliveryStatus(), fact.providerErrorCode(),
                fact.createdAtSource(), fact.dispatchedAt(), fact.deliveredAt(), fact.readAt(),
                fact.settledAt(), fact.dispatchDate(), fact.subjectDate(), fact.latencyMs(),
                fact.timeToDeliverS(), fact.costAmount(), fact.costCurrency());
        return rows > 0;
    }
}
