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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

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

        kafkaProducer.publishJson("my-topic", event);

        verify(kafkaTemplate).send("my-topic", "{\"eventType\":\"TEST_EVENT\",\"id\":42}");
    }

    @Test
    void publishJson_canPublishToCommonTopic() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventType\":\"NUDGE\"}");

        kafkaProducer.publishJson("common-topic", Map.of("eventType", "NUDGE"));

        verify(kafkaTemplate).send(eq("common-topic"), eq("{\"eventType\":\"NUDGE\"}"));
    }

    @Test
    void publishJson_canPublishToDltTopic() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"retryId\":\"abc\"}");

        kafkaProducer.publishJson("welcome-message-dlt", Map.of("retryId", "abc"));

        verify(kafkaTemplate).send(eq("welcome-message-dlt"), anyString());
    }

    @Test
    void publishJson_publishesToDifferentTopicsIndependently() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

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

        producerWithRealMapper.publishJson("any-topic",
                Map.of("eventType", "WHATSAPP_CONTACT_REGISTERED", "userId", 10));

        verify(kafkaTemplate).send(anyString(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("WHATSAPP_CONTACT_REGISTERED");
        assertThat(json).contains("10");
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
}
