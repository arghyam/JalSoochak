package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "analytics-service-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendMessage(String message) {
        log.info("Publishing message to topic [{}]: {}", TOPIC, message);
        awaitSend(TOPIC, kafkaTemplate.send(TOPIC, message));
    }

    /**
     * Serializes {@code event} to JSON and publishes it to the given topic. Blocks until the broker
     * has accepted the record so a Kafka failure surfaces to the caller (the Kafka listener) instead
     * of being lost after the offset is committed.
     */
    public void publishJson(String topic, Object event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to serialize Kafka event", e);
        }
        log.debug("Publishing event to topic [{}]: {}", topic, json);
        awaitSend(topic, kafkaTemplate.send(topic, json));
    }

    /**
     * Waits for the send future so broker rejections propagate to the caller rather than being
     * silently dropped after the consumer offset is committed.
     */
    private void awaitSend(String topic, java.util.concurrent.Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to topic " + topic, e);
        } catch (ExecutionException e) {
            log.error("Failed to publish to topic [{}]: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to publish Kafka event to " + topic, e);
        }
    }
}
