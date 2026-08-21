package org.arghyam.jalsoochak.scheme.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class KafkaConsumer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * This service takes no action on common-topic events; the listener exists only for visibility.
     * The payload must not be logged: common-topic carries operator and officer phone numbers, and
     * — until it moved to its own topic — the plaintext login OTP. Event type alone is enough here.
     */
    @KafkaListener(topics = "common-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        String eventType;
        try {
            eventType = OBJECT_MAPPER.readTree(message).path("eventType").asText("UNKNOWN");
        } catch (Exception e) {
            eventType = "UNPARSEABLE";
        }
        log.info("[scheme-service] Received message from common-topic: eventType={}", eventType);
    }
}
