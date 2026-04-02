package org.arghyam.jalsoochak.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unified configuration for the transactional email abstraction.
 *
 * <p>Bound to {@code notification.mail.*} in application.yml.
 * The active provider is selected by {@code notification.mail.provider}:
 * <ul>
 *   <li>{@code sendgrid} — uses SendGrid dynamic templates (default)</li>
 *   <li>{@code smtp}     — uses SMTP with plain-text fallback (requires spring.mail.* config)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "notification.mail")
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

    public record SmtpTemplate(String subject, String body) {}
}
