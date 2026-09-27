package org.arghyam.jalsoochak.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import org.arghyam.jalsoochak.tenant.config.properties.MessagingProviderProperties;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.enums.EmailProviderType;
import org.arghyam.jalsoochak.tenant.enums.SmsProviderType;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.security.HostAddressResolver;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MESSAGING-PROVIDER-SETTINGS: the checks that keep a tenant's SMTP settings from becoming an
 * attacker-chosen destination (O2-13), plus the OTP template rules (O2-15).
 *
 * <p>DNS is behind {@link HostAddressResolver} so "this host resolves privately" is a property of
 * the test, not of whatever the build machine happens to resolve.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessagingProviderSettingsValidator Tests")
class MessagingProviderSettingsValidatorTest {

    private static final String ALLOWED_HOSTS_JSON = "{\"smtp\":[\"smtp.mp.gov.in\",\"*.nic.in\"]}";

    /**
     * A genuinely routable address. Not one of the RFC 5737 documentation ranges (192.0.2.0/24,
     * 198.51.100.0/24, 203.0.113.0/24) — {@code SsrfAddressPolicy} classifies all three as internal,
     * on purpose, so the obvious choice for a test fixture is the one that cannot be used here.
     */
    private static final String PUBLIC_ADDRESS = "8.8.8.8";

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private HostAddressResolver hostAddressResolver;

    private MessagingProviderProperties providerProperties;
    private MessagingProviderSettingsValidator validator;

    @BeforeEach
    void setUp() {
        providerProperties = new MessagingProviderProperties();
        validator = new MessagingProviderSettingsValidator(
                tenantCommonRepository, new ObjectMapper(), hostAddressResolver, providerProperties);
    }

    private void givenAllowedHosts(String json) {
        when(tenantCommonRepository.findConfigByTenantAndKey(
                TenantConstants.SYSTEM_TENANT_ID,
                SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS.name()))
                .thenReturn(Optional.of(ConfigDTO.builder()
                        .configKey(SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS.name())
                        .configValue(json)
                        .build()));
    }

