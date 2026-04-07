package org.arghyam.jalsoochak.message.kafka;

import org.arghyam.jalsoochak.message.service.NotificationEventRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaConsumer}.
 *
 * <p>Verifies that incoming Kafka messages are forwarded unchanged to
 * {@link NotificationEventRouter#route}, and that exceptions from the
 * router propagate naturally (no swallowing).</p>
 */
@ExtendWith(MockitoExtension.class)
class KafkaConsumerTest {

    @Mock
    private NotificationEventRouter notificationEventRouter;

    @InjectMocks
    private KafkaConsumer consumer;

    @Test
    void consume_delegatesMessageToRouter() {
        String message = "{\"eventType\":\"NUDGE\",\"recipientPhone\":\"919876543210\"}";

        consumer.consume(message);

        verify(notificationEventRouter).route(message);
    }

    @Test
    void consume_passesExactPayload_withoutModification() {
        String message = "{\"eventType\":\"ESCALATION\",\"officerPhone\":\"919000000001\"}";

        consumer.consume(message);

        verify(notificationEventRouter).route(message);
        verifyNoMoreInteractions(notificationEventRouter);
    }

    @Test
    void consume_propagatesRouterException_forKafkaRetry() {
        String message = "{\"eventType\":\"NUDGE\"}";
        doThrow(new RuntimeException("Router failed"))
                .when(notificationEventRouter).route(anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Router failed");
    }
}
