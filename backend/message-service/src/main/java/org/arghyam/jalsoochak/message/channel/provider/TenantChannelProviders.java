package org.arghyam.jalsoochak.message.channel.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.arghyam.jalsoochak.message.config.PerTenantProviderProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.arghyam.jalsoochak.message.repository.TenantProviderConfigRepository;
import org.arghyam.jalsoochak.message.security.ProviderEndpointPolicy;
import org.arghyam.jalsoochak.message.service.TenantSecretResolver;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: answers "which email or SMS sender does this tenant use?".
 *
 * <p>This is the one place the feature is switched on, cached and failed back, so every caller
 * keeps depending only on the {@link EmailSender} and {@link SmsSender} ports (O2-1) and no
 * business logic learns that tenants can have providers at all.
 *
 * <p>Its two callers are {@code AccountEmailService} and {@code NotificationEventRouter}'s SMS
 * branch, which ask per event rather than holding a sender, so a settings change takes effect
 * without a restart. While {@code notification.per-tenant-providers.enabled} is off — which is
 * every deployment today — every lookup returns the system default by the same path a tenant with
 * no settings takes.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>flag off (O2-5), or the event carries no tenant (a super-user email) ⇒ system default,
 *       uncached, because there is nothing tenant-shaped to key on;</li>
 *   <li>cache hit ⇒ whatever was decided last time, including a decision to use the default;</li>
 *   <li>no settings row ⇒ system default;</li>
 *   <li>settings that cannot be built — unknown provider, missing or undecryptable credential,
 *       refused endpoint ⇒ ERROR, a counter, and the system default (O2-9);</li>
 *   <li>otherwise the tenant's own sender.</li>
 * </ol>
 *
 * <p>Failing <em>back</em> rather than closed is the deliberate half of O2-9: a state that
 * mistypes its SendGrid key should have its mail delivered from the platform account and an ERROR
 * in the log, not a silent stop to its operators' password resets. The mirror of that rule is that
 * there is no fallback once a sender exists — an error the provider itself returns takes today's
 * retry and failure path, because falling back after the provider has accepted or rejected a
 * message could deliver it twice.
 *
 * <h2>Caching</h2>
 * <p>One entry per (tenant, channel), held in two typed caches rather than one keyed by a pair.
 * Building a sender costs two queries and, for SMTP, a DNS resolution, none of which belong on the
 * path of every OTP. The outcome is cached whichever way it went, the fallback included: an
 * unconfigured tenant is the common case during rollout and must not pay two queries a message.
 *
 * <p>A build failure is cached too, which means a transient DNS outage pins that tenant to the
 * system default until the entry expires. That is the intended trade: the alternative is re-running
 * a failing DNS lookup on the listener thread for every message the tenant sends. Both the TTL and
 * {@code TENANT_CONFIG_UPDATED} bound it, and the operator's fix — rewriting the settings — evicts
 * the entry as a side effect.
 *
 * <p><b>Why Caffeine and not Redis</b>, although this platform has Redis and uses it for
 * distributed caching elsewhere:
 *
 * <ul>
 *   <li><b>The values are live objects, not data.</b> A cached sender wraps a {@code WebClient} or
 *       a {@code JavaMailSenderImpl} — connection pools, not bytes — so there is nothing to put in
 *       Redis. The same fact is why this cache has a removal listener that closes them.</li>
 *   <li><b>The alternative would cache decrypted credentials.</b> To make Redis useful the cached
 *       thing would have to be the settings plus the resolved secrets, and putting plaintext
 *       provider credentials in Redis gives back exactly what §4 bought: they are encrypted at rest
 *       under a key held only in the environment, so that a stolen database or backup is useless
 *       (S-5). A Redis dump must not be a shortcut past that.</li>
 *   <li><b>It would be a new dependency on the send path.</b> message-service has no Redis client
 *       today, and a network hop in front of every login OTP is a new way for OTPs to fail.</li>
 * </ul>
 *
 * <p>The genuine argument for a shared cache is coherence across replicas, and that is solved
 * instead by {@code TenantConfigUpdatedListener} joining under a fresh consumer group id per
 * instance, so an eviction reaches every replica rather than one (O2-10). Even with Redis, this
 * local cache would still be needed for the senders themselves.
 */
@Component
@Slf4j
public class TenantChannelProviders {

