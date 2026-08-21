package org.arghyam.jalsoochak.message.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link KafkaProducer}.
 *
 * <p>Verifies correct topic routing, JSON serialisation, and exception
 * propagation when serialisation fails.</p>
 */
@ExtendWith(MockitoExtension.class)
class KafkaProducerTest {

    private static final String DEFAULT_TOPIC = "message-service-topic";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private KafkaProducer kafkaProducer;

    /** A broker acknowledgement, as {@code KafkaTemplate.send} would eventually complete it. */
    private static CompletableFuture<SendResult<String, String>> acknowledged(String topic) {
        RecordMetadata metadata =
                new RecordMetadata(new TopicPartition(topic, 0), 42L, 0, System.currentTimeMillis(), 0, 0);
        return CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>(topic, "{}"), metadata));
    }

    /** Stubs the template so a publish is acknowledged, the normal case for most tests here. */
    private void givenBrokerAcknowledges() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenAnswer(inv -> acknowledged(inv.getArgument(0, String.class)));
    }

    // ─────────────────────────── sendMessage ───────────────────────────────────

    @Test
    void sendMessage_publishesRawStringToDefaultTopic() {
        kafkaProducer.sendMessage("hello world");

        verify(kafkaTemplate).send(DEFAULT_TOPIC, "hello world");
    }

    @Test
    void sendMessage_publishesEmptyString_whenMessageIsEmpty() {
        kafkaProducer.sendMessage("");

        verify(kafkaTemplate).send(DEFAULT_TOPIC, "");
    }

    @Test
    void sendMessage_alwaysUsesHardcodedDefaultTopic_notTheCustomTopic() {
        kafkaProducer.sendMessage("some event payload");

        // Must go to message-service-topic, never to common-topic or other topics
        verify(kafkaTemplate).send(eq(DEFAULT_TOPIC), anyString());
        verify(kafkaTemplate, never()).send(eq("common-topic"), anyString());
    }

    // ─────────────────────────── publishJson ───────────────────────────────────

    @Test
    void publishJson_serialisesObjectAndPublishesToSpecifiedTopic() throws JsonProcessingException {
        Map<String, Object> event = Map.of("eventType", "TEST_EVENT", "id", 42);
        when(objectMapper.writeValueAsString(event)).thenReturn("{\"eventType\":\"TEST_EVENT\",\"id\":42}");
        givenBrokerAcknowledges();

        assertThat(kafkaProducer.publishJson("my-topic", event)).isTrue();

        verify(kafkaTemplate).send("my-topic", "{\"eventType\":\"TEST_EVENT\",\"id\":42}");
    }

    @Test
    void publishJson_canPublishToCommonTopic() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventType\":\"NUDGE\"}");
        givenBrokerAcknowledges();

        kafkaProducer.publishJson("common-topic", Map.of("eventType", "NUDGE"));

        verify(kafkaTemplate).send(eq("common-topic"), eq("{\"eventType\":\"NUDGE\"}"));
    }

    @Test
    void publishJson_canPublishToDltTopic() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"failureId\":\"abc\"}");
        givenBrokerAcknowledges();

        kafkaProducer.publishJson("welcome-message-dlt", Map.of("failureId", "abc"));

        verify(kafkaTemplate).send(eq("welcome-message-dlt"), anyString());
    }

    @Test
    void publishJson_publishesToDifferentTopicsIndependently() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        givenBrokerAcknowledges();

        kafkaProducer.publishJson("topic-a", Map.of());
        kafkaProducer.publishJson("topic-b", Map.of());

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(topicCaptor.capture(), anyString());
        assertThat(topicCaptor.getAllValues()).containsExactly("topic-a", "topic-b");
    }

    @Test
    void publishJson_serialisedJsonContentsMatchPayload() throws JsonProcessingException {
        // Use a real ObjectMapper here to verify the actual serialised content
        ObjectMapper realMapper = new ObjectMapper();
        KafkaProducer producerWithRealMapper = new KafkaProducer(kafkaTemplate, realMapper);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        givenBrokerAcknowledges();

        producerWithRealMapper.publishJson("any-topic",
                Map.of("eventType", "WHATSAPP_CONTACT_REGISTERED", "userId", 10));

        verify(kafkaTemplate).send(anyString(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("WHATSAPP_CONTACT_REGISTERED");
        assertThat(json).contains("10");
    }

    // ──────────────── broker acknowledgement (dead-letter accounting) ────────────────

    /**
     * The reason this method waits at all. {@code KafkaTemplate.send} returns once the record is in
     * the local batch buffer, so a broker-side rejection arrives later on the producer's I/O thread
     * and never reaches the caller's {@code catch}. The router took the silent return as proof the
     * record had landed and counted {@code outcome="deadlettered"} — reporting a recoverable park
     * for a notification that was actually lost.
     */
    @Test
    void publishJson_returnsFalse_whenTheBrokerRejectsTheRecordAsynchronously() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(
                new org.apache.kafka.common.errors.TimeoutException("Topic account-email-dlt not present in metadata"));
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(failed);

        assertThat(kafkaProducer.publishJson("account-email-dlt", Map.of())).isFalse();
    }

    @Test
    void publishJson_returnsFalse_whenTheProducerItselfThrows() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenThrow(new IllegalStateException("Producer is closed"));

        // A synchronous producer failure is still a failure to publish, not a reason to abort the
        // caller: the dead-letter paths must be able to count the drop and move on.
        assertThat(kafkaProducer.publishJson("welcome-message-dlt", Map.of())).isFalse();
    }

    // ──────────────────── serialisation failure propagation ────────────────────

    @Test
    void publishJson_throwsRuntimeException_whenSerialisationFails() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("Serialisation error") {});

        assertThatThrownBy(() -> kafkaProducer.publishJson("my-topic", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to serialize Kafka event");
    }

    @Test
    void publishJson_doesNotCallKafkaTemplate_whenSerialisationFails() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("Serialisation error") {});

        try {
            kafkaProducer.publishJson("my-topic", Map.of());
        } catch (RuntimeException ignored) {
            // expected
        }

        verify(kafkaTemplate, never()).send(anyString(), anyString());
    }

    /**
     * The dead-letter payloads this producer writes carry the recipient's email address
     * ({@code account-email-dlt}) and phone number ({@code welcome-message-dlt}). Neither may
     * appear in a log line above DEBUG.
     */
    @Test
    void redactSensitive_masksRecipientAddressOnDeadLetterPayloads() {
        String json = "{\"failureId\":\"abc\",\"eventType\":\"ACCOUNT_EMAIL_FAILED\","
                + "\"originalEventType\":\"SEND_INVITE_EMAIL\",\"to\":\"officer@tenant.in\"}";

        String redacted = KafkaProducer.redactSensitive(json);

        assertThat(redacted).doesNotContain("officer@tenant.in");
        assertThat(redacted).contains("ACCOUNT_EMAIL_FAILED").contains("SEND_INVITE_EMAIL").contains("abc");
    }

    @Test
    void redactSensitive_masksPhoneNumberOnWelcomeDeadLetterPayloads() {
        String json = "{\"retryId\":\"xyz\",\"phone\":\"919876543210\",\"tenantSchema\":\"tenant_mp\"}";

        String redacted = KafkaProducer.redactSensitive(json);

        assertThat(redacted).doesNotContain("919876543210").contains("tenant_mp");
    }

    @Test
    void redactSensitive_returnsNullForNull() {
        assertThat(KafkaProducer.redactSensitive(null)).isNull();
    }
}
