package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * PER-TENANT-PROVIDERS: builds one tenant's {@link SendGridMailSender} from its stored settings and
 * its decrypted API key (O2-2).
 *
 * <p>Registered unconditionally, unlike the system default bean in {@code SystemDefaultProviders}:
 * the factories exist so that several accounts can coexist, so gating one on
 * {@code notification.mail.provider} — which now names only the <em>system default</em> provider
 * (O2-4) — would stop a tenant using SendGrid because the platform does not.
 *
 * <p>The API root comes from {@code notification.mail.sendgrid.api-url} rather than from the
 * settings, and the key from the secret store rather than from the settings, so nothing a state
 * admin writes can choose where an API key is sent (O2-8, O2-13).
 *
 * <p>{@code fromName} and {@code logoImageUrl} are optional on write and fall back to the
 * platform's {@code notification.mail.from-name} and {@code logo-image-url}. A tenant that fills in
 * only the required fields then sends from its own verified address under the product's name, with
 * the logo its templates expect, rather than with an empty display name and a broken image.
 *
 * <p>Every check here is a build-time one, so a failure is a {@link ProviderNotUsableException}
 * that leaves the tenant on the system default with an ERROR rather than stopping its mail (O2-9).
 */
@Component
public class SendGridMailSenderFactory implements EmailSenderFactory {

    /**
     * The name {@code EmailProviderType.SENDGRID.getRequiredSecretNames()} declares, which is what
     * {@code TenantChannelProviders} has already resolved by the time {@link #create} is called.
     * Duplicated because the enum is kept byte-identical to tenant-service's twin;
     * {@code SendGridMailSenderFactoryTest} asserts the two agree.
     */
    static final String SECRET_API_KEY = "apiKey";

    private final WebClient.Builder webClientBuilder;
    private final MailProperties mailProperties;

    public SendGridMailSenderFactory(WebClient.Builder webClientBuilder, MailProperties mailProperties) {
        this.webClientBuilder = webClientBuilder;
        this.mailProperties = mailProperties;
    }

    @Override
    public EmailProviderType providerId() {
        return EmailProviderType.SENDGRID;
    }

    @Override
    public EmailSender create(EmailProviderSettings settings, TenantSecrets secrets) {
        EmailProviderSettings.SendGrid block = settings == null ? null : settings.sendgrid();
        if (block == null) {
            throw new ProviderNotUsableException("email settings carry no 'sendgrid' block");
        }
        // The template ids and the from address are checked although tenant-service requires them
        // on write, for the reason ProviderEndpointPolicy re-checks an SMTP host: a stored row can
        // predate a rule. A missing id would be a 400 from SendGrid on one kind of mail only,
        // which looks like an outage for that one mail; refusing to build the sender turns that
        // into one ERROR and the system default.
        EmailProviderSettings.Templates templates = block.templates();
        if (templates == null || !templates.isComplete()) {
            throw new ProviderNotUsableException(
                    "sendgrid.templates must name all five transactional templates");
        }
        SendGridSettings resolved = new SendGridSettings(
                apiUrl(),
                secrets.get(SECRET_API_KEY),
                require(settings.fromAddress(), "fromAddress"),
                orPlatform(settings.fromName(), mailProperties.fromName()),
                orPlatform(settings.logoImageUrl(), mailProperties.logoImageUrl()),
                new SendGridSettings.Templates(
                        templates.passwordReset(),
                        templates.reinvitation(),
                        templates.defaultInvitation(),
                        templates.superUserInvitation(),
                        templates.stateAdminInvitation()));
        return new SendGridMailSender(resolved, webClientBuilder);
    }

    /**
     * The platform's API root. A deployment whose system default is SMTP may carry no
     * {@code notification.mail.sendgrid} block at all, and a tenant on SendGrid must still work
     * there, so the record's own default stands in.
     */
    private String apiUrl() {
        MailProperties.SendGrid platform = mailProperties.sendgrid();
        return platform == null ? MailProperties.SendGrid.DEFAULT_API_URL : platform.apiUrl();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProviderNotUsableException(field + " is required but is not set");
        }
        return value.trim();
    }

    private static String orPlatform(String tenantValue, String platformValue) {
        return (tenantValue == null || tenantValue.isBlank()) ? platformValue : tenantValue.trim();
    }
}
