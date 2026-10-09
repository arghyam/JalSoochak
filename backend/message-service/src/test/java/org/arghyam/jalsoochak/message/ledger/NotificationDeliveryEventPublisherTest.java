package org.arghyam.jalsoochak.message.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The analytics feed: the exact contract analytics-service consumes, keyed so one notification's
 * snapshots stay in order, and carrying no address, hash, contact or provider error text.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final LedgerSnapshot SNAPSHOT = new LedgerSnapshot("tenant_mp", "uuid-1", 3, 11L,
            "SECTION_OFFICER", "DAILY_REPORT", 6, "wa-provider", "ACCEPTED", null, "READ", null, 412,
            LocalDate.of(2026, 10, 5), new BigDecimal("0.3"), "INR",
            Instant.parse("2026-10-05T10:30:00Z"), Instant.parse("2026-10-05T10:30:01Z"),
            Instant.parse("2026-10-05T10:31:00Z"), Instant.parse("2026-10-05T11:00:00Z"),
            Instant.parse("2026-10-05T10:31:00Z"));

    @Test
    void publishesTheSnapshotKeyedByNotification() throws Exception {
        new NotificationDeliveryEventPublisher(kafkaTemplate, mapper, true).publish(SNAPSHOT, 7);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("message-service-topic"), eq("uuid-1"), json.capture());
        JsonNode event = mapper.readTree(json.getValue());
        assertThat(event.path("eventType").asText()).isEqualTo("NOTIFICATION_DELIVERY_UPDATED");
        assertThat(event.path("notificationUuid").asText()).isEqualTo("uuid-1");
        assertThat(event.path("statusVersion").asInt()).isEqualTo(3);
        assertThat(event.path("tenantId").asInt()).isEqualTo(7);
        assertThat(event.path("channel").asText()).isEqualTo("WHATSAPP");
        assertThat(event.path("deliveryStatus").asText()).isEqualTo("READ");
        assertThat(event.path("createdAt").asText()).isEqualTo("2026-10-05T10:30:00Z");
        assertThat(event.path("subjectDate").asText()).isEqualTo("2026-10-05");
        assertThat(event.path("costAmount").decimalValue()).isEqualByComparingTo("0.3");
    }

    @Test
    void carriesNothingThatCouldIdentifyAnAddress() throws Exception {
        String json = mapper.writeValueAsString(NotificationDeliveryEventPublisher.toEvent(SNAPSHOT, 7));

        assertThat(json).doesNotContain("recipient").doesNotContain("contact").doesNotContain("hash")
                .doesNotContain("errorMessage").doesNotContain("phone").doesNotContain("email");
    }

    @Test
    void aBrokenBrokerNeverReachesTheLedger() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> new NotificationDeliveryEventPublisher(kafkaTemplate, mapper, true).publish(SNAPSHOT, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void canBeSwitchedOff() {
        new NotificationDeliveryEventPublisher(kafkaTemplate, mapper, false).publish(SNAPSHOT, 7);

        verifyNoInteractions(kafkaTemplate);
    }
}
