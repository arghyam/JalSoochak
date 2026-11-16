package org.arghyam.jalsoochak.user.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "user-service-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendMessage(String message) {
        log.info("Publishing message to topic [{}]: {}", TOPIC, message);
        kafkaTemplate.send(TOPIC, message);
    }

    /**
     * Serializes {@code event} to JSON and publishes it to the given topic.
     */
    public boolean publishJson(String topic, Object event) {
        return publish(topic, null, event);
    }

    /**
     * As {@link #publishJson(String, Object)}, with a record key: Kafka delivers the records that share
     * a key in the order they were sent.
     */
    public boolean publishJson(String topic, String key, Object event) {
        return publish(topic, key, event);
    }

    private boolean publish(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // Log the full payload so operators can confirm what other services will consume.
            log.info("[kafka:publish] topic={} payload={}", topic, json);

            CompletableFuture<SendResult<String, String>> fut = key == null
                    ? kafkaTemplate.send(topic, json)
                    : kafkaTemplate.send(topic, key, json);
            fut.whenComplete((res, ex) -> {
                if (ex != null) {
                    log.error("[kafka:publish] FAILED topic={} err={}", topic, ex.getMessage(), ex);
                    return;
                }
                try {
                    var meta = res.getRecordMetadata();
                    log.info("[kafka:publish] OK topic={} partition={} offset={}",
                            topic, meta.partition(), meta.offset());
                } catch (Exception metaEx) {
                    log.info("[kafka:publish] OK topic={} (metadata unavailable: {})",
                            topic, metaEx.getMessage());
                }
            });
            return true;
        } catch (JsonProcessingException e) {
            log.error("[kafka:publish] SERIALIZE_FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            // Kafka may be unavailable in local/dev; publishing should be best-effort and must not break core flows.
            log.error("[kafka:publish] FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        }
    }
}
