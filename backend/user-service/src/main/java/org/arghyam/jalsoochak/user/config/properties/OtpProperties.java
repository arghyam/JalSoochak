package org.arghyam.jalsoochak.user.config.properties;

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
        String deliveryChannel
) {
    public OtpProperties {
        if (expiryMinutes <= 0)    throw new IllegalArgumentException("otp.expiry-minutes must be > 0");
        if (maxAttempts <= 0)      throw new IllegalArgumentException("otp.max-attempts must be > 0");
        if (cooldownSeconds < 0)   throw new IllegalArgumentException("otp.cooldown-seconds must be >= 0");
        if (otpLength < 4)         throw new IllegalArgumentException("otp.otp-length must be >= 4");
        if (deliveryChannel == null || deliveryChannel.isBlank())
            throw new IllegalArgumentException("otp.delivery-channel must not be blank");
        deliveryChannel = deliveryChannel.toUpperCase();
    }
}
