package org.arghyam.jalsoochak.message.config;

import jakarta.validation.Valid;
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
 * {@code SystemDefaultProviders} validates that the active provider's configuration is present
 * when it builds that provider's bean.
 *
 * <p>PER-TENANT-PROVIDERS: these are the <em>system default</em> mail settings (O2-4) — what every
 * tenant used before the feature existed, and what a tenant with no settings of its own, an event
 * with no tenant, and every send while the flag is off still use. A tenant that configures its own
 * account gets the account-specific values from
 * {@link org.arghyam.jalsoochak.message.dto.EmailProviderSettings} and the encrypted secret store
 * instead. Three things here are shared by both paths: {@link SendGrid#apiUrl()}, because SendGrid
 * serves every customer from one host and a per-tenant URL would only add an attacker-chosen
 * destination for the API key (O2-13); the SMTP subject and body templates, which are this
 * service's own text whichever relay carries them (O2-17); and {@link #fromName()} and
 * {@link #logoImageUrl()}, which a tenant may leave unset and then falls back to.
 */
@ConfigurationProperties(prefix = "notification.mail")
@Validated
public record MailProperties(
        String provider,
        String fromAddress,
        String fromName,
        String logoImageUrl,
        @Valid SendGrid sendgrid,
        @Valid Smtp smtp
) {

    // ── SendGrid ─────────────────────────────────────────────────────────────────

    public record SendGrid(
            String apiUrl,
            @NotBlank(message = "SendGrid API key must not be blank; set SENDGRID_API_KEY environment variable")
            String apiKey,
            @Valid Templates templates
    ) {

        /** As the former {@code @Value} default on {@code SendGridMailSender.apiUrl}. */
        public static final String DEFAULT_API_URL = "https://api.sendgrid.com";

        public SendGrid {
            apiUrl = (apiUrl == null || apiUrl.isBlank()) ? DEFAULT_API_URL : apiUrl.trim();
        }

        /** The API key is a credential and must not reach a log line or an exception message (S-4). */
        @Override
        public String toString() {
            return "SendGrid(apiUrl=" + apiUrl + ")";
        }
    }

    public record Templates(
            @NotBlank(message = "SendGrid template ID for password-reset must not be blank")
            String passwordReset,
            @NotBlank(message = "SendGrid template ID for reinvitation must not be blank")
            String reinvitation,
            @NotBlank(message = "SendGrid template ID for default-invitation must not be blank")
            String defaultInvitation,
            @NotBlank(message = "SendGrid template ID for super-user-invitation must not be blank")
            String superUserInvitation,
            @NotBlank(message = "SendGrid template ID for state-admin-invitation must not be blank")
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
