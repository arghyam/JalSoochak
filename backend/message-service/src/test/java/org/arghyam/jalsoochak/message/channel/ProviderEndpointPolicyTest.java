package org.arghyam.jalsoochak.message.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.arghyam.jalsoochak.message.config.MessagingProviderProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.MessagingAllowedHosts;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.arghyam.jalsoochak.message.repository.TenantProviderConfigRepository;
import org.arghyam.jalsoochak.message.security.HostAddressResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PER-TENANT-PROVIDERS: unit tests for {@link ProviderEndpointPolicy}.
 *
 * <p>DNS is behind {@link HostAddressResolver} so these never depend on what the build machine
 * resolves — the policy's verdict must be the same on a laptop, in CI and in a cluster.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderEndpointPolicy Tests")
class ProviderEndpointPolicyTest {

    /**
     * 8.8.8.8, not the obvious 203.0.113.1: all three RFC 5737 documentation ranges are classified
     * internal by {@code SsrfAddressPolicy}, on purpose, so the address a test fixture would
     * naturally reach for is unusable here.
     */
    private static final String PUBLIC_ADDRESS = "8.8.8.8";

    private static final String ALLOWED_HOST = "smtp.mp.gov.in";

    @Mock
    private TenantProviderConfigRepository configRepository;

    @Mock
    private HostAddressResolver hostAddressResolver;

    private MessagingProviderProperties providerProperties;
    private ProviderEndpointPolicy policy;

    @BeforeEach
    void setUp() {
        providerProperties = new MessagingProviderProperties();
        policy = new ProviderEndpointPolicy(configRepository, hostAddressResolver, providerProperties);
    }

    private static EmailProviderSettings.Smtp smtp(String host) {
        return new EmailProviderSettings.Smtp(host, 587, "mailer@mp.gov.in", true);
    }

    private void allowlist(String... patterns) {
        lenient().when(configRepository.findAllowedHosts())
                .thenReturn(new MessagingAllowedHosts(List.of(patterns)));
    }

    private void resolvesTo(String host, String... addresses) throws UnknownHostException {
        InetAddress[] resolved = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            resolved[i] = InetAddress.getByName(addresses[i]);
        }
        lenient().when(hostAddressResolver.resolve(host)).thenReturn(resolved);
    }

    @Test
    @DisplayName("an allowlisted host resolving publicly is accepted")
    void allowlistedPublicHostIsAccepted() throws Exception {
        allowlist(ALLOWED_HOST);
        resolvesTo(ALLOWED_HOST, PUBLIC_ADDRESS);

        assertThatCode(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a host removed from the allowlist since the write is refused")
    void hostNoLongerAllowlistedIsRefused() {
        // The re-check is not only about DNS: a super user revoking a relay must take effect on the
        // tenants already using it, not only on the next settings write.
        allowlist("*.nic.in");

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("no longer in the platform's allowed messaging hosts");
    }

    @Test
    @DisplayName("an empty allowlist refuses every host")
    void emptyAllowlistRefusesEveryHost() {
        when(configRepository.findAllowedHosts()).thenReturn(MessagingAllowedHosts.NONE);

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class);
    }

    @Test
    @DisplayName("an allowlisted name resolving to a private address is refused")
    void allowlistedNameResolvingPrivatelyIsRefused() throws Exception {
        allowlist(ALLOWED_HOST);
        resolvesTo(ALLOWED_HOST, "10.0.0.5");

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("not reachable from the public internet");
    }

    @Test
    @DisplayName("one private address among several public ones is still refused")
    void oneInternalAddressAmongManyIsRefused() throws Exception {
        // The DNS-rebinding shape. Accepting because the first address passed would be the hole.
        allowlist(ALLOWED_HOST);
        resolvesTo(ALLOWED_HOST, PUBLIC_ADDRESS, "169.254.169.254");

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class);
    }

    @Test
    @DisplayName("the refusal never echoes the address the host resolved to")
    void refusalNeverEchoesTheResolvedAddress() throws Exception {
        allowlist(ALLOWED_HOST);
        resolvesTo(ALLOWED_HOST, "10.11.12.13");

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class)
                // Echoing it would turn a settings write plus a log read into an internal-network
                // scanner: the caller supplied the name, not the mapping.
                .hasMessageNotContaining("10.11.12.13");
    }

    @Test
    @DisplayName("a host that does not resolve is refused, not accepted hopefully")
    void unresolvableHostIsRefused() throws Exception {
        allowlist(ALLOWED_HOST);
        when(hostAddressResolver.resolve(ALLOWED_HOST)).thenThrow(new UnknownHostException(ALLOWED_HOST));

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("does not resolve");
    }

    @Test
    @DisplayName("an empty resolution result is treated as unresolvable")
    void emptyResolutionIsRefused() throws Exception {
        allowlist(ALLOWED_HOST);
        when(hostAddressResolver.resolve(ALLOWED_HOST)).thenReturn(new InetAddress[0]);

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("does not resolve");
    }

    @Test
    @DisplayName("an IP literal is refused before the allowlist is even consulted")
    void ipLiteralIsRefusedBeforeTheAllowlist() throws Exception {
        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(PUBLIC_ADDRESS)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("must be a host name, not an IP address");
        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp("[::1]")))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("must be a host name, not an IP address");

        verify(configRepository, never()).findAllowedHosts();
        verify(hostAddressResolver, never()).resolve(anyString());
    }

    @Test
    @DisplayName("absent or blank smtp settings are refused")
    void absentSettingsAreRefused() {
        assertThatThrownBy(() -> policy.requireUsableSmtpHost(null))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("no host");
        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp("  ")))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("no host");
    }

    @Test
    @DisplayName("allow-internal-hosts skips the address check but not the allowlist")
    void allowInternalHostsSkipsOnlyTheAddressCheck() throws Exception {
        providerProperties.setAllowInternalHosts(true);
        allowlist(ALLOWED_HOST);

        assertThatCode(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST))).doesNotThrowAnyException();
        verify(hostAddressResolver, never()).resolve(anyString());

        // The allowlist still applies: the escape hatch is for local DNS, not for the control.
        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp("mailcatcher.test")))
                .isInstanceOf(ProviderNotUsableException.class);
    }

    @Test
    @DisplayName("the host is matched case-insensitively and trimmed")
    void hostIsNormalisedBeforeMatching() throws Exception {
        allowlist(ALLOWED_HOST);
        resolvesTo(ALLOWED_HOST, PUBLIC_ADDRESS);

        assertThatCode(() -> policy.requireUsableSmtpHost(smtp("  SMTP.MP.GOV.IN  ")))
                .doesNotThrowAnyException();
        verify(hostAddressResolver).resolve(ALLOWED_HOST);
    }

    @Test
    @DisplayName("the documentation ranges are internal, so 203.0.113.x is not a usable fixture")
    void documentationRangesAreInternal() throws Exception {
        // Recorded as a test because it costs a debug cycle every time someone reaches for the
        // obvious example address.
        allowlist(ALLOWED_HOST);
        resolvesTo(ALLOWED_HOST, "203.0.113.1");

        assertThatThrownBy(() -> policy.requireUsableSmtpHost(smtp(ALLOWED_HOST)))
                .isInstanceOf(ProviderNotUsableException.class);
        assertThat(PUBLIC_ADDRESS).isEqualTo("8.8.8.8");
    }
}
