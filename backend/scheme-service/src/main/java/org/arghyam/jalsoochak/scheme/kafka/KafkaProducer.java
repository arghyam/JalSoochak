package org.arghyam.jalsoochak.scheme.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "scheme-service-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendMessage(String message) {
        log.info("Publishing message to topic [{}]: {}", TOPIC, message);
        kafkaTemplate.send(TOPIC, message);
    }

    /**
     * Publishes {@code event} as JSON under {@code key}. Kafka keeps records with the same key in one
     * partition, so consumers see them in the order they were sent.
     */
    public boolean publishJson(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> fut = kafkaTemplate.send(topic, key, json);
            fut.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("[kafka:publish] FAILED topic={} err={}", topic, ex.getMessage(), ex);
                    return;
                }
                try {
                    RecordMetadata meta = result != null ? result.getRecordMetadata() : null;
                    if (meta != null) {
                        log.info("[kafka:publish] OK topic={} partition={} offset={}",
                                topic, meta.partition(), meta.offset());
                    } else {
                        log.info("[kafka:publish] OK topic={} (metadata unavailable)", topic);
                    }
                } catch (Exception metaEx) {
                    log.info("[kafka:publish] OK topic={} (metadata unavailable: {})",
                            topic, metaEx.getMessage());
                }
            });
            return true;
        } catch (Exception e) {
            log.error("[kafka:publish] FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        }
    }
}
