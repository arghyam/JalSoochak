package org.arghyam.jalsoochak.user.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * Kafka event published to {@code common-topic} after a staff user requests an OTP.
 * {@code message-service} consumes this event and delivers the OTP via WhatsApp or SMS.
 *
 * <p>Phone number is PII — this event is published only to the
 * internal Kafka topic, never logged at INFO/WARN/ERROR level.
 */
@Value
@Builder
public class SendLoginOtpEvent {

    /** Always {@code "SEND_LOGIN_OTP"}. */
    @JsonProperty("eventType")
    String eventType;

    /** Decrypted phone number with country code (e.g. {@code "919876543210"}). PII — do not log. */
    @JsonProperty("phoneNumber")
    String phoneNumber;

    /** Glific contact ID from {@code user_table.whatsapp_connection_id}. Omitted for SMS delivery. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("glific_id")
    Long glificId;

    /** Plaintext OTP to deliver. Never stored in plaintext in the DB. */
    @JsonProperty("OTP")
    String otp;

    /** OTP validity duration in minutes. */
    @JsonProperty("expiryMinutes")
    int expiryMinutes;

    /** {@code "WHATSAPP"} or {@code "SMS"} — from {@code otp.delivery-channel} config. */
    @JsonProperty("deliveryChannel")
    String deliveryChannel;
}
