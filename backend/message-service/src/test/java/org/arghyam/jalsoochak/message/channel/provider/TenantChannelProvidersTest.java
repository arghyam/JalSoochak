package org.arghyam.jalsoochak.message.channel.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.message.config.PerTenantProviderProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.arghyam.jalsoochak.message.exception.SecretCryptoException;
import org.arghyam.jalsoochak.message.repository.TenantProviderConfigRepository;
import org.arghyam.jalsoochak.message.security.ProviderEndpointPolicy;
import org.arghyam.jalsoochak.message.service.TenantSecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * PER-TENANT-PROVIDERS: unit tests for {@link TenantChannelProviders}.
 *
 * <p>The behaviour under test is almost entirely about what happens when something is wrong, so
 * most of these assert a fallback: O2-9 says a configuration problem must never be worse for the
 * tenant than not configuring anything at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantChannelProviders Tests")
class TenantChannelProvidersTest {

    private static final TenantRef TENANT = new TenantRef(7, "MP");
    private static final TenantRef OTHER_TENANT = new TenantRef(8, "TR");

    @Mock
    private TenantProviderConfigRepository configRepository;

    @Mock
    private TenantSecretResolver secretResolver;

    @Mock
    private ProviderEndpointPolicy endpointPolicy;

    @Mock
    private EmailSender systemDefaultEmail;

    @Mock
    private SmsSender systemDefaultSms;

    @Mock
    private EmailSender tenantEmail;

    @Mock
    private SmsSender tenantSms;

