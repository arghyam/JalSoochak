package org.arghyam.jalsoochak.message.config;

import org.arghyam.jalsoochak.message.channel.SmsCountryService;
import org.arghyam.jalsoochak.message.channel.SmsSender;
import org.arghyam.jalsoochak.message.channel.SmsCountrySettings;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The system default SMS sender — the one the platform's own SMSCountry account sends through.
 *
 * <p>PER-TENANT-PROVIDERS: {@code SmsCountryService} used to be a {@code @Component} carrying this
 * {@code @ConditionalOnProperty} itself. It is a plain class now, so that several accounts can
 * coexist (O2-2), and the condition moves here unchanged: {@code notification.sms.provider} still
 * selects the adapter, it just now means "the system default provider" rather than "the only
 * provider" (O2-4).
 *
 * <p>This bean is what every send used before the feature existed and still uses when the flag is
 * off, when the event carries no tenant, when the tenant has no settings, and when its settings
 * cannot be built (O2-9) — so it is the thing that makes the feature behaviour-neutral until a
 * state configures itself.
 *
 * <p>Follows {@code MailConfig}'s shape. The email half joins it as
 * {@code SystemDefaultProviders} when the email adapters are converted.
 */
@Configuration
@EnableConfigurationProperties(SmsCountryProperties.class)
public class SmsConfig {

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
