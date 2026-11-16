package org.arghyam.jalsoochak.telemetry.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "telemetry-service-topic";

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
        try {
            String json = objectMapper.writeValueAsString(event);
            log.info("[kafka:publish] topic={} payload={}", topic, json);

            logOutcome(topic, kafkaTemplate.send(topic, json));
            return true;
        } catch (JsonProcessingException e) {
            log.error("[kafka:publish] SERIALIZE_FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("[kafka:publish] FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Like {@link #publishJson(String, Object)}, with a record key: Kafka delivers the records that share
     * a key in the order they were sent. The payload is logged at DEBUG only, since a keyed event may
     * carry an operator's phone number.
     */
    public boolean publishJson(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            log.debug("[kafka:publish] topic={} key={} payload={}", topic, key, json);

            logOutcome(topic, kafkaTemplate.send(topic, key, json));
            return true;
        } catch (JsonProcessingException e) {
            log.error("[kafka:publish] SERIALIZE_FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("[kafka:publish] FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Like {@link #publishJson(String, Object)}, but waits up to {@code timeout} for the broker to
     * acknowledge the event, for a caller that sends many events in a row: it cannot run ahead of Kafka,
     * and it learns which events did not go. The payload is logged at DEBUG only, since such a caller
     * sends many.
     *
     * <p>{@code false} after a timeout does not mean the event was lost: the producer may still deliver
     * it, so a consumer must be able to apply it twice.
     *
     * @return {@code true} once the broker has acknowledged the event; {@code false} when it could not
     *         be serialised, the send failed, or no acknowledgement came within {@code timeout}
     */
    public boolean publishJsonAndAwait(String topic, Object event, Duration timeout) {
        try {
            String json = objectMapper.writeValueAsString(event);
            log.debug("[kafka:publish] topic={} payload={}", topic, json);

            kafkaTemplate.send(topic, json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("[kafka:publish] ACKNOWLEDGED topic={}", topic);
            return true;
        } catch (JsonProcessingException e) {
            log.error("[kafka:publish] SERIALIZE_FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[kafka:publish] INTERRUPTED topic={}", topic);
            return false;
        } catch (TimeoutException e) {
            log.error("[kafka:publish] NOT_ACKNOWLEDGED topic={} timeout={}", topic, timeout);
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[kafka:publish] FAILED topic={} err={}", topic, cause.getMessage(), cause);
            return false;
        } catch (Exception e) {
            log.error("[kafka:publish] FAILED topic={} err={}", topic, e.getMessage(), e);
            return false;
        }
    }

    private static void logOutcome(String topic, CompletableFuture<SendResult<String, String>> send) {
        send.whenComplete((res, ex) -> {
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
    }
}
