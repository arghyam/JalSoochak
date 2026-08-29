package org.arghyam.jalsoochak.message.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "message-service-topic";

    /**
     * JSON keys whose values must never reach a log file — the recipient address on
     * {@code account-email-dlt} and the phone number on {@code welcome-message-dlt}.
     * Mirrors {@code KafkaConfig.redactPhoneNumbers}, which covers the consume side.
     */
    private static final Pattern SENSITIVE_KEYS = Pattern.compile(
            "(\"(?:OTP|otp|password|officerPhoneNumber|phoneNumber|phone|recipientPhone"
                    + "|to|email|inviteLink|resetLink|activationLink)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * Masks the values of {@link #SENSITIVE_KEYS} so a DEBUG payload dump shows an event's shape
     * without disclosing its contents. A string rewrite rather than a re-parse: this sits on the
     * publish path and must not be able to throw.
     */
    static String redactSensitive(String json) {
        if (json == null) {
            return null;
        }
        return SENSITIVE_KEYS.matcher(json).replaceAll("$1\"***\"");
    }

    /**
     * How long a caller waits for the broker to acknowledge a record.
     *
     * <p>{@code KafkaTemplate.send} returns as soon as the record is in the producer's local batch
     * buffer; the broker's answer arrives later on the producer's I/O thread. The callers that matter
     * here are the dead-letter paths, and they have to decide <em>synchronously</em> whether to count
     * the record as parked or as lost — so they need the answer, not the future.
     *
     * <p>Ten seconds is well under the producer's own {@code delivery.timeout.ms} (120s by default),
     * which is the point: a broker outage must not park a Kafka listener thread for two minutes per
     * record. Returning {@code false} early is the correct outcome — the caller counts a drop and
     * moves on rather than stalling the partition.
     */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendMessage(String message) {
        log.info("Publishing message to topic [{}]: {}", TOPIC, message);
        kafkaTemplate.send(TOPIC, message);
    }

    /**
     * Serializes {@code event} and publishes it, waiting up to {@link #ACK_TIMEOUT} for the broker to
     * acknowledge the record.
     *
     * <p>The wait is the whole point. This method previously called {@code kafkaTemplate.send} and
     * returned immediately, so a broker-side failure — surfaced asynchronously on the producer's I/O
     * thread — never reached the caller's {@code catch}. {@code NotificationEventRouter} took that
     * silent return as proof the record had landed and incremented {@code outcome="deadlettered"},
     * meaning the metric reported a recoverable park for a notification that had actually been lost.
     * The container's {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer} already
     * waits for its send result; this brings the service's own dead-letter path in line with it.
     *
     * @return {@code true} only when the broker acknowledged the record
     * @throws RuntimeException if the event cannot be serialized — a bug in the payload, not a
     *                          delivery failure, and not something a caller can retry around
     */
    public boolean publishJson(String topic, Object event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to serialize Kafka event", e);
        }

        // The dead-letter payloads carry the recipient's email address and, for
        // welcome-message-dlt, their phone number. Both are PII and stay out of INFO.
        log.info("Publishing JSON to topic [{}]: event={}", topic, event.getClass().getSimpleName());
        log.debug("Publishing JSON to topic [{}]: {}", topic, redactSensitive(json));

        try {
            SendResult<String, String> result =
                    kafkaTemplate.send(topic, json).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            RecordMetadata metadata = result.getRecordMetadata();
            log.debug("Published to topic [{}] partition={} offset={}",
                    topic, metadata.partition(), metadata.offset());
            return true;
        } catch (InterruptedException e) {
            // Shutdown in progress. Restore the flag so the container's own stop logic still sees it.
            Thread.currentThread().interrupt();
            log.error("Interrupted while awaiting acknowledgement from topic [{}]; treating as not published", topic);
            return false;
        } catch (ExecutionException | TimeoutException e) {
            // Payload withheld deliberately: the caller is usually a dead-letter path whose record
            // carries an email address or a phone number.
            log.error("Publish to topic [{}] was not acknowledged ({}): {}",
                    topic, e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // Buffer exhaustion, serializer failure inside the producer, an unresolvable topic.
            log.error("Publish to topic [{}] failed ({}): {}", topic, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
}
