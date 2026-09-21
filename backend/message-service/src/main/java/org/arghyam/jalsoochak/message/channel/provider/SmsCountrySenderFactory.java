package org.arghyam.jalsoochak.message.channel.provider;

import org.arghyam.jalsoochak.message.config.SmsCountryProperties;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * PER-TENANT-PROVIDERS: builds one tenant's {@link SmsCountrySender} from its stored settings and
 * its decrypted credentials (O2-2).
 *
 * <p>Registered unconditionally, unlike the system default bean in
 * {@code SystemDefaultProviders}: the factories exist so that several accounts can coexist, so
 * gating one on
 * {@code notification.sms.provider} — which now names only the <em>system default</em> provider
 * (O2-4) — would stop a tenant using SMSCountry because the platform does not.
 *
 * <p>The base URL comes from {@code smscountry.base-url} rather than from the settings, and the
 * credentials from the secret store rather than from the settings, so nothing a state admin writes
 * can choose where an auth token is sent (O2-8, O2-13).
 *
 * <p>Every check here is a build-time one, so a failure is a {@link ProviderNotUsableException}
 * that leaves the tenant on the system default with an ERROR rather than stopping its OTPs (O2-9).
 */
@Component
public class SmsCountrySenderFactory implements SmsSenderFactory {

    /**
     * The names {@code SmsProviderType.SMSCOUNTRY.getRequiredSecretNames()} declares, which is what
     * {@code TenantChannelProviders} has already resolved by the time {@link #create} is called.
     * Duplicated because the enum is kept byte-identical to tenant-service's twin;
     * {@code SmsCountrySenderFactoryTest} asserts the two agree.
     */
    static final String SECRET_AUTH_KEY = "authKey";
    static final String SECRET_AUTH_TOKEN = "authToken";

    private final WebClient.Builder webClientBuilder;
    private final SmsCountryProperties properties;
    private final boolean dryRun;

    public SmsCountrySenderFactory(WebClient.Builder webClientBuilder,
            SmsCountryProperties properties,
            @Value("${notifications.sms.dry-run:false}") boolean dryRun) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.dryRun = dryRun;
    }

    @Override
    public SmsProviderType providerId() {
        return SmsProviderType.SMSCOUNTRY;
    }

    @Override
    public SmsSender create(SmsProviderSettings settings, TenantSecrets secrets) {
        SmsProviderSettings.SmsCountry block = settings == null ? null : settings.smscountry();
        if (block == null) {
            throw new ProviderNotUsableException("SMS settings carry no 'smscountry' block");
        }
        // The sender and DLT ids are checked although tenant-service requires them on write, for
        // the reason ProviderEndpointPolicy re-checks an SMTP host: a stored row can predate a
        // rule. A blank one is rejected by SMSCountry per message, which would look like an
        // outage; refusing to build the sender turns that into one ERROR and the system default.
        SmsCountrySettings resolved = new SmsCountrySettings(
                properties.baseUrl(),
                secrets.get(SECRET_AUTH_KEY),
                secrets.get(SECRET_AUTH_TOKEN),
                require(block.senderId(), "smscountry.senderId"),
                require(block.dltPrincipalEntityId(), "smscountry.dltPrincipalEntityId"),
                require(block.dltTemplateId(), "smscountry.dltTemplateId"),
                require(block.dltHeaderId(), "smscountry.dltHeaderId"),
                block.otpTemplateOrDefault());
        // Compiles the OTP template, so an unrenderable one fails here rather than per send.
        return new SmsCountrySender(webClientBuilder, resolved, dryRun);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProviderNotUsableException(field + " is required but is not set");
        }
        return value.trim();
    }
}
