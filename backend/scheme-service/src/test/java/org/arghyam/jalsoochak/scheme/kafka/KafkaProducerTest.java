package org.arghyam.jalsoochak.scheme.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaProducerTest {

    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    ObjectMapper objectMapper;

    @InjectMocks
    KafkaProducer kafkaProducer;

    @Test
    void sendMessage_sendsToFixedTopic() {
        kafkaProducer.sendMessage("payload");
        verify(kafkaTemplate).send("scheme-service-topic", "payload");
    }

    @Test
    void publishJson_returnsTrueOnSuccessfulSerializationAndSend() throws Exception {
        when(objectMapper.writeValueAsString(Map.of("k", "v"))).thenReturn("{\"k\":\"v\"}");
        when(kafkaTemplate.send(eq("topic-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        boolean ok = kafkaProducer.publishJson("topic-1", Map.of("k", "v"));

        assertThat(ok).isTrue();
        verify(kafkaTemplate).send("topic-1", "{\"k\":\"v\"}");
    }

    @Test
    void publishJson_returnsFalseWhenSerializationFails() throws Exception {
        when(objectMapper.writeValueAsString(Map.of("k", "v")))
                .thenThrow(new RuntimeException("json error"));

        boolean ok = kafkaProducer.publishJson("topic-1", Map.of("k", "v"));

        assertThat(ok).isFalse();
    }

    @Test
    void publishJson_returnsTrueAndLogsMetadataWhenSendCompletes() throws Exception {
        when(objectMapper.writeValueAsString("e")).thenReturn("\"e\"");
        RecordMetadata meta = new RecordMetadata(new TopicPartition("topic-1", 2), 5L, 0, 0L, 0, 0);
        SendResult<String, String> result = new SendResult<>(null, meta);
        when(kafkaTemplate.send("topic-1", "\"e\"")).thenReturn(CompletableFuture.completedFuture(result));

        assertThat(kafkaProducer.publishJson("topic-1", "e")).isTrue();
    }

    @Test
    void publishJson_returnsTrueWhenMetadataUnavailable() throws Exception {
        when(objectMapper.writeValueAsString("e")).thenReturn("\"e\"");
        when(kafkaTemplate.send("topic-1", "\"e\""))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        assertThat(kafkaProducer.publishJson("topic-1", "e")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishJson_returnsTrueWhenMetadataLookupThrows() throws Exception {
        when(objectMapper.writeValueAsString("e")).thenReturn("\"e\"");
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenThrow(new IllegalStateException("boom"));
        when(kafkaTemplate.send("topic-1", "\"e\"")).thenReturn(CompletableFuture.completedFuture(result));

        assertThat(kafkaProducer.publishJson("topic-1", "e")).isTrue();
    }

    @Test
    void publishJson_returnsTrueEvenWhenAsyncSendFails() throws Exception {
        when(objectMapper.writeValueAsString("e")).thenReturn("\"e\"");
        when(kafkaTemplate.send("topic-1", "\"e\""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThat(kafkaProducer.publishJson("topic-1", "e")).isTrue();
    }
}
