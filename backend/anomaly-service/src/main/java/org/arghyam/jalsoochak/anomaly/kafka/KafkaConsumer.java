package org.arghyam.jalsoochak.anomaly.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.anomaly.service.AnalyticsDimensionSyncService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumer {

    private final ObjectMapper objectMapper;
    private final AnalyticsDimensionSyncService analyticsDimensionSyncService;

    @KafkaListener(topics = "common-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        log.info("[anomaly-service] Received message from common-topic: {}", message);
    }

    @KafkaListener(topics = "user-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeUserEvents(String message) {
        log.info("[anomaly-service] Received message from user-service-topic");
        try {
            JsonNode node = objectMapper.readTree(message);
            String eventType = node.path("eventType").asText("UNKNOWN");
            switch (eventType) {
                case "USER_CREATED", "USER_UPDATED" -> analyticsDimensionSyncService.upsertUser(node);
                case "USER_SCHEME_MAPPINGS_REPLACED" -> analyticsDimensionSyncService.replaceUserSchemeMappings(node);
                default -> log.debug("[anomaly-service] Ignoring user event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process user event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "scheme-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeSchemeEvents(String message) {
        log.info("[anomaly-service] Received message from scheme-service-topic");
        try {
            JsonNode node = objectMapper.readTree(message);
            String eventType = node.path("eventType").asText("UNKNOWN");
            switch (eventType) {
                case "SCHEME_CREATED", "SCHEME_UPDATED" -> analyticsDimensionSyncService.upsertScheme(node);
                default -> log.debug("[anomaly-service] Ignoring scheme event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process scheme event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "telemetry-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTelemetryEvents(String message) {
        log.info("[anomaly-service] Received message from telemetry-service-topic");
        System.out.println("[anomaly-service] telemetry-service-topic raw=" + message);
        try {
            String eventType = extractEventType(message);
            if ("ANOMALY_RECORDED".equals(eventType)) {
                log.debug("[anomaly-service] Ignoring ANOMALY_RECORDED to avoid duplicate analytics rows.");
                return;
            }
            log.debug("[anomaly-service] Ignoring telemetry event type: {}", eventType);
        } catch (Exception e) {
            log.error("Failed to process telemetry event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String extractEventType(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode eventTypeNode = node.get("eventType");
            return eventTypeNode != null ? eventTypeNode.asText() : "UNKNOWN";
        } catch (Exception e) {
            log.warn("Could not extract eventType from message, treating as UNKNOWN");
            return "UNKNOWN";
        }
    }
}
