package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.arghyam.jalsoochak.message.config.SmsCountryProperties;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PER-TENANT-PROVIDERS: tests for {@link SmsCountrySenderFactory}.
 *
 * <p>The point the WireMock cases make is the one the whole feature rests on: two tenants
 * configured with different SMSCountry accounts send under their own credentials, sender id, DLT
 * registrations and message text — never each other's.
 *
 * <p>The rest assert O2-9's half of the contract, that a settings row which cannot produce a
 * working sender raises {@link ProviderNotUsableException} while it is being built, so
 * {@code TenantChannelProviders} can answer with the system default instead of a send that fails
 * per message.
 */
class SmsCountrySenderFactoryTest {

    private static final String TENANT_A_KEY = "tenantAKey";
    private static final String TENANT_A_TOKEN = "tenantAToken";
    private static final String TENANT_B_KEY = "tenantBKey";
    private static final String TENANT_B_TOKEN = "tenantBToken";

    private static final String SUCCESS_RESPONSE = """
            {"ApiId":"api-1","Success":true,"Message":"SMS Queued","MessageUUID":"uuid-1"}""";

    private WireMockServer wireMockServer;
    private SmsCountrySenderFactory factory;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        factory = factoryWith(false);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void providerId_isSmsCountry() {
        assertThat(factory.providerId()).isEqualTo(SmsProviderType.SMSCOUNTRY);
    }

    @Test
    void secretNameConstants_matchTheOnesTheProviderTypeDeclares() {
        // The factory duplicates the names because the enum is a copy of tenant-service's, which is
        // what the secret store writes against. If the two drift, every configured tenant falls back.
        assertThat(SmsProviderType.SMSCOUNTRY.getRequiredSecretNames())
                .containsExactlyInAnyOrder(
                        SmsCountrySenderFactory.SECRET_AUTH_KEY,
                        SmsCountrySenderFactory.SECRET_AUTH_TOKEN);
    }

    @Test
    void create_twoTenants_eachSendsUnderItsOwnAccount() {
        stubSmsPath(TENANT_A_KEY);
        stubSmsPath(TENANT_B_KEY);

        SmsSender tenantA = factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", "A says {otp}"),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN));
        SmsSender tenantB = factory.create(
                settings("SENDER-B", "pe-b", "tmpl-b", "hdr-b", "B says {otp} for {expiryMinutes}m"),
                secrets(TENANT_B_KEY, TENANT_B_TOKEN));

        assertThat(tenantA.sendOtp("919876543210", "111111", 5).block()).isTrue();
        assertThat(tenantB.sendOtp("919876543211", "222222", 9).block()).isTrue();

        wireMockServer.verify(postRequestedFor(urlEqualTo(smsPath(TENANT_A_KEY)))
                .withHeader("Authorization", equalTo(basic(TENANT_A_KEY, TENANT_A_TOKEN)))
                .withRequestBody(containing("SENDER-A"))
                .withRequestBody(containing("pe-a"))
                .withRequestBody(containing("tmpl-a"))
                .withRequestBody(containing("hdr-a"))
                .withRequestBody(containing("A says 111111")));

        wireMockServer.verify(postRequestedFor(urlEqualTo(smsPath(TENANT_B_KEY)))
                .withHeader("Authorization", equalTo(basic(TENANT_B_KEY, TENANT_B_TOKEN)))
                .withRequestBody(containing("SENDER-B"))
                .withRequestBody(containing("pe-b"))
                .withRequestBody(containing("tmpl-b"))
                .withRequestBody(containing("hdr-b"))
                .withRequestBody(containing("B says 222222 for 9m")));
    }

