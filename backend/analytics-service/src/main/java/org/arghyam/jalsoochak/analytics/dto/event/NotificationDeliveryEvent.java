package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * NOTIFICATION_DELIVERY_UPDATED from message-service-topic: the current state of one notification
 * in message-service's ledger. Sent on every status change; {@code statusVersion} orders them.
 * Instants are ISO-8601 UTC strings, {@code subjectDate} is {@code yyyy-MM-dd}. Carries no PII.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationDeliveryEvent {

    private String eventType;
    private String notificationUuid;
    private Integer statusVersion;
    private Integer tenantId;
    private String messageType;
    private String channel;
    private String provider;
    private Long userId;
    private String userType;
    private String dispatchStatus;
    private String failureStage;
    private String deliveryStatus;
    private String providerErrorCode;
    private String createdAt;
    private String dispatchedAt;
    private String deliveredAt;
    private String readAt;
    private String settledAt;
    private Integer latencyMs;
    private String subjectDate;
    private BigDecimal costAmount;
    private String costCurrency;
}
