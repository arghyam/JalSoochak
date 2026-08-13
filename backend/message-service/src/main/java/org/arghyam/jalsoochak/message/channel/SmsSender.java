package org.arghyam.jalsoochak.message.channel;

import reactor.core.publisher.Mono;

/**
 * Port interface for transactional SMS (OTP) delivery.
 *
 * <p>Exactly one implementation is registered in the Spring context at a time,
 * controlled by the {@code notification.sms.provider} property:
 * <ul>
 *   <li>{@code smscountry} — {@link SmsCountryService} (default)</li>
 * </ul>
 *
 * <p>To switch providers, add a new {@code SmsSender} implementation annotated
 * with {@code @ConditionalOnProperty(name = "notification.sms.provider",
 * havingValue = "<name>")} and set {@code SMS_PROVIDER=<name>}. Callers depend
 * only on this port, so no other code changes are required — provider-specific
 * concerns (auth scheme, request/response shape, DLT template registration)
 * stay inside the adapter.
 *
 * <p>The contract is reactive so callers can dispatch without blocking the
 * Kafka listener thread. The returned {@link Mono}:
 * <ul>
 *   <li>emits {@code true} when the provider accepts the message for delivery;</li>
 *   <li>emits {@code false} on a non-retryable rejection (e.g. a 4xx / bad
 *       configuration) that must <em>not</em> trigger a Kafka retry;</li>
 *   <li>signals an error for transient failures (5xx, network, timeout) that a
 *       caller may choose to retry.</li>
 * </ul>
 */
public interface SmsSender {

    /**
     * Send a one-time-password SMS.
     *
     * @param phoneNumber   E.164 format without '+' (e.g. "919876543210")
     * @param otp           the one-time password string
     * @param expiryMinutes how long the OTP is valid, in minutes
     * @return a {@code Mono} emitting {@code true} on acceptance, {@code false}
     *         on a non-retryable rejection, or an error signal on transient failure
     */
    Mono<Boolean> sendOtp(String phoneNumber, String otp, int expiryMinutes);
}
