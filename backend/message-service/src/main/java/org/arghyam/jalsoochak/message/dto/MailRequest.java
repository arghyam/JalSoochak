package org.arghyam.jalsoochak.message.dto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable input to the {@link org.arghyam.jalsoochak.message.channel.EmailSender} port.
 *
 * <p>{@code templateVariables} uses snake_case keys as the canonical naming convention:
 * <ul>
 *   <li>{@code name} — recipient display name</li>
 *   <li>{@code activation_link} — account activation URL</li>
 *   <li>{@code expiry_hours} — link validity in hours (Integer)</li>
 *   <li>{@code reset_link} — password reset URL</li>
 *   <li>{@code expiry_minutes} — link validity in minutes (Integer)</li>
 *   <li>{@code state_name} — state name (STATE_ADMIN_INVITATION only)</li>
 * </ul>
 *
 * <p>The {@code logo_image} key is NOT included here — each channel adapter injects it
 * from {@code MailProperties.logoImageUrl()} so callers never need to supply it.
 */
public record MailRequest(
        String to,
        MailTemplate template,
        Map<String, Object> templateVariables
) {
    /**
     * Canonical constructor that defensively copies the templateVariables map
     * to ensure immutability and prevent external mutation after construction.
     */
    public MailRequest {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(template, "template must not be null");
        templateVariables = Collections.unmodifiableMap(new HashMap<>(
                Objects.requireNonNull(templateVariables, "templateVariables must not be null")));
    }
}
