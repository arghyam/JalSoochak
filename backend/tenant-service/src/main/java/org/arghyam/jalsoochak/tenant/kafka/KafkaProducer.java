package org.arghyam.jalsoochak.tenant.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "tenant-service-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendMessage(String message) {
        log.info("Publishing message to topic [{}]: {}", TOPIC, message);
        kafkaTemplate.send(TOPIC, message);
    }

    /**
     * Serializes {@code event} to JSON and publishes it to the given topic.
     */
    public void publishJson(String topic, Object event) {
        publishJson(topic, null, event);
    }

    /**
     * Serializes {@code event} to JSON and publishes it to the given topic under {@code key}, so every
     * event for the same key lands on the same partition in order. A {@code null} key is unkeyed.
     */
    public void publishJson(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            log.debug("Publishing event to topic [{}]: {}", topic, json);
            if (key == null) {
                kafkaTemplate.send(topic, json);
            } else {
                kafkaTemplate.send(topic, key, json);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to serialize Kafka event", e);
        }
    }
}