    private PerTenantProviderProperties properties;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new PerTenantProviderProperties();
        properties.setEnabled(true);
        meterRegistry = new SimpleMeterRegistry();
    }

    private TenantChannelProviders providers(List<EmailSenderFactory> emailFactories,
            List<SmsSenderFactory> smsFactories) {
        return new TenantChannelProviders(configRepository, secretResolver, endpointPolicy, properties,
                meterRegistry, emailFactories, smsFactories, systemDefaultEmail, systemDefaultSms);
    }

    /** No factory is registered, which is exactly the state this service ships in today. */
    private TenantChannelProviders providers() {
        return providers(List.of(), List.of());
    }

    private EmailSenderFactory emailFactory(EmailProviderType provider, EmailSender sender) {
        return new EmailSenderFactory() {
            @Override
            public EmailProviderType providerId() {
                return provider;
            }

            @Override
            public EmailSender create(EmailProviderSettings settings, TenantSecrets secrets) {
                return sender;
            }
        };
    }

    private SmsSenderFactory smsFactory(SmsProviderType provider, SmsSender sender) {
        return new SmsSenderFactory() {
            @Override
            public SmsProviderType providerId() {
                return provider;
            }

            @Override
            public SmsSender create(SmsProviderSettings settings, TenantSecrets secrets) {
                return sender;
            }
        };
    }

    private static EmailProviderSettings sendGridSettings() {
        return new EmailProviderSettings(EmailProviderType.SENDGRID.getWireName(), "noreply@mp.gov.in", "MP", null,
                new EmailProviderSettings.SendGrid(new EmailProviderSettings.Templates(
                        "d-1", "d-2", "d-3", "d-4", "d-5")),
                null);
    }

    private static EmailProviderSettings smtpSettings() {
        return new EmailProviderSettings(EmailProviderType.SMTP.getWireName(), "noreply@mp.gov.in", "MP", null, null,
                new EmailProviderSettings.Smtp("smtp.mp.gov.in", 587, "mailer", true));
    }

    private static SmsProviderSettings smsCountrySettings() {
        return new SmsProviderSettings(SmsProviderType.SMSCOUNTRY.getWireName(),
                new SmsProviderSettings.SmsCountry("MPJLSK", "pe-1", "tpl-1", "hdr-1", null));
    }

    private void emailSecretsResolve() {
        lenient().when(secretResolver.resolveAll(eq(TENANT), eq(MessagingChannel.EMAIL), any()))
                .thenReturn(Optional.of(TenantSecrets.of(MessagingChannel.EMAIL, Map.of("apiKey", "k"))));
    }

    private double countFor(String channel, String outcome) {
        return meterRegistry.find(TenantChannelProviders.METRIC_RESOLUTION)
                .tag("channel", channel).tag("outcome", outcome).counters()
                .stream().mapToDouble(c -> c.count()).sum();
    }

    /** Every distinct {@code provider} tag the channel's counters were given. */
    private List<String> providerTagsFor(String channel) {
        return meterRegistry.find(TenantChannelProviders.METRIC_RESOLUTION)
                .tag("channel", channel).counters()
                .stream().map(c -> c.getId().getTag("provider")).distinct().toList();
    }

    // ── the flag ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with the flag off nothing is read and the system default is used")
    void flagOffUsesTheSystemDefault() {
        properties.setEnabled(false);
        TenantChannelProviders providers = providers();

        assertThat(providers.emailFor(TENANT)).isSameAs(systemDefaultEmail);
        assertThat(providers.smsFor(TENANT)).isSameAs(systemDefaultSms);

        // The rollback path must not depend on the database being reachable.
        verifyNoInteractions(configRepository, secretResolver, endpointPolicy);
    }

    @Test
    @DisplayName("an event with no tenant uses the system default")
    void noTenantUsesTheSystemDefault() {
        TenantChannelProviders providers = providers();

        assertThat(providers.emailFor(TenantRef.NONE)).isSameAs(systemDefaultEmail);
        assertThat(providers.smsFor(null)).isSameAs(systemDefaultSms);
        // A super-user email belongs to no state, so there is nothing to resolve (O2-7).
        assertThat(providers.emailFor(new TenantRef(null, "MP"))).isSameAs(systemDefaultEmail);

        verifyNoInteractions(configRepository, secretResolver);
    }

    // ── happy path ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a tenant with usable settings gets its own sender")
    void configuredTenantGetsItsOwnSender() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        emailSecretsResolve();

        TenantChannelProviders providers = providers(
                List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of());

        assertThat(providers.emailFor(TENANT)).isSameAs(tenantEmail);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_TENANT)).isEqualTo(1);
    }

    @Test
    @DisplayName("an SMS tenant with usable settings gets its own sender")
    void configuredSmsTenantGetsItsOwnSender() {
        when(configRepository.findSmsSettings(TENANT.id())).thenReturn(Optional.of(smsCountrySettings()));
        when(secretResolver.resolveAll(eq(TENANT), eq(MessagingChannel.SMS), any()))
                .thenReturn(Optional.of(TenantSecrets.of(MessagingChannel.SMS,
                        Map.of("authKey", "k", "authToken", "t"))));

        TenantChannelProviders providers = providers(
                List.of(), List.of(smsFactory(SmsProviderType.SMSCOUNTRY, tenantSms)));

        assertThat(providers.smsFor(TENANT)).isSameAs(tenantSms);
        assertThat(countFor("sms", TenantChannelProviders.OUTCOME_TENANT)).isEqualTo(1);
    }

    @Test
    @DisplayName("SMTP settings are endpoint-checked before the sender is built")
    void smtpSettingsAreEndpointChecked() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(smtpSettings()));
        when(secretResolver.resolveAll(eq(TENANT), eq(MessagingChannel.EMAIL), any()))
                .thenReturn(Optional.of(TenantSecrets.of(MessagingChannel.EMAIL, Map.of("password", "p"))));

        TenantChannelProviders providers = providers(
                List.of(emailFactory(EmailProviderType.SMTP, tenantEmail)), List.of());

        assertThat(providers.emailFor(TENANT)).isSameAs(tenantEmail);
        verify(endpointPolicy).requireUsableSmtpHost(any());
    }

    @Test
    @DisplayName("SendGrid settings are not endpoint-checked: the API host is a system property")
    void sendGridIsNotEndpointChecked() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        emailSecretsResolve();

        providers(List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of())
                .emailFor(TENANT);

        verifyNoInteractions(endpointPolicy);
    }

    // ── fallback (O2-9) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a tenant with no settings uses the system default")
    void unconfiguredTenantUsesTheSystemDefault() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.empty());

        assertThat(providers().emailFor(TENANT)).isSameAs(systemDefaultEmail);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_SYSTEM_DEFAULT)).isEqualTo(1);
        verifyNoInteractions(secretResolver);
    }

    @Test
    @DisplayName("settings naming no provider at all fall back and are counted as a fallback")
    void absentProviderFallsBack() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(
                new EmailProviderSettings(null, "noreply@mp.gov.in", null, null, null, null)));

        assertThat(providers().emailFor(TENANT)).isSameAs(systemDefaultEmail);
        // Distinguished from "no settings": one is a tenant that never configured anything, the
        // other is a tenant that did and is not getting it.
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_FALLBACK)).isEqualTo(1);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_SYSTEM_DEFAULT)).isZero();
    }

    @Test
    @DisplayName("a provider this deployment does not know is counted as a fallback under its own name")
    void unknownProviderFallsBackUnderItsOwnName() {
        // A settings row written by a newer tenant-service. Binding provider as the enum would have
        // failed the whole row in the repository, and the tenant would have been indistinguishable
        // from one that configured nothing; the whole point of outcome=fallback is telling them
        // apart, so an operator can see a tenant that configured a provider and is not using it.
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(
                new EmailProviderSettings("mailgun", "noreply@mp.gov.in", null, null, null, null)));

        assertThat(providers().emailFor(TENANT)).isSameAs(systemDefaultEmail);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_FALLBACK)).isEqualTo(1);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_SYSTEM_DEFAULT)).isZero();
        assertThat(providerTagsFor("email")).containsExactly("mailgun");
    }

    @Test
    @DisplayName("an SMS provider this deployment does not know falls back under its own name")
    void unknownSmsProviderFallsBackUnderItsOwnName() {
        when(configRepository.findSmsSettings(TENANT.id())).thenReturn(Optional.of(
                new SmsProviderSettings("twilio", null)));

        assertThat(providers().smsFor(TENANT)).isSameAs(systemDefaultSms);
        assertThat(countFor("sms", TenantChannelProviders.OUTCOME_FALLBACK)).isEqualTo(1);
        assertThat(providerTagsFor("sms")).containsExactly("twilio");
    }

    @Test
    @DisplayName("a stored provider name too malformed to be a tag value does not become one")
    void malformedProviderNameIsNotUsedAsATag() {
        // The name still reaches the operator through the ERROR line; what it must not do is give
        // an unbounded string a place in the metric's cardinality.
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(
                new EmailProviderSettings("send grid; drop table", "noreply@mp.gov.in", null, null,
                        null, null)));

        assertThat(providers().emailFor(TENANT)).isSameAs(systemDefaultEmail);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_FALLBACK)).isEqualTo(1);
        assertThat(providerTagsFor("email"))
                .containsExactly(TenantChannelProviders.PROVIDER_UNSUPPORTED);
    }

    @Test
    @DisplayName("settings naming a provider with no registered factory fall back")
    void noRegisteredFactoryFallsBack() {
        // The state this service ships in: the ports exist, no adapter implements them yet.
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));

        assertThat(providers().emailFor(TENANT)).isSameAs(systemDefaultEmail);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_FALLBACK)).isEqualTo(1);
    }

    @Test
    @DisplayName("settings whose provider block is absent fall back")
    void missingProviderBlockFallsBack() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(
                new EmailProviderSettings(EmailProviderType.SENDGRID.getWireName(), "noreply@mp.gov.in",
                        null, null, null, null)));

        assertThat(providers(List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of())
                .emailFor(TENANT)).isSameAs(systemDefaultEmail);
    }

    @Test
    @DisplayName("a missing credential falls back rather than failing the send")
    void missingCredentialFallsBack() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        when(secretResolver.resolveAll(eq(TENANT), eq(MessagingChannel.EMAIL), any()))
                .thenReturn(Optional.empty());

        assertThat(providers(List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of())
                .emailFor(TENANT)).isSameAs(systemDefaultEmail);
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_FALLBACK)).isEqualTo(1);
    }

    @Test
    @DisplayName("an undecryptable credential falls back rather than propagating")
    void undecryptableCredentialFallsBack() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        when(secretResolver.resolveAll(eq(TENANT), eq(MessagingChannel.EMAIL), any()))
                .thenThrow(new SecretCryptoException("Failed to decrypt secret [tenantId=7]"));

        assertThat(providers(List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of())
                .emailFor(TENANT)).isSameAs(systemDefaultEmail);
    }

    @Test
    @DisplayName("a refused SMTP endpoint falls back")
    void refusedEndpointFallsBack() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(smtpSettings()));
        doThrow(new ProviderNotUsableException("smtp host 'smtp.mp.gov.in' resolves to an address that"
                + " is not reachable from the public internet"))
                .when(endpointPolicy).requireUsableSmtpHost(any());

        assertThat(providers(List.of(emailFactory(EmailProviderType.SMTP, tenantEmail)), List.of())
                .emailFor(TENANT)).isSameAs(systemDefaultEmail);
        // Refused before any credential is read: the password is not decrypted for a host that is
        // not going to receive it.
        verifyNoInteractions(secretResolver);
    }

    @Test
    @DisplayName("a factory that throws falls back rather than propagating")
    void throwingFactoryFallsBack() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        emailSecretsResolve();
        EmailSenderFactory exploding = new EmailSenderFactory() {
            @Override
            public EmailProviderType providerId() {
                return EmailProviderType.SENDGRID;
            }

            @Override
            public EmailSender create(EmailProviderSettings settings, TenantSecrets secrets) {
                throw new IllegalStateException("bad template id");
            }
        };

        assertThat(providers(List.of(exploding), List.of()).emailFor(TENANT))
                .isSameAs(systemDefaultEmail);
    }

    // ── caching (O2-3) and eviction (O2-10) ─────────────────────────────────────

    @Test
    @DisplayName("a resolved provider is cached, so the settings are read once")
    void resolvedProviderIsCached() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        emailSecretsResolve();
        TenantChannelProviders providers = providers(
                List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of());

        assertThat(providers.emailFor(TENANT)).isSameAs(tenantEmail);
        assertThat(providers.emailFor(TENANT)).isSameAs(tenantEmail);
        assertThat(providers.emailFor(TENANT)).isSameAs(tenantEmail);

        verify(configRepository, times(1)).findEmailSettings(TENANT.id());
        // Every call is still counted: the metric measures sends, not cache misses.
        assertThat(countFor("email", TenantChannelProviders.OUTCOME_TENANT)).isEqualTo(3);
    }

    @Test
    @DisplayName("the decision to use the system default is cached too")
    void systemDefaultDecisionIsCached() {
        // During rollout most tenants have no settings. Two queries per message for each of them
        // would be the feature's whole cost paid by the tenants it does nothing for.
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.empty());
        TenantChannelProviders providers = providers();

        providers.emailFor(TENANT);
        providers.emailFor(TENANT);

        verify(configRepository, times(1)).findEmailSettings(TENANT.id());
    }

    @Test
    @DisplayName("eviction on the relevant channel forces a rebuild")
    void evictionForcesARebuild() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        emailSecretsResolve();
        TenantChannelProviders providers = providers(
                List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail)), List.of());

        providers.emailFor(TENANT);
        providers.evict(TENANT.id(), MessagingChannel.EMAIL);
        providers.emailFor(TENANT);

        verify(configRepository, times(2)).findEmailSettings(TENANT.id());
    }

    @Test
    @DisplayName("evicting one channel leaves the other cached")
    void evictionIsPerChannel() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.empty());
        when(configRepository.findSmsSettings(TENANT.id())).thenReturn(Optional.empty());
        TenantChannelProviders providers = providers();

        providers.emailFor(TENANT);
        providers.smsFor(TENANT);
        providers.evict(TENANT.id(), MessagingChannel.SMS);
        providers.emailFor(TENANT);
        providers.smsFor(TENANT);

        verify(configRepository, times(1)).findEmailSettings(TENANT.id());
        verify(configRepository, times(2)).findSmsSettings(TENANT.id());
    }

    @Test
    @DisplayName("evicting one tenant leaves another tenant cached")
    void evictionIsPerTenant() {
        when(configRepository.findEmailSettings(anyInt())).thenReturn(Optional.empty());
        TenantChannelProviders providers = providers();

        providers.emailFor(TENANT);
        providers.emailFor(OTHER_TENANT);
        providers.evict(TENANT.id(), MessagingChannel.EMAIL);
        providers.emailFor(TENANT);
        providers.emailFor(OTHER_TENANT);

        verify(configRepository, times(2)).findEmailSettings(TENANT.id());
        verify(configRepository, times(1)).findEmailSettings(OTHER_TENANT.id());
    }

    @Test
    @DisplayName("evicting each channel clears both caches")
    void evictingEachChannelClearsBothCaches() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.empty());
        when(configRepository.findSmsSettings(TENANT.id())).thenReturn(Optional.empty());
        TenantChannelProviders providers = providers();

        providers.emailFor(TENANT);
        providers.smsFor(TENANT);
        providers.evict(TENANT.id(), MessagingChannel.EMAIL);
        providers.evict(TENANT.id(), MessagingChannel.SMS);
        providers.emailFor(TENANT);
        providers.smsFor(TENANT);

        verify(configRepository, times(2)).findEmailSettings(TENANT.id());
        verify(configRepository, times(2)).findSmsSettings(TENANT.id());
    }

    @Test
    @DisplayName("a null tenant id or channel is ignored by evict")
    void evictIgnoresNulls() {
        TenantChannelProviders providers = providers();

        providers.evict(null, MessagingChannel.EMAIL);
        providers.evict(TENANT.id(), null);

        verifyNoInteractions(configRepository);
    }

    @Test
    @DisplayName("an expired entry is rebuilt, so a missed eviction event self-heals")
    void ttlExpiryRebuilds() throws Exception {
        properties.setCacheTtl(Duration.ofMillis(30));
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.empty());
        TenantChannelProviders providers = providers();

        providers.emailFor(TENANT);
        Thread.sleep(80);
        providers.emailFor(TENANT);

        verify(configRepository, times(2)).findEmailSettings(TENANT.id());
    }

    @Test
    @DisplayName("a replaced sender that is AutoCloseable is closed")
    void replacedCloseableSenderIsClosed() {
        // None of today's adapters hold a connection, but one that does must not leak an instance
        // every time a state edits its settings — a leak that stays invisible until it exhausts
        // something.
        class CloseableEmailSender implements EmailSender, AutoCloseable {
            private boolean closed;

            @Override
            public void send(org.arghyam.jalsoochak.message.dto.MailRequest request) {
            }

            @Override
            public void close() {
                closed = true;
            }
        }
        CloseableEmailSender sender = new CloseableEmailSender();
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(sendGridSettings()));
        emailSecretsResolve();
        TenantChannelProviders providers = providers(
                List.of(emailFactory(EmailProviderType.SENDGRID, sender)), List.of());

        providers.emailFor(TENANT);
        providers.evict(TENANT.id(), MessagingChannel.EMAIL);
        // Caffeine runs removal listeners on the common pool; an immediate read forces maintenance.
        providers.emailFor(TENANT);

        assertThat(sender.closed).isTrue();
    }

    // ── wiring ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two factories claiming the same provider is a startup failure")
    void duplicateFactoriesAreAStartupFailure() {
        // Silently keeping one of them would mean a tenant's mail going through whichever bean the
        // classpath happened to order first.
        assertThatThrownBy(() -> providers(
                List.of(emailFactory(EmailProviderType.SENDGRID, tenantEmail),
                        emailFactory(EmailProviderType.SENDGRID, systemDefaultEmail)),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two sender factories claim provider");
    }

    @Test
    @DisplayName("a secret is never resolved for a channel the tenant has no settings on")
    void secretsAreNotReadWithoutSettings() {
        when(configRepository.findSmsSettings(TENANT.id())).thenReturn(Optional.empty());

        providers().smsFor(TENANT);

        verify(secretResolver, never()).resolveAll(any(), any(), any());
    }

    @Test
    @DisplayName("the provider's own required names are what is asked of the resolver")
    void resolverIsAskedForTheProvidersOwnSecretNames() {
        when(configRepository.findEmailSettings(TENANT.id())).thenReturn(Optional.of(smtpSettings()));
        when(secretResolver.resolveAll(eq(TENANT), eq(MessagingChannel.EMAIL), eq(Set.of("password"))))
                .thenReturn(Optional.of(TenantSecrets.of(MessagingChannel.EMAIL, Map.of("password", "p"))));

        providers(List.of(emailFactory(EmailProviderType.SMTP, tenantEmail)), List.of()).emailFor(TENANT);

        // SMTP asks for "password" only, never SendGrid's "apiKey".
        verify(secretResolver).resolveAll(TENANT, MessagingChannel.EMAIL, Set.of("password"));
    }
}
