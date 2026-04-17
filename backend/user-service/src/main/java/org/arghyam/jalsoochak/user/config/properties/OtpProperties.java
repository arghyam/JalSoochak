package org.arghyam.jalsoochak.user.config.properties;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OTP configuration properties.
 * Bound from {@code otp.*} in application.yml.
 */
@ConfigurationProperties(prefix = "otp")
public record OtpProperties(
        int expiryMinutes,
        int maxAttempts,
        int cooldownSeconds,
        int otpLength,
        String deliveryChannel,
        Cheat cheat
) {
    /**
     * Cheat OTP configuration — for non-production environments only.
     *
     * @param enabled when {@code true}, all OTP requests return {@code value} instead of a random OTP
     * @param value   the fixed OTP to return; must be non-blank when {@code enabled} is {@code true}
     */
    public record Cheat(boolean enabled, String value) {}

    public OtpProperties {
        if (expiryMinutes <= 0)    throw new IllegalArgumentException("otp.expiry-minutes must be > 0");
        if (maxAttempts <= 0)      throw new IllegalArgumentException("otp.max-attempts must be > 0");
        if (cooldownSeconds < 0)   throw new IllegalArgumentException("otp.cooldown-seconds must be >= 0");
        if (otpLength < 4)         throw new IllegalArgumentException("otp.otp-length must be >= 4");
        if (deliveryChannel == null || deliveryChannel.isBlank())
            throw new IllegalArgumentException("otp.delivery-channel must not be blank");
        deliveryChannel = deliveryChannel.trim().toUpperCase(Locale.ROOT);
        if (cheat == null) cheat = new Cheat(false, null);
        if (cheat.enabled() && (cheat.value() == null || cheat.value().isBlank()))
            throw new IllegalArgumentException("otp.cheat.value must not be blank when otp.cheat.enabled is true");
    }
}
