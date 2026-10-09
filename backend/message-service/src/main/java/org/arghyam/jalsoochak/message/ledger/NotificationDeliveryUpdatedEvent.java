package org.arghyam.jalsoochak.message.ledger;

import java.math.BigDecimal;

/**
 * The delivery-statistics feed: a full snapshot of one ledger row after each change, published to
 * {@code message-service-topic} and consumed by analytics-service.
 *
 * <p>A snapshot rather than a delta, so the consumer can upsert it and keep whichever has the higher
 * {@code statusVersion} — redelivered and out-of-order events are then harmless. Instants are ISO-8601
 * in UTC; {@code subjectDate} is {@code yyyy-MM-dd}.</p>
 *
 * <p>No recipient address, hash or provider contact, and no provider error text: this event is logged
 * and replicated, and a provider's failure text can echo an address. The error code is enough to
 * count on; the full text stays in the ledger.</p>
 */
public record NotificationDeliveryUpdatedEvent(
        String eventType,
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
        String createdAt,
        String dispatchedAt,
        String deliveredAt,
        String readAt,
        String settledAt,
        Integer latencyMs,
        String subjectDate,
        BigDecimal costAmount,
        String costCurrency) {

    public static final String EVENT_TYPE = "NOTIFICATION_DELIVERY_UPDATED";
}
