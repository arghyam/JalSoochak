package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.event.NotificationDeliveryEvent;
import org.arghyam.jalsoochak.analytics.exception.MalformedEventException;
import org.arghyam.jalsoochak.analytics.repository.FactNotificationDeliveryRepository;
import org.arghyam.jalsoochak.analytics.repository.FactNotificationDeliveryRepository.NotificationDeliveryFact;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Stores NOTIFICATION_DELIVERY_UPDATED events in fact_notification_delivery_table. The event's UTC
 * instants are kept on the IST wall clock, and the IST day the notification belongs to
 * ({@code dispatch_date}) is fixed here so the daily rollups group on a stored column.
 *
 * <p>An event missing a field the row cannot do without, or carrying an unparseable time, throws
 * {@link MalformedEventException}: it goes straight to the dead-letter topic, as retrying cannot fix
 * it and a guessed time would put the notification on the wrong day.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryIngestionService {

    /** Reporting days are Indian calendar days, as for readings (FactServiceImpl). */
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /** Stored when the event names no provider (e.g. nothing was sent), so the row still counts. */
    static final String UNKNOWN_PROVIDER = "unknown";

    private final FactNotificationDeliveryRepository repository;

    public void ingest(NotificationDeliveryEvent event) {
        String uuid = required(event.getNotificationUuid(), "notificationUuid", event);
        if (event.getStatusVersion() == null) {
            throw malformed("statusVersion is missing", event);
        }
        LocalDateTime createdAt = parseInstant(required(event.getCreatedAt(), "createdAt", event), "createdAt", event);
        LocalDateTime dispatchedAt = parseInstant(event.getDispatchedAt(), "dispatchedAt", event);
        LocalDateTime deliveredAt = parseInstant(event.getDeliveredAt(), "deliveredAt", event);
        LocalDateTime readAt = parseInstant(event.getReadAt(), "readAt", event);

        NotificationDeliveryFact fact = NotificationDeliveryFact.builder()
                .notificationUuid(uuid)
                .statusVersion(event.getStatusVersion())
                .tenantId(event.getTenantId())
                .messageType(required(event.getMessageType(), "messageType", event))
                .channel(required(event.getChannel(), "channel", event))
                .provider(isBlank(event.getProvider()) ? UNKNOWN_PROVIDER : event.getProvider())
                .userId(event.getUserId())
                .userType(event.getUserType())
                .dispatchStatus(required(event.getDispatchStatus(), "dispatchStatus", event))
                .failureStage(event.getFailureStage())
                .deliveryStatus(required(event.getDeliveryStatus(), "deliveryStatus", event))
                .providerErrorCode(event.getProviderErrorCode())
                .createdAtSource(createdAt)
                .dispatchedAt(dispatchedAt)
                .deliveredAt(deliveredAt)
                .readAt(readAt)
                .settledAt(parseInstant(event.getSettledAt(), "settledAt", event))
                .dispatchDate((dispatchedAt != null ? dispatchedAt : createdAt).toLocalDate())
                .subjectDate(parseDate(event.getSubjectDate(), event))
                .latencyMs(event.getLatencyMs())
                .timeToDeliverS(timeToDeliverSeconds(dispatchedAt, deliveredAt != null ? deliveredAt : readAt))
                .costAmount(event.getCostAmount())
                .costCurrency(event.getCostCurrency())
                .build();

        if (repository.upsert(fact)) {
            log.debug("[analytics/NOTIFICATION_DELIVERY] stored notification={} version={} type={} dispatch={} delivery={}",
                    uuid, fact.statusVersion(), fact.messageType(), fact.dispatchStatus(), fact.deliveryStatus());
        } else {
            log.debug("[analytics/NOTIFICATION_DELIVERY] ignored notification={} version={}: not newer than stored",
                    uuid, fact.statusVersion());
        }
    }

    /**
     * Seconds from our dispatch to the provider's delivery (or read) receipt. Clamped at zero: the
     * receipt carries the provider's clock, which can run slightly behind ours.
     */
    private static Integer timeToDeliverSeconds(LocalDateTime dispatchedAt, LocalDateTime receivedAt) {
        if (dispatchedAt == null || receivedAt == null) {
            return null;
        }
        long seconds = Duration.between(dispatchedAt, receivedAt).getSeconds();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, seconds));
    }

    /** An ISO-8601 instant (or offset date-time) as the IST wall clock; null when absent. */
    private static LocalDateTime parseInstant(String value, String field, NotificationDeliveryEvent event) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(IST_ZONE).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw malformed("unparseable " + field + " '" + value + "'", event, e);
        }
    }

    private static LocalDate parseDate(String value, NotificationDeliveryEvent event) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw malformed("unparseable subjectDate '" + value + "'", event, e);
        }
    }

    private static String required(String value, String field, NotificationDeliveryEvent event) {
        if (isBlank(value)) {
            throw malformed(field + " is missing", event);
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static MalformedEventException malformed(String reason, NotificationDeliveryEvent event) {
        return malformed(reason, event, null);
    }

    private static MalformedEventException malformed(String reason, NotificationDeliveryEvent event, Throwable cause) {
        return new MalformedEventException("NOTIFICATION_DELIVERY_UPDATED notification="
                + event.getNotificationUuid() + ": " + reason, cause);
    }
}