    /** Resolution outcomes, as the {@code outcome} metric tag. */
    static final String OUTCOME_TENANT = "tenant";
    static final String OUTCOME_SYSTEM_DEFAULT = "system_default";
    static final String OUTCOME_FALLBACK = "fallback";

    static final String METRIC_RESOLUTION = "notification.provider.resolution";
    private static final String PROVIDER_NONE = "none";

    /** The {@code provider} tag for a stored name too malformed to be a tag value of its own. */
    static final String PROVIDER_UNSUPPORTED = "unsupported";

    /** What a wire name tenant-service could have written looks like, after normalisation. */
    private static final Pattern TAG_SAFE_PROVIDER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final TenantProviderConfigRepository configRepository;
    private final TenantSecretResolver secretResolver;
    private final ProviderEndpointPolicy endpointPolicy;
    private final PerTenantProviderProperties properties;
    private final MeterRegistry meterRegistry;

    private final Map<EmailProviderType, EmailSenderFactory> emailFactories =
            new EnumMap<>(EmailProviderType.class);
    private final Map<SmsProviderType, SmsSenderFactory> smsFactories =
            new EnumMap<>(SmsProviderType.class);

    /**
     * The platform's own accounts, built by {@code SystemDefaultProviders} from
     * {@code notification.mail.*}, {@code spring.mail.*} and {@code smscountry.*} and selected by
     * {@code notification.mail.provider} and {@code notification.sms.provider}. Injected by type:
     * exactly one bean of each is registered, as before the feature existed, which is what makes
     * this class provably behaviour-neutral while the flag is off (O2-4).
     */
    private final EmailSender systemDefaultEmail;
    private final SmsSender systemDefaultSms;

    private final Cache<Integer, Resolved<EmailSender>> emailCache;
    private final Cache<Integer, Resolved<SmsSender>> smsCache;

    public TenantChannelProviders(TenantProviderConfigRepository configRepository,
            TenantSecretResolver secretResolver,
            ProviderEndpointPolicy endpointPolicy,
            PerTenantProviderProperties properties,
            MeterRegistry meterRegistry,
            List<EmailSenderFactory> emailSenderFactories,
            List<SmsSenderFactory> smsSenderFactories,
            EmailSender systemDefaultEmail,
            SmsSender systemDefaultSms) {
        this.configRepository = configRepository;
        this.secretResolver = secretResolver;
        this.endpointPolicy = endpointPolicy;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.systemDefaultEmail = systemDefaultEmail;
        this.systemDefaultSms = systemDefaultSms;

        for (EmailSenderFactory factory : emailSenderFactories) {
            EmailSenderFactory clash = emailFactories.put(factory.providerId(), factory);
            requireNoClash(clash, factory.providerId().name());
        }
        for (SmsSenderFactory factory : smsSenderFactories) {
            SmsSenderFactory clash = smsFactories.put(factory.providerId(), factory);
            requireNoClash(clash, factory.providerId().name());
        }

        this.emailCache = buildCache();
        this.smsCache = buildCache();

        log.info("[Providers] Per-tenant providers {} [cacheTtl={}, emailFactories={}, smsFactories={}]",
                properties.isEnabled() ? "ENABLED" : "disabled", properties.getCacheTtl(),
                emailFactories.keySet(), smsFactories.keySet());
    }

    /** The email sender {@code tenant} should be served by. Never null. */
    public EmailSender emailFor(TenantRef tenant) {
        if (!usesTenantProviders(tenant)) {
            count(MessagingChannel.EMAIL, PROVIDER_NONE, OUTCOME_SYSTEM_DEFAULT);
            return systemDefaultEmail;
        }
        Resolved<EmailSender> resolved = emailCache.get(tenant.id(), id -> resolveEmail(tenant));
        count(MessagingChannel.EMAIL, resolved.providerId(), resolved.outcome());
        return resolved.sender() != null ? resolved.sender() : systemDefaultEmail;
    }

    /** The SMS sender {@code tenant} should be served by. Never null. */
    public SmsSender smsFor(TenantRef tenant) {
        if (!usesTenantProviders(tenant)) {
            count(MessagingChannel.SMS, PROVIDER_NONE, OUTCOME_SYSTEM_DEFAULT);
            return systemDefaultSms;
        }
        Resolved<SmsSender> resolved = smsCache.get(tenant.id(), id -> resolveSms(tenant));
        count(MessagingChannel.SMS, resolved.providerId(), resolved.outcome());
        return resolved.sender() != null ? resolved.sender() : systemDefaultSms;
    }

