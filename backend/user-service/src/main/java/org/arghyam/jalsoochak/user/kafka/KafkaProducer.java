package org.arghyam.jalsoochak.user.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private static final String TOPIC = "user-service-topic";

    /**
     * JSON keys whose values must never reach a log file. Two kinds live here: outright secrets
     * (the OTP, passwords) and single-use links, which are bearer credentials — anyone holding a
     * password-reset URL can take the account. Phone numbers and email addresses are PII under
     * the same rule that keeps them out of INFO everywhere else in the platform.
     */
    private static final Pattern SENSITIVE_KEYS = Pattern.compile(
            "(\"(?:OTP|otp|password|officerPhoneNumber|phoneNumber|phone|recipientPhone"
                    + "|to|email|inviteLink|resetLink|activationLink)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * Masks the values of {@link #SENSITIVE_KEYS} so a DEBUG payload dump stays useful for
     * shape-checking an event without disclosing its contents. Deliberately a string rewrite
     * rather than a re-parse: this runs on the publish path and must not be able to throw.
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

    /**
     * Serializes {@code event} to JSON and publishes it to the given topic.
     */
    public boolean publishJson(String topic, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // Never log the payload at INFO. SEND_LOGIN_OTP carries the plaintext OTP and the
            // officer's phone number; the invite and password-reset events carry single-use
            // links that are credentials in their own right. @ToString.Exclude on the event does
            // not help here — Jackson serialises the field regardless of what toString() hides.
            log.info("[kafka:publish] topic={} event={}", topic, event.getClass().getSimpleName());
            log.debug("[kafka:publish] topic={} payload={}", topic, redactSensitive(json));

            CompletableFuture<SendResult<String, String>> fut = kafkaTemplate.send(topic, json);
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
