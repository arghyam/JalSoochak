package org.arghyam.jalsoochak.message.config;

import org.arghyam.jalsoochak.message.channel.EmailSender;
import org.arghyam.jalsoochak.message.channel.SendGridMailSender;
import org.arghyam.jalsoochak.message.channel.SendGridSettings;
import org.arghyam.jalsoochak.message.channel.SmsCountryService;
import org.arghyam.jalsoochak.message.channel.SmsCountrySettings;
import org.arghyam.jalsoochak.message.channel.SmsSender;
import org.arghyam.jalsoochak.message.channel.SmtpMailSender;
import org.arghyam.jalsoochak.message.channel.SmtpSettings;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The system default senders — the ones the platform's own accounts send through.
 *
 * <p>PER-TENANT-PROVIDERS: {@code SendGridMailSender}, {@code SmtpMailSender} and
 * {@code SmsCountryService} used to be {@code @Component}s carrying these
 * {@code @ConditionalOnProperty} annotations themselves. They are plain classes now, so that
 * several accounts can coexist (O2-2), and the conditions move here unchanged:
 * {@code notification.mail.provider} and {@code notification.sms.provider} still select the
 * adapter, they just now mean "the system default provider" rather than "the only provider"
 * (O2-4). A provider name neither condition matches still leaves no bean and still stops the
 * context, exactly as it did.
 *
 * <p>These beans are what every send used before the feature existed and still use when the flag
 * is off, when the event carries no tenant, when the tenant has no settings, and when its settings
 * cannot be built (O2-9) — so they are what makes the feature behaviour-neutral until a state
 * configures itself. Their fail-fast checks are therefore the adapters' former constructor checks,
 * kept verbatim and still running at context startup.
 *
 * <p>Replaces {@code MailConfig} and {@code SmsConfig}, whose
 * {@code @EnableConfigurationProperties} they fold in, so that the platform's own accounts are
 * assembled in one file.
 */
@Configuration
@EnableConfigurationProperties({MailProperties.class, SmsCountryProperties.class})
public class SystemDefaultProviders {

    /**
     * @throws IllegalStateException if the SendGrid block, its API key or its templates are
     *         missing — the three checks the former {@code SendGridMailSender} constructor made,
     *         with their messages unchanged
     */
    @Bean
    @ConditionalOnProperty(name = "notification.mail.provider", havingValue = "sendgrid", matchIfMissing = true)
    public EmailSender systemDefaultSendGridSender(MailProperties mailProperties,
            WebClient.Builder webClientBuilder) {
        MailProperties.SendGrid sendgrid = mailProperties.sendgrid();
        if (sendgrid == null) {
            throw new IllegalStateException(
                    "Missing SendGrid configuration: notification.mail.sendgrid must be configured when provider=sendgrid");
        }
        if (sendgrid.apiKey() == null || sendgrid.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Missing SendGrid API key: set SENDGRID_API_KEY environment variable when provider=sendgrid");
        }
        MailProperties.Templates templates = sendgrid.templates();
        if (templates == null) {
            throw new IllegalStateException(
                    "Missing SendGrid templates: notification.mail.sendgrid.templates must be configured when provider=sendgrid");
        }
        SendGridSettings settings = new SendGridSettings(
                sendgrid.apiUrl(),
                sendgrid.apiKey(),
                mailProperties.fromAddress(),
                mailProperties.fromName(),
                mailProperties.logoImageUrl(),
                new SendGridSettings.Templates(
                        templates.passwordReset(),
                        templates.reinvitation(),
                        templates.defaultInvitation(),
                        templates.superUserInvitation(),
                        templates.stateAdminInvitation()));
        return new SendGridMailSender(settings, webClientBuilder);
    }

    /**
     * Pairs the platform's identity with Spring Boot's auto-configured {@code spring.mail.*}
     * sender. Missing templates are deliberately not checked here: they failed at the first send
     * before, and {@code SmtpMailSender} still fails there.
     */
    @Bean
    @ConditionalOnProperty(name = "notification.mail.provider", havingValue = "smtp")
    public EmailSender systemDefaultSmtpSender(MailProperties mailProperties,
            JavaMailSender javaMailSender) {
        MailProperties.Smtp smtp = mailProperties.smtp();
        SmtpSettings settings = new SmtpSettings(
                mailProperties.fromAddress(),
                mailProperties.logoImageUrl(),
                smtp == null ? null : smtp.templates());
        return new SmtpMailSender(settings, javaMailSender);
    }

    /**
     * @throws IllegalStateException if {@code smscountry.sender-id} is not set — the one fail-fast
     *         the former {@code @Value("${smscountry.sender-id}")} gave, with no default, kept on
     *         the path where it applies. The auth key and token are deliberately not checked:
     *         they defaulted to empty before and a deployment that has not set them yet must still
     *         start.
     */
    @Bean
    @ConditionalOnProperty(name = "notification.sms.provider", havingValue = "smscountry", matchIfMissing = true)
    public SmsSender systemDefaultSmsSender(WebClient.Builder webClientBuilder,
            SmsCountryProperties properties,
            @Value("${notifications.sms.dry-run:false}") boolean dryRun) {
        if (properties.senderId() == null || properties.senderId().isBlank()) {
            throw new IllegalStateException(
                    "smscountry.sender-id must not be blank; set SMSCOUNTRY_SENDER_ID");
        }
        SmsCountrySettings settings = new SmsCountrySettings(
                properties.baseUrl(),
                properties.authKey(),
                properties.authToken(),
                properties.senderId(),
                properties.dltPrincipalEntityId(),
                properties.dltTemplateId(),
                properties.dltHeaderId(),
                // The platform account keeps the one hard-coded text it has always sent. It is not
                // a property: the message must match a DLT registration, so changing it is a
                // registration change, not a restart.
                SmsProviderSettings.SmsCountry.DEFAULT_OTP_TEMPLATE);
        return new SmsCountryService(webClientBuilder, settings, dryRun);
    }
}
