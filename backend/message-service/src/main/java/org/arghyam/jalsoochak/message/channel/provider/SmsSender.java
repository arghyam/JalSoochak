package org.arghyam.jalsoochak.message.channel.provider;

import reactor.core.publisher.Mono;

/**
 * Port interface for transactional SMS (OTP) delivery.
 *
 * <p>Implementations are plain classes, one instance per SMS account. The
 * {@code notification.sms.provider} property selects the <em>system default</em>
 * one, built in {@code SystemDefaultProviders}:
 * <ul>
 *   <li>{@code smscountry} — {@link SmsCountrySender} (default)</li>
 * </ul>
 *
 * <p>PER-TENANT-PROVIDERS: a tenant that has configured its own account gets its
 * own instance of the same adapter instead, built by the matching
 * {@link SmsSenderFactory} and handed out by {@link TenantChannelProviders}
 * (O2-1, O2-2). Callers ask that class for a tenant's sender and otherwise
 * depend only on this port, so no business logic changes.
 *
 * <p>To add a provider, write an {@code SmsSender} adapter and an
 * {@code SmsSenderFactory} for it, and register the factory as a
 * {@code @Component}. Provider-specific concerns (auth scheme, request and
 * response shape, DLT template registration) stay inside the adapter.
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
