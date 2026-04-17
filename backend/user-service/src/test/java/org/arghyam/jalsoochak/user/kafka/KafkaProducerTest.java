package org.arghyam.jalsoochak.user.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaProducer")
class KafkaProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private KafkaProducer producer;

    // ── sendMessage ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendMessage")
    class SendMessage {

        @Test
        @DisplayName("delegates to kafkaTemplate on the fixed topic")
        void delegatesToKafkaTemplate() {
            producer.sendMessage("hello");
            verify(kafkaTemplate).send("user-service-topic", "hello");
        }
    }

    // ── publishJson ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("publishJson")
    class PublishJson {

        @Test
        @DisplayName("returns true and logs OK when send succeeds with metadata")
        void returnsTrueWhenSendSucceeds() throws JsonProcessingException {
            when(objectMapper.writeValueAsString(anyString())).thenReturn("{\"key\":\"value\"}");

            @SuppressWarnings("unchecked")
            SendResult<String, String> sendResult = mock(SendResult.class);
            RecordMetadata meta = new RecordMetadata(new TopicPartition("topic", 0), 0L, 0, 0L, 0, 0);
            when(sendResult.getRecordMetadata()).thenReturn(meta);
            when(kafkaTemplate.send(anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(sendResult));

            assertThat(producer.publishJson("topic", "payload")).isTrue();
        }

        @Test
        @DisplayName("returns true and logs OK with metadata-unavailable fallback when getRecordMetadata throws")
        void returnsTrueWhenMetadataUnavailable() throws JsonProcessingException {
            when(objectMapper.writeValueAsString(anyString())).thenReturn("{}");

            // Completing with null causes NPE on res.getRecordMetadata(), exercising the inner catch
            when(kafkaTemplate.send(anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            assertThat(producer.publishJson("topic", "payload")).isTrue();
        }

        @Test
        @DisplayName("returns true and logs error when future completes exceptionally")
        void returnsTrueWhenFutureCompletesExceptionally() throws JsonProcessingException {
            when(objectMapper.writeValueAsString(anyString())).thenReturn("{}");
            when(kafkaTemplate.send(anyString(), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

            assertThat(producer.publishJson("topic", "payload")).isTrue();
        }

        @Test
        @DisplayName("returns false when JSON serialization fails")
        void returnsFalseWhenSerializationFails() throws JsonProcessingException {
            when(objectMapper.writeValueAsString(anyString()))
                    .thenThrow(mock(JsonProcessingException.class));

            assertThat(producer.publishJson("topic", "payload")).isFalse();
        }

        @Test
        @DisplayName("returns false when kafkaTemplate.send throws unexpectedly")
        void returnsFalseWhenKafkaSendThrows() throws JsonProcessingException {
            when(objectMapper.writeValueAsString(anyString())).thenReturn("{}");
            when(kafkaTemplate.send(anyString(), anyString()))
                    .thenThrow(new RuntimeException("kafka down"));

            assertThat(producer.publishJson("topic", "payload")).isFalse();
        }
    }
}