    @Test
    void create_noOtpTemplateStored_sendsTodaysDefaultText() {
        stubSmsPath(TENANT_A_KEY);

        SmsSender sender = factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN));
        sender.sendOtp("919876543210", "654321", 10).block();

        wireMockServer.verify(postRequestedFor(urlEqualTo(smsPath(TENANT_A_KEY)))
                .withRequestBody(containing("Your OTP for Jalsoochak login is 654321. "
                        + "Do not share this OTP. Valid for 10 minutes.")));
    }

    @Test
    void create_dryRunOn_appliesToTenantSendersToo() {
        // The dry-run switch is global to the channel (O2-12); a tenant's own account must not
        // slip past a kill switch that is on.
        stubSmsPath(TENANT_A_KEY);

        SmsSender sender = factoryWith(true).create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN));

        assertThat(sender.sendOtp("919876543210", "123456", 5).block()).isTrue();
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(smsPath(TENANT_A_KEY))));
    }

    @Test
    void create_settingsWithoutSmsCountryBlock_isRefused() {
        SmsProviderSettings noBlock = new SmsProviderSettings(SmsProviderType.SMSCOUNTRY.getWireName(), null);

        assertThatThrownBy(() -> factory.create(noBlock, secrets(TENANT_A_KEY, TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smscountry");
    }

    @Test
    void create_blankSenderId_isRefused() {
        assertThatThrownBy(() -> factory.create(
                settings("  ", "pe-a", "tmpl-a", "hdr-a", null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smscountry.senderId");
    }

    @Test
    void create_missingDltRegistrations_areRefused() {
        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", null, "tmpl-a", "hdr-a", null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smscountry.dltPrincipalEntityId");

        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", null, "hdr-a", null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smscountry.dltTemplateId");

        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", null, null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smscountry.dltHeaderId");
    }

    @Test
    void create_missingOrBlankSecrets_areRefused() {
        // TenantSecretResolver checks that a secret row exists, not that it decrypts to anything,
        // so an empty value reaches the factory. A blank key would build /Accounts//SMSes/ — a
        // wrong request rather than a refused one — and a blank token a permanent 401.
        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                TenantSecrets.of(MessagingChannel.SMS,
                        Map.of(SmsCountrySenderFactory.SECRET_AUTH_TOKEN, TENANT_A_TOKEN))))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining(SmsCountrySenderFactory.SECRET_AUTH_KEY);

        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                secrets("   ", TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining(SmsCountrySenderFactory.SECRET_AUTH_KEY);

        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                TenantSecrets.of(MessagingChannel.SMS,
                        Map.of(SmsCountrySenderFactory.SECRET_AUTH_KEY, TENANT_A_KEY))))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining(SmsCountrySenderFactory.SECRET_AUTH_TOKEN);

        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                secrets(TENANT_A_KEY, "   ")))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining(SmsCountrySenderFactory.SECRET_AUTH_TOKEN);
    }

    @Test
    void create_authKeyCarryingUriSyntax_isRefused() {
        // The key is also the /Accounts/{authKey}/ path segment of every request this account
        // makes. SmsCountrySender percent-encodes it, so this is the second defence rather than
        // the only one — but refusing here is what turns a hostile or malformed key into one ERROR
        // and the system default instead of a request to a path SMSCountry will not recognise.
        // The dot is refused with the rest: it survives encoding, so '..' would still traverse.
        for (String hostileKey : List.of("a/../../admin", "a?x=1", "a{0}", "a b", "a.b")) {
            assertThatThrownBy(() -> factory.create(
                    settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                    secrets(hostileKey, TENANT_A_TOKEN)))
                    .isInstanceOf(ProviderNotUsableException.class)
                    .hasMessageContaining(SmsCountrySenderFactory.SECRET_AUTH_KEY);
        }
    }

    @Test
    void create_refusedAuthKey_isNotQuotedInTheFailure() {
        // S-4: the key is half of this account's basic-auth pair, and TenantChannelProviders logs a
        // ProviderNotUsableException message in full.
        String hostileKey = "a/../../admin";

        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                secrets(hostileKey, TENANT_A_TOKEN)))
                .hasMessageNotContaining(hostileKey);
    }

    @Test
    void create_unrenderableOtpTemplate_isRefused() {
        assertThatThrownBy(() -> factory.create(
                settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", "No placeholder here"),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("{otp}");
    }

    @Test
    void create_baseUrlComesFromSystemProperties_notFromTheTenant() {
        // O2-13: SMSCountry serves every customer from one host, so there is no per-tenant URL to
        // store — and therefore no tenant-chosen destination for an auth token.
        stubSmsPath(TENANT_A_KEY);

        factory.create(settings("SENDER-A", "pe-a", "tmpl-a", "hdr-a", null),
                secrets(TENANT_A_KEY, TENANT_A_TOKEN))
                .sendOtp("919876543210", "123456", 5).block();

        wireMockServer.verify(postRequestedFor(urlEqualTo(smsPath(TENANT_A_KEY))));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private SmsCountrySenderFactory factoryWith(boolean dryRun) {
        SmsCountryProperties properties = new SmsCountryProperties(
                wireMockServer.baseUrl() + "/v0.1", null, null, null, null, null, null);
        return new SmsCountrySenderFactory(WebClient.builder(), properties, dryRun);
    }

    private static SmsProviderSettings settings(String senderId, String principalEntityId,
            String templateId, String headerId, String otpTemplate) {
        return new SmsProviderSettings(SmsProviderType.SMSCOUNTRY.getWireName(),
                new SmsProviderSettings.SmsCountry(
                        senderId, principalEntityId, templateId, headerId, otpTemplate));
    }

    private static TenantSecrets secrets(String authKey, String authToken) {
        return TenantSecrets.of(MessagingChannel.SMS,
                Map.of(SmsCountrySenderFactory.SECRET_AUTH_KEY, authKey,
                        SmsCountrySenderFactory.SECRET_AUTH_TOKEN, authToken));
    }

    private static String smsPath(String authKey) {
        return "/v0.1/Accounts/" + authKey + "/SMSes/";
    }

    private static String basic(String authKey, String authToken) {
        return "Basic " + Base64.getEncoder().encodeToString((authKey + ":" + authToken).getBytes());
    }

    private void stubSmsPath(String authKey) {
        wireMockServer.stubFor(post(urlEqualTo(smsPath(authKey)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));
    }
}
