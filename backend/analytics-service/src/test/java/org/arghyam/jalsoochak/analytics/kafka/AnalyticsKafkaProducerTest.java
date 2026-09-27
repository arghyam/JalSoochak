package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Analytics publishes from inside a Kafka listener, so — unlike telemetry's fire-and-forget producer
 * — this one blocks on the send and rethrows. A broker rejection must reach the listener, otherwise
 * the consumer offset is committed for an event that was never republished.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KafkaProducer (analytics)")
class AnalyticsKafkaProducerTest {

    private static final String TOPIC = "analytics-service-topic";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaProducer producer() {
        return new KafkaProducer(kafkaTemplate, new ObjectMapper());
    }

    private static CompletableFuture<SendResult<String, String>> completedSend() {
        ProducerRecord<String, String> sentRecord = new ProducerRecord<>(TOPIC, "payload");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(sentRecord, metadata));
    }

    private static CompletableFuture<SendResult<String, String>> failedSend(Throwable cause) {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(cause);
        return future;
    }

    @Test
    void sendMessagePublishesTheRawPayloadToTheDefaultTopic() {
        CompletableFuture<SendResult<String, String>> sent = completedSend();
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(sent);

        producer().sendMessage("hello");

        verify(kafkaTemplate).send(TOPIC, "hello");
    }

    @Test
    void publishJsonSerialisesTheEventBeforeSending() {
        CompletableFuture<SendResult<String, String>> sent = completedSend();
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(sent);

        producer().publishJson(TOPIC, Map.of("eventType", "TEST", "tenantId", 17));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), payload.capture());
        assertThat(payload.getValue()).contains("\"eventType\":\"TEST\"").contains("\"tenantId\":17");
    }

    @Test
    void publishJsonHonoursTheRequestedTopic() {
        CompletableFuture<SendResult<String, String>> sent = completedSend();
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(sent);

        producer().publishJson("other-topic", Map.of("k", "v"));

        verify(kafkaTemplate).send(eq("other-topic"), anyString());
    }

    @Test
    void publishJsonFailsWhenTheEventCannotBeSerialised() {
        assertThatThrownBy(() -> producer().publishJson(TOPIC, new Unserialisable()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to serialize Kafka event");

        verify(kafkaTemplate, org.mockito.Mockito.never()).send(anyString(), anyString());
    }

    @Test
    void publishJsonRethrowsABrokerRejectionSoTheListenerDoesNotCommitTheOffset() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(failedSend(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> producer().publishJson(TOPIC, Map.of("k", "v")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to publish Kafka event to " + TOPIC);
    }

    @Test
    void sendMessageRethrowsABrokerRejection() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(failedSend(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> producer().sendMessage("hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to publish Kafka event to " + TOPIC);
    }

    @Test
    void publishJsonRestoresTheInterruptFlagWhenTheWaitIsInterrupted() throws Exception {
        // A never-completing send, so future.get() blocks until the thread is interrupted.
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(new CompletableFuture<>());

        Thread worker = new Thread(() ->
                assertThatThrownBy(() -> producer().publishJson(TOPIC, Map.of("k", "v")))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Interrupted while publishing"));
        worker.start();
        Thread.sleep(150);
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
    }

    /** Jackson cannot serialise a bean with no properties and no annotations. */
    private static final class Unserialisable {
    }
}
