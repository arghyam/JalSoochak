package org.arghyam.jalsoochak.message.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendMessage(String message) {
        log.info("Publishing message to topic [{}]: {}", TOPIC, message);
        kafkaTemplate.send(TOPIC, message);
    }

    public void publishJson(String topic, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // The dead-letter payloads carry the recipient's email address and, for
            // welcome-message-dlt, their phone number. Both are PII and stay out of INFO.
            log.info("Publishing JSON to topic [{}]: event={}", topic, event.getClass().getSimpleName());
            log.debug("Publishing JSON to topic [{}]: {}", topic, redactSensitive(json));
            kafkaTemplate.send(topic, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to serialize Kafka event", e);
        }
    }
}
