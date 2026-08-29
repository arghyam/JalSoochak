package org.arghyam.jalsoochak.telemetry.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumer {

    private final ObjectMapper objectMapper;
    private final TenantConfigRepository tenantConfigRepository;

    /**
     * This service takes no action on common-topic events; the listener exists only for visibility.
     * The payload must not be logged: common-topic carries operator and officer phone numbers, and
     * — until it moved to its own topic — the plaintext login OTP. Event type alone is enough here.
     */
    @KafkaListener(topics = "common-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        String eventType;
        try {
            eventType = objectMapper.readTree(message).path("eventType").asText("UNKNOWN");
        } catch (Exception e) {
            eventType = "UNPARSEABLE";
        }
        log.info("[telemetry-service] Received message from common-topic: eventType={}", safeEventType(eventType));
    }

    private static final Pattern EVENT_TYPE_SHAPE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * The event type is payload data, and this is the only part of the payload that reaches an INFO
     * line. Producers only ever set it to a constant, but nothing on the consume side enforces that:
     * a value carrying a newline would let a crafted event write extra lines into the log and forge
     * entries for other services. Anything outside the shape a real event type has is replaced
     * rather than escaped, so no attacker-chosen text is logged at all.
     */
    private static String safeEventType(String eventType) {
        return EVENT_TYPE_SHAPE.matcher(eventType).matches() ? eventType : "INVALID";
    }

    @KafkaListener(topics = "tenant-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTenantServiceEvent(String message) {
        log.info("[telemetry-service] Received message from tenant-service-topic: {}", message);
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText(null);
            if (!"TENANT_CONFIG_UPDATED".equals(eventType)) {
                return;
            }
            JsonNode tenantIdNode = event.get("tenantId");
            if (tenantIdNode == null || !tenantIdNode.canConvertToInt()) {
                log.warn("[telemetry-service] TENANT_CONFIG_UPDATED missing valid tenantId: {}", message);
                return;
            }
            Integer tenantId = tenantIdNode.asInt();
            tenantConfigRepository.invalidateTenantConfigCache(tenantId);
            log.info("[telemetry-service] Invalidated tenant config cache [tenantId={}]", tenantId);
        } catch (Exception e) {
            log.warn("[telemetry-service] Failed to process tenant-service-topic message: {}", e.getMessage());
        }
    }
}