    /**
     * Drops one tenant's cached decision for one channel, so the next message rebuilds it.
     *
     * <p>Called by {@code TenantConfigUpdatedListener} when tenant-service reports that this
     * tenant's settings or secrets changed (O2-10).
     *
     * <p>A switch <em>expression</em> rather than an if/else, so that adding the WHATSAPP constant
     * {@link MessagingChannel} already reserves is a compile error here. The bare {@code else} it
     * replaces would have invalidated the SMS cache for it: a good sender dropped, the new channel's
     * entry left stale, and a log line claiming it evicted WHATSAPP.
     */
    public void evict(Integer tenantId, MessagingChannel channel) {
        if (tenantId == null || channel == null) {
            return;
        }
        Cache<Integer, ?> cache = switch (channel) {
            case EMAIL -> emailCache;
            case SMS -> smsCache;
        };
        cache.invalidate(tenantId);
        log.info("[Providers] Evicted cached provider [tenantId={}, channel={}]", tenantId, channel);
    }

    /** Drops every cached decision. For an event that names no channel, and for tests. */
    public void evictAll() {
        emailCache.invalidateAll();
        smsCache.invalidateAll();
        log.info("[Providers] Evicted every cached provider");
    }

    /** Whether the feature is on, exposed so a caller can skip work it would only discard. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    // ── resolution ──────────────────────────────────────────────────────────────

    private boolean usesTenantProviders(TenantRef tenant) {
        // A null id, not a null tenant, is the test: the id is the key of every row this reads, and
        // an event that resolved only to a state code has nothing to look up. TenantRef.NONE — a
        // super-user email, which belongs to no state — takes the same path (O2-7).
        return properties.isEnabled() && tenant != null && tenant.id() != null;
    }

    private Resolved<EmailSender> resolveEmail(TenantRef tenant) {
        Optional<EmailProviderSettings> stored = configRepository.findEmailSettings(tenant.id());
        if (stored.isEmpty()) {
            return Resolved.systemDefault();
        }
        EmailProviderSettings settings = stored.get();
        EmailProviderType provider = settings.providerType();
        String providerId = providerTag(settings.provider());
        try {
            if (provider == null) {
                throw new ProviderNotUsableException(PROVIDER_NONE.equals(providerId)
                        ? "email settings name no provider"
                        : "email settings name provider '" + providerId
                                + "', which this deployment does not support");
            }
            if (settings.blockForProvider() == null) {
                throw new ProviderNotUsableException(
                        "email settings name provider '" + providerId + "' but carry no '"
                                + providerId + "' block");
            }
            EmailSenderFactory factory = emailFactories.get(provider);
            if (factory == null) {
                throw new ProviderNotUsableException("no factory is registered for email provider '"
                        + providerId + "'");
            }
            if (provider == EmailProviderType.SMTP) {
                endpointPolicy.requireUsableSmtpHost(settings.smtp());
            }
            TenantSecrets secrets = requireSecrets(tenant, MessagingChannel.EMAIL,
                    provider.getRequiredSecretNames(), providerId);
            return Resolved.tenant(factory.create(settings, secrets), providerId);
        } catch (RuntimeException e) {
            return fallback(tenant, MessagingChannel.EMAIL, providerId, e);
        }
    }

    private Resolved<SmsSender> resolveSms(TenantRef tenant) {
        Optional<SmsProviderSettings> stored = configRepository.findSmsSettings(tenant.id());
        if (stored.isEmpty()) {
            return Resolved.systemDefault();
        }
        SmsProviderSettings settings = stored.get();
        SmsProviderType provider = settings.providerType();
        String providerId = providerTag(settings.provider());
        try {
            if (provider == null) {
                throw new ProviderNotUsableException(PROVIDER_NONE.equals(providerId)
                        ? "SMS settings name no provider"
                        : "SMS settings name provider '" + providerId
                                + "', which this deployment does not support");
            }
            if (settings.blockForProvider() == null) {
                throw new ProviderNotUsableException(
                        "SMS settings name provider '" + providerId + "' but carry no '"
                                + providerId + "' block");
            }
            SmsSenderFactory factory = smsFactories.get(provider);
            if (factory == null) {
                throw new ProviderNotUsableException("no factory is registered for SMS provider '"
                        + providerId + "'");
            }
            TenantSecrets secrets = requireSecrets(tenant, MessagingChannel.SMS,
                    provider.getRequiredSecretNames(), providerId);
            return Resolved.tenant(factory.create(settings, secrets), providerId);
        } catch (RuntimeException e) {
            return fallback(tenant, MessagingChannel.SMS, providerId, e);
        }
    }

    private TenantSecrets requireSecrets(TenantRef tenant, MessagingChannel channel,
            Set<String> requiredNames, String providerId) {
        return secretResolver.resolveAll(tenant, channel, requiredNames)
                .orElseThrow(() -> new ProviderNotUsableException("provider '" + providerId
                        + "' needs " + requiredNames + " but at least one is not stored"));
    }

    /**
     * The {@code provider} metric tag and log name for a stored wire name: the name itself, or
     * {@link #PROVIDER_NONE} when the settings declare none.
     *
     * <p>A name this deployment does not know still has to reach the operator, because "a tenant
     * configured its own provider and is silently not using it" is exactly what the counter exists
     * to surface. It is a tag value, though, so it is bounded rather than passed through: only a
     * wire name tenant-service could have written — lower case, short, and no punctuation beyond
     * {@code -} and {@code _} — is used as-is, and anything else is counted as
     * {@link #PROVIDER_UNSUPPORTED} so a malformed row cannot grow the metric's cardinality. The
     * ERROR line from {@link #fallback} names the row either way.
     */
    private static String providerTag(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return PROVIDER_NONE;
        }
        String normalised = wireName.trim().toLowerCase(Locale.ROOT);
        return TAG_SAFE_PROVIDER.matcher(normalised).matches() ? normalised : PROVIDER_UNSUPPORTED;
    }

    private <T> Resolved<T> fallback(TenantRef tenant, MessagingChannel channel, String providerId,
            RuntimeException cause) {
        // ERROR, not WARN: a tenant that configured its own provider and is silently not using it is
        // something an operator has to be told about. The exception message names the reason and the
        // location and never a credential, so it is safe to log in full; the stack trace goes to
        // DEBUG because a crypto failure's trace adds nothing an operator can act on.
        log.error("[Providers] {} channel {} provider '{}' could not be built, falling back to the"
                + " system default: {}", tenant, channel, providerId, cause.getMessage());
        log.debug("[Providers] Provider build failure for {} channel {}", tenant, channel, cause);
        return Resolved.fallback(providerId);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────

    private <T> Cache<Integer, Resolved<T>> buildCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(properties.getCacheTtl())
                .maximumSize(properties.getCacheMaxSize())
                .removalListener((Integer tenantId, Resolved<T> resolved, RemovalCause cause) ->
                        closeIfNeeded(tenantId, resolved))
                .build();
    }

    /**
     * Closes a sender that is being replaced (O2-3). None of today's adapters hold a connection —
     * both {@code WebClient}-based ones are stateless and SMTP's {@code JavaMailSenderImpl} opens a
     * transport per send — but a future adapter that does must not leak one every time a state
     * edits its settings, and that leak would be invisible until it exhausted something.
     */
    private <T> void closeIfNeeded(Integer tenantId, Resolved<T> resolved) {
        if (resolved == null || !(resolved.sender() instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("[Providers] Failed to close the replaced sender [tenantId={}]: {}",
                    tenantId, e.getMessage());
        }
    }

    private void count(MessagingChannel channel, String providerId, String outcome) {
        meterRegistry.counter(METRIC_RESOLUTION,
                "channel", channel.name().toLowerCase(Locale.ROOT),
                "provider", providerId,
                "outcome", outcome).increment();
    }

    private static void requireNoClash(Object previous, String providerId) {
        if (previous != null) {
            throw new IllegalStateException("Two sender factories claim provider '" + providerId
                    + "'. Exactly one factory may be registered per provider.");
        }
    }

    /**
     * One cached decision. {@code sender} is null when the decision was to use the system default,
     * so the default bean itself is never cached and can be swapped without stale entries.
     */
    record Resolved<T>(T sender, String providerId, String outcome) {

        static <T> Resolved<T> tenant(T sender, String providerId) {
            return new Resolved<>(sender, providerId, OUTCOME_TENANT);
        }

        static <T> Resolved<T> systemDefault() {
            return new Resolved<>(null, PROVIDER_NONE, OUTCOME_SYSTEM_DEFAULT);
        }

        static <T> Resolved<T> fallback(String providerId) {
            return new Resolved<>(null, providerId, OUTCOME_FALLBACK);
        }
    }
}
