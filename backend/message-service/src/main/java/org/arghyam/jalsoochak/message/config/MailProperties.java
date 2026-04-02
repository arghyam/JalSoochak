package org.arghyam.jalsoochak.message.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Unified configuration for the transactional email abstraction.
 *
 * <p>Bound to {@code notification.mail.*} in application.yml.
 * The active provider is selected by {@code notification.mail.provider}:
 * <ul>
 *   <li>{@code sendgrid} — uses SendGrid dynamic templates (default)</li>
 *   <li>{@code smtp}     — uses SMTP with plain-text fallback (requires spring.mail.* config)</li>
 * </ul>
 *
 * <p>Both {@code sendgrid} and {@code smtp} fields may be null depending on the active provider.
 * The respective sender implementations validate that their required configuration is present
 * at construction time via {@code @ConditionalOnProperty}.
 */
@ConfigurationProperties(prefix = "notification.mail")
@Validated
public record MailProperties(
        String provider,
        String fromAddress,
        String fromName,
        String logoImageUrl,
        SendGrid sendgrid,
        Smtp smtp
) {

    // ── SendGrid ─────────────────────────────────────────────────────────────────

    public record SendGrid(
            @NotBlank(message = "SendGrid API key must not be blank; set SENDGRID_API_KEY environment variable")
            String apiKey,
            Templates templates
    ) {}

    public record Templates(
            String passwordReset,
            String reinvitation,
            String defaultInvitation,
            String superUserInvitation,
            String stateAdminInvitation
    ) {}

    // ── SMTP ─────────────────────────────────────────────────────────────────────

    /**
     * Subject and body templates for SMTP delivery.
     * Body strings may contain {@code {variable}} placeholders — e.g. {@code {name}},
     * {@code {activation_link}}, {@code {expiry_hours}}, {@code {reset_link}},
     * {@code {expiry_minutes}}, {@code {state_name}}.
     * Values are sourced from {@link org.arghyam.jalsoochak.message.dto.MailRequest#templateVariables()}.
     */
    public record Smtp(SmtpTemplates templates) {}

    public record SmtpTemplates(
            SmtpTemplate passwordReset,
            SmtpTemplate reinvitation,
            SmtpTemplate defaultInvitation,
            SmtpTemplate superUserInvitation,
            SmtpTemplate stateAdminInvitation
    ) {}

    public record SmtpTemplate(
            @NotBlank(message = "SMTP template subject must not be blank")
            String subject,
            @NotBlank(message = "SMTP template body must not be blank")
            String body
    ) {}
}
