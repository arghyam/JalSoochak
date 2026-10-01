package org.arghyam.jalsoochak.message.channel.provider;

import java.util.regex.Pattern;

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
     * Duplicated because the enum is a copy of tenant-service's, which is what the secret store
     * writes against; the two must declare the same secret names and
     * {@code SmsCountrySenderFactoryTest} asserts they do.
     */
    static final String SECRET_AUTH_KEY = "authKey";
    static final String SECRET_AUTH_TOKEN = "authToken";

    /**
     * What an SMSCountry account key may contain. The key is not only the basic-auth username: it
     * is also the {@code /Accounts/{authKey}/} path segment of every request this account makes,
     * so a value outside this set is a value that decides part of a URL.
     *
     * <p>{@code SmsCountrySender} percent-encodes the segment, which is the defence that matters;
     * this is the second one, and it is here rather than there because a shape this factory refuses
     * costs the tenant one ERROR and the system default (O2-9), while a value that reaches the
     * sender costs it a request to a path SMSCountry will not recognise.
     *
     * <p>The dot is deliberately excluded. It needs no encoding, so {@code ..} would survive
     * encoding unchanged and still traverse; no real account key contains one.
     */
    private static final Pattern AUTH_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

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
                requireAuthKey(secrets.get(SECRET_AUTH_KEY)),
                requireSecret(secrets.get(SECRET_AUTH_TOKEN), SECRET_AUTH_TOKEN),
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

    /**
     * A stored secret is present by the time {@code TenantChannelProviders} calls this — but only
     * by name: {@code TenantSecretResolver.resolveAll} checks that a row exists, not that it
     * decrypts to anything. A blank key would produce {@code /Accounts//SMSes/}, which is a wrong
     * request rather than a refused one.
     *
     * <p>Not trimmed, unlike the settings above: silently altering a credential turns a bad value
     * into a 401 with nothing in the logs to explain it. A key with stray whitespace is refused by
     * {@link #AUTH_KEY_PATTERN} instead, with a message that names the rule.
     */
    private static String requireSecret(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ProviderNotUsableException(
                    "secret '" + name + "' is required but is not set");
        }
        return value;
    }

    /** Never names the value: the key is half of this account's basic-auth pair (S-4). */
    private static String requireAuthKey(String value) {
        String authKey = requireSecret(value, SECRET_AUTH_KEY);
        if (!AUTH_KEY_PATTERN.matcher(authKey).matches()) {
            throw new ProviderNotUsableException("secret '" + SECRET_AUTH_KEY
                    + "' must match " + AUTH_KEY_PATTERN.pattern()
                    + "; it is sent as a path segment of every request");
        }
        return authKey;
    }
}
