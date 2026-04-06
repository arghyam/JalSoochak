package org.arghyam.jalsoochak.tenant.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaProducer}.
 *
 * <p>Verifies JSON serialization, correct topic routing, and that serialization
 * failures are wrapped and re-thrown.</p>
 */
@ExtendWith(MockitoExtension.class)
class KafkaProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private KafkaProducer kafkaProducer;

    // ── sendMessage ───────────────────────────────────────────────────────────────

    @Test
    void sendMessage_publishesToDefaultTopic() {
        kafkaProducer.sendMessage("hello");

        verify(kafkaTemplate).send("tenant-service-topic", "hello");
    }

    @Test
    void sendMessage_publishesRawStringWithoutModification() {
        String payload = "{\"key\":\"value\"}";

        kafkaProducer.sendMessage(payload);

        verify(kafkaTemplate).send("tenant-service-topic", payload);
        verifyNoInteractions(objectMapper);
    }

    // ── publishJson ───────────────────────────────────────────────────────────────

    @Test
    void publishJson_serializesEventAndSendsToGivenTopic() throws JsonProcessingException {
        Map<String, Object> event = Map.of("eventType", "TENANT_CREATED", "stateCode", "mp");
        when(objectMapper.writeValueAsString(event)).thenReturn("{\"eventType\":\"TENANT_CREATED\",\"stateCode\":\"mp\"}");

        kafkaProducer.publishJson("tenant-service-topic", event);

        verify(objectMapper).writeValueAsString(event);
        verify(kafkaTemplate).send(eq("tenant-service-topic"),
                eq("{\"eventType\":\"TENANT_CREATED\",\"stateCode\":\"mp\"}"));
    }

    @Test
    void publishJson_sendsToCustomTopic_notDefaultTopic() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        kafkaProducer.publishJson("some-other-topic", Map.of());

        verify(kafkaTemplate).send(eq("some-other-topic"), anyString());
        verify(kafkaTemplate, never()).send(eq("tenant-service-topic"), anyString());
    }

    @Test
    void publishJson_throwsRuntimeException_whenSerializationFails() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("bad json") {});

        assertThatThrownBy(() -> kafkaProducer.publishJson("any-topic", Map.of("bad", new Object())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to serialize Kafka event");

        verify(kafkaTemplate, never()).send(anyString(), anyString());
    }
}
