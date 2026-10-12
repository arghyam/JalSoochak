package org.arghyam.jalsoochak.message.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A ledger row as it stands after a change: the fields the delivery-statistics feed carries. Holds no
 * recipient address, hash or contact reference, and no provider error text.
 */
public record LedgerSnapshot(String schema,
                             String uuid,
                             int statusVersion,
                             Long userId,
                             String userType,
                             String messageType,
                             Integer channelId,
                             String provider,
                             String dispatchStatus,
                             String failureStage,
                             String deliveryStatus,
                             String providerErrorCode,
                             Integer latencyMs,
                             LocalDate subjectDate,
                             BigDecimal cost,
                             String costCurrency,
                             Instant createdAt,
                             Instant dispatchedAt,
                             Instant deliveredAt,
                             Instant readAt,
                             Instant settledAt) {
}
