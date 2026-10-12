package org.arghyam.jalsoochak.message.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes a {@link NotificationDeliveryUpdatedEvent} for each ledger change.
 *
 * <p>Keyed by the notification's uuid, so every snapshot of one notification lands on one partition
 * in order. Sent directly rather than through {@code KafkaProducer.publishJson}, which logs every
 * payload at INFO — at one event per message that would double the service's log volume.</p>
 *
 * <p>Never throws: the ledger row is the source of truth and a lost event costs only a stale statistic,
 * which the next change to that row corrects.</p>
 */
@Component
@Slf4j
public class NotificationDeliveryEventPublisher {

    static final String TOPIC = "message-service-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public NotificationDeliveryEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                              ObjectMapper objectMapper,
                                              @Value("${notifications.ledger.events.enabled:true}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public void publish(LedgerSnapshot snapshot, Integer tenantId) {
        if (!enabled || snapshot == null) {
            return;
        }
        try {
            NotificationDeliveryUpdatedEvent event = toEvent(snapshot, tenantId);
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, snapshot.uuid(), json);
            log.debug("[Ledger] published {} uuid={} version={} delivery={}",
                    NotificationDeliveryUpdatedEvent.EVENT_TYPE, snapshot.uuid(), snapshot.statusVersion(),
                    snapshot.deliveryStatus());
        } catch (Exception e) {
            log.warn("[Ledger] could not publish the delivery event for uuid={}: {}", snapshot.uuid(), e.getMessage());
        }
    }

    static NotificationDeliveryUpdatedEvent toEvent(LedgerSnapshot s, Integer tenantId) {
        LedgerChannel channel = s.channelId() == null ? null : LedgerChannel.fromId(s.channelId());
        return new NotificationDeliveryUpdatedEvent(
                NotificationDeliveryUpdatedEvent.EVENT_TYPE,
                s.uuid(),
                s.statusVersion(),
                tenantId,
                s.messageType(),
                channel == null ? null : channel.name(),
                s.provider(),
                s.userId(),
                s.userType(),
                s.dispatchStatus(),
                s.failureStage(),
                s.deliveryStatus(),
                s.providerErrorCode(),
                iso(s.createdAt()),
                iso(s.dispatchedAt()),
                iso(s.deliveredAt()),
                iso(s.readAt()),
                iso(s.settledAt()),
                s.latencyMs(),
                s.subjectDate() == null ? null : s.subjectDate().toString(),
                s.cost(),
                s.costCurrency());
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