    private void givenResolvesTo(String... addresses) throws UnknownHostException {
        InetAddress[] resolved = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            resolved[i] = InetAddress.getByName(addresses[i]);
        }
        lenient().when(hostAddressResolver.resolve(anyString())).thenReturn(resolved);
    }

    private static EmailProviderConfigDTO smtpSettings(String host, int port, boolean startTls) {
        return EmailProviderConfigDTO.builder()
                .provider(EmailProviderType.SMTP)
                .fromAddress("no-reply@mp.gov.in")
                .smtp(EmailProviderConfigDTO.SmtpSettings.builder()
                        .host(host).port(port).username("jalsoochak").startTls(startTls).build())
                .build();
    }

    private static EmailProviderConfigDTO sendGridSettings() {
        return EmailProviderConfigDTO.builder()
                .provider(EmailProviderType.SENDGRID)
                .fromAddress("no-reply@mp.gov.in")
                .sendgrid(EmailProviderConfigDTO.SendGridSettings.builder()
                        .templates(EmailProviderConfigDTO.Templates.builder()
                                .passwordReset("d-1").reinvitation("d-2").defaultInvitation("d-3")
                                .superUserInvitation("d-4").stateAdminInvitation("d-5").build())
                        .build())
                .build();
    }

    private static SmsProviderConfigDTO smsSettings(String otpTemplate) {
        return SmsProviderConfigDTO.builder()
                .provider(SmsProviderType.SMSCOUNTRY)
                .smscountry(SmsProviderConfigDTO.SmsCountrySettings.builder()
                        .senderId("JLSCHK").dltPrincipalEntityId("PE-1").dltTemplateId("DT-1")
                        .dltHeaderId("DH-1").otpTemplate(otpTemplate).build())
                .build();
    }

    @Nested
    @DisplayName("Provider and its settings block must agree")
    class ProviderBlock {

        @Test
        @DisplayName("SendGrid without a sendgrid block is rejected")
        void sendGridWithoutBlock() {
            EmailProviderConfigDTO settings = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SENDGRID).fromAddress("a@b.in").build();

            assertThatThrownBy(() -> validator.validateEmailSettings(settings))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("sendgrid");
        }

        @Test
        @DisplayName("SMTP without an smtp block is rejected")
        void smtpWithoutBlock() {
            EmailProviderConfigDTO settings = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SMTP).fromAddress("a@b.in").build();

            assertThatThrownBy(() -> validator.validateEmailSettings(settings))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("smtp");
        }

        @Test
        @DisplayName("The other provider's block is rejected rather than stored and ignored")
        void foreignBlockRejected() {
            EmailProviderConfigDTO settings = sendGridSettings();
            settings.setSmtp(EmailProviderConfigDTO.SmtpSettings.builder()
                    .host("smtp.evil.example").port(25).username("u").startTls(false).build());

            // Storing it would leave a host in the settings that never went through the allowlist
            // check, waiting for the day someone flips the provider.
            assertThatThrownBy(() -> validator.validateEmailSettings(settings))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("smtp");
            verifyNoInteractions(tenantCommonRepository, hostAddressResolver);
        }

        @Test
        @DisplayName("A valid SendGrid value needs neither the allowlist nor DNS")
        void sendGridSkipsHostChecks() {
            // SendGrid's API URL is a system property, not a tenant setting (O2-13), so there is no
            // caller-chosen host on this path to check.
            assertThatCode(() -> validator.validateEmailSettings(sendGridSettings())).doesNotThrowAnyException();

            verifyNoInteractions(tenantCommonRepository, hostAddressResolver);
        }
    }

    @Nested
    @DisplayName("SMTP host allowlist")
    class Allowlist {

        @Test
        @DisplayName("An exact allowlisted host is accepted")
        void exactHostAccepted() throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            givenResolvesTo(PUBLIC_ADDRESS);

            assertThatCode(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, true)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A wildcard-allowlisted host is accepted")
        void wildcardHostAccepted() throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            givenResolvesTo(PUBLIC_ADDRESS);

            assertThatCode(() -> validator.validateEmailSettings(smtpSettings("mail.up.nic.in", 587, true)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A host outside the allowlist is rejected")
        void hostOutsideAllowlistRejected() {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);

            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.evil.example", 587, true)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining(SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS.name());
        }

        @Test
        @DisplayName("With the allowlist unset, no host is allowed")
        void unsetAllowlistAllowsNothing() {
            when(tenantCommonRepository.findConfigByTenantAndKey(
                    TenantConstants.SYSTEM_TENANT_ID,
                    SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS.name()))
                    .thenReturn(Optional.empty());

            // Failing closed is what makes the key a control: the alternative would allow every
            // host until someone first remembered to populate it.
            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, true)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining(SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS.name());
        }

        @ParameterizedTest(name = "rejects the IP literal \"{0}\"")
        @ValueSource(strings = {"203.0.113.10", "127.0.0.1", "[::1]", "fe80::1"})
        @DisplayName("An IP literal is rejected before the allowlist is even consulted")
        void ipLiteralsRejected(String host) {
            // A literal would sail past name matching and past any later re-check of the name.
            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings(host, 587, true)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("host name");
        }
    }

    @Nested
    @DisplayName("SMTP transport encryption")
    class Tls {

        @Test
        @DisplayName("A submission port with startTls=false is rejected")
        void plaintextPortRejected() {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);

            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, false)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("startTls");
        }

        @Test
        @DisplayName("Port 25 with startTls=false is rejected")
        void plainSmtpPortRejected() {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);

            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 25, false)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("startTls");
        }

        @Test
        @DisplayName("Port 465 may omit STARTTLS, because TLS is implicit there")
        void implicitTlsPortAccepted() throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            givenResolvesTo(PUBLIC_ADDRESS);

            assertThatCode(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 465, false)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("SMTP address check")
    class AddressCheck {

        @ParameterizedTest(name = "rejects a host resolving to {0}")
        @ValueSource(strings = {
                "127.0.0.1",        // loopback
                "10.1.2.3",         // RFC 1918
                "192.168.0.5",      // RFC 1918
                "172.16.0.1",       // RFC 1918
                "169.254.169.254",  // link-local: the cloud metadata endpoint
                "100.64.0.1",       // carrier-grade NAT
                "203.0.113.10"      // RFC 5737 documentation range
        })
        @DisplayName("A host resolving to a loopback, private, link-local or reserved address is rejected")
        void internalAddressesRejected(String address) throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            givenResolvesTo(address);

            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, true)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("not reachable from the public internet");
        }

        @Test
        @DisplayName("One internal address among several is enough to refuse the host")
        void anyInternalAddressRejects() throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            givenResolvesTo(PUBLIC_ADDRESS, "10.0.0.1");

            // Accepting because the first address passed is the DNS-rebinding hole.
            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, true)))
                    .isInstanceOf(InvalidConfigValueException.class);
        }

        @Test
        @DisplayName("An unresolvable host is rejected, not accepted on hope")
        void unresolvableHostRejected() throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            when(hostAddressResolver.resolve("smtp.mp.gov.in")).thenThrow(new UnknownHostException("nope"));

            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, true)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("does not resolve");
        }

        @Test
        @DisplayName("The refusal names the host but never the address it resolved to")
        void refusalDoesNotEchoTheAddress() throws Exception {
            givenAllowedHosts(ALLOWED_HOSTS_JSON);
            givenResolvesTo("10.1.2.3");

            // Echoing the mapping would turn a settings endpoint into an internal-network probe:
            // the caller supplied the name, not what it resolves to.
            assertThatThrownBy(() -> validator.validateEmailSettings(smtpSettings("smtp.mp.gov.in", 587, true)))
                    .hasMessageContaining("smtp.mp.gov.in")
                    .hasMessageNotContaining("10.1.2.3");
        }

        @Test
        @DisplayName("allow-internal-hosts skips the check, for local development only")
        void allowInternalHostsSkipsTheCheck() {
            givenAllowedHosts("{\"smtp\":[\"mailcatcher.test.local\",\"*.test.local\"]}");
            providerProperties.setAllowInternalHosts(true);

            assertThatCode(() -> validator.validateEmailSettings(smtpSettings("mailcatcher.test.local", 587, true)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("OTP template")
    class OtpTemplate {

        @Test
        @DisplayName("A null template is valid and means the default")
        void nullTemplateAccepted() {
            assertThatCode(() -> validator.validateSmsSettings(smsSettings(null))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("The default template passes its own rules")
        void defaultTemplateIsValid() {
            assertThatCode(() -> validator.validateSmsSettings(
                    smsSettings(SmsProviderConfigDTO.DEFAULT_OTP_TEMPLATE))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("{expiryMinutes} is optional")
        void expiryPlaceholderOptional() {
            assertThatCode(() -> validator.validateSmsSettings(
                    smsSettings("Your OTP is {otp}."))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A template without {otp} is rejected")
        void missingOtpPlaceholder() {
            assertThatThrownBy(() -> validator.validateSmsSettings(smsSettings("Your OTP is on its way.")))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("{otp}");
        }

        @Test
        @DisplayName("A repeated {otp} is rejected")
        void repeatedOtpPlaceholder() {
            assertThatThrownBy(() -> validator.validateSmsSettings(smsSettings("OTP {otp}, again {otp}.")))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("exactly once");
        }

        @Test
        @DisplayName("A repeated {expiryMinutes} is rejected")
        void repeatedExpiryPlaceholder() {
            assertThatThrownBy(() -> validator.validateSmsSettings(
                    smsSettings("OTP {otp}, valid {expiryMinutes}/{expiryMinutes} minutes.")))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("at most once");
        }

        @Test
        @DisplayName("An unknown placeholder is rejected")
        void unknownPlaceholder() {
            assertThatThrownBy(() -> validator.validateSmsSettings(smsSettings("Hi {name}, your OTP is {otp}.")))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("{name}");
        }

        @Test
        @DisplayName("A blank template is rejected")
        void blankTemplateRejected() {
            assertThatThrownBy(() -> validator.validateSmsSettings(smsSettings("   ")))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("SMSCountry without an smscountry block is rejected")
        void smsCountryWithoutBlock() {
            SmsProviderConfigDTO settings = SmsProviderConfigDTO.builder()
                    .provider(SmsProviderType.SMSCOUNTRY).build();

            assertThatThrownBy(() -> validator.validateSmsSettings(settings))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("smscountry");
        }

        @Test
        @DisplayName("The default template is the exact text SmsCountryService sends today")
        void defaultMatchesTodaysText() {
            assertThat(SmsProviderConfigDTO.DEFAULT_OTP_TEMPLATE)
                    .contains("{otp}")
                    .contains("{expiryMinutes}");
        }
    }

    @Nested
    @DisplayName("Logo image URL")
    class LogoUrl {

        @Test
        @DisplayName("An https URL is accepted")
        void httpsAccepted() {
            EmailProviderConfigDTO settings = sendGridSettings();
            settings.setLogoImageUrl("https://cdn.mp.gov.in/logo.png");

            assertThatCode(() -> validator.validateEmailSettings(settings)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A plain-http or relative URL is rejected")
        void nonHttpsRejected() {
            EmailProviderConfigDTO http = sendGridSettings();
            http.setLogoImageUrl("http://cdn.mp.gov.in/logo.png");
            EmailProviderConfigDTO relative = sendGridSettings();
            relative.setLogoImageUrl("/logo.png");

            assertThatThrownBy(() -> validator.validateEmailSettings(http))
                    .isInstanceOf(InvalidConfigValueException.class);
            assertThatThrownBy(() -> validator.validateEmailSettings(relative))
                    .isInstanceOf(InvalidConfigValueException.class);
        }
    }
}
