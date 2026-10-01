package org.arghyam.jalsoochak.telemetry.kafka;

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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The thin Kafka publishing wrapper. Its contract is that publishing never throws: a serialisation
 * or broker failure is reported as {@code false} so callers can log and carry on rather than losing
 * the already-persisted reading behind it. {@code publishJsonAndAwait} also reports a send the broker
 * fails or does not acknowledge in time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KafkaProducer")
class KafkaProducerTest {

    private static final String TOPIC = "telemetry-service-topic";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaProducer producer() {
        return new KafkaProducer(kafkaTemplate, new ObjectMapper());
    }

    /**
     * A real completed {@link SendResult}, built outside any {@code when(...)} chain — constructing
     * it inline would nest one stubbing inside another and Mockito rejects that.
     */
    private static CompletableFuture<SendResult<String, String>> completedSend() {
        ProducerRecord<String, String> sentRecord = new ProducerRecord<>(TOPIC, "payload");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(sentRecord, metadata));
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

        boolean published = producer().publishJson(TOPIC, Map.of("eventType", "TEST", "tenantId", 17));

        assertThat(published).isTrue();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), payload.capture());
        assertThat(payload.getValue()).contains("\"eventType\":\"TEST\"").contains("\"tenantId\":17");
    }

    @Test
    void publishJsonHonoursTheRequestedTopic() {
        CompletableFuture<SendResult<String, String>> sent = completedSend();
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(sent);

        producer().publishJson("anomaly-service-topic", Map.of("k", "v"));

        verify(kafkaTemplate).send(eq("anomaly-service-topic"), anyString());
    }

    @Test
    void publishJsonReportsFailureWhenTheEventCannotBeSerialised() {
        boolean published = producer().publishJson(TOPIC, new Unserialisable());

        assertThat(published).isFalse();
        verify(kafkaTemplate, org.mockito.Mockito.never()).send(anyString(), anyString());
    }

    @Test
    void publishJsonReportsFailureWhenTheBrokerCallThrows() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenThrow(new IllegalStateException("no broker available"));

        assertThat(producer().publishJson(TOPIC, Map.of("k", "v"))).isFalse();
    }

    @Test
    void publishJsonStillReportsSuccessWhenTheAsyncSendLaterFails() {
        // The send is fire-and-forget: an async broker failure is logged by the callback, and the
        // caller has already been told the event was handed off.
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(failed);

        assertThat(producer().publishJson(TOPIC, Map.of("k", "v"))).isTrue();
    }

    @Test
    void publishJsonToleratesASendResultWithoutMetadata() {
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenThrow(new IllegalStateException("metadata unavailable"));
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));

        assertThat(producer().publishJson(TOPIC, Map.of("k", "v"))).isTrue();
    }

    @Test
    void publishJsonAndAwaitReportsTheAcknowledgement() {
        CompletableFuture<SendResult<String, String>> sent = completedSend();
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(sent);

        assertThat(producer().publishJsonAndAwait(TOPIC, Map.of("k", "v"), TIMEOUT)).isTrue();

        verify(kafkaTemplate).send(eq(TOPIC), eq("{\"k\":\"v\"}"));
    }

    /** Unlike publishJson, a send the broker later fails is reported to the caller. */
    @Test
    void publishJsonAndAwaitReportsAFailedSend() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(failed);

        assertThat(producer().publishJsonAndAwait(TOPIC, Map.of("k", "v"), TIMEOUT)).isFalse();
    }

    @Test
    void publishJsonAndAwaitGivesUpWhenNoAcknowledgementComesInTime() {
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(new CompletableFuture<>());

        assertThat(producer().publishJsonAndAwait(TOPIC, Map.of("k", "v"), Duration.ofMillis(10))).isFalse();
    }

    @Test
    void publishJsonAndAwaitReportsFailureWhenTheBrokerCallThrows() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenThrow(new IllegalStateException("no broker available"));

        assertThat(producer().publishJsonAndAwait(TOPIC, Map.of("k", "v"), TIMEOUT)).isFalse();
    }

    @Test
    void publishJsonAndAwaitReportsFailureWhenTheEventCannotBeSerialised() {
        assertThat(producer().publishJsonAndAwait(TOPIC, new Unserialisable(), TIMEOUT)).isFalse();

        verify(kafkaTemplate, org.mockito.Mockito.never()).send(anyString(), anyString());
    }

    /** The interrupt is kept, so the calling thread can still see it was asked to stop. */
    @Test
    void publishJsonAndAwaitKeepsTheThreadsInterrupt() {
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThat(producer().publishJsonAndAwait(TOPIC, Map.of("k", "v"), TIMEOUT)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** Jackson cannot serialise a bean with no properties and no annotations. */
    private static final class Unserialisable {
    }
}
