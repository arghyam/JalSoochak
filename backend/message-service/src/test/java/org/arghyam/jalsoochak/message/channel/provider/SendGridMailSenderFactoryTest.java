package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Map;

import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PER-TENANT-PROVIDERS: tests for {@link SendGridMailSenderFactory}.
 *
 * <p>The point the WireMock cases make is the one the whole feature rests on: two tenants
 * configured with different SendGrid accounts send under their own API key, from address and
 * dynamic template ids — never each other's.
 *
 * <p>The rest assert O2-9's half of the contract, that a settings row which cannot produce a
 * working sender raises {@link ProviderNotUsableException} while it is being built, so
 * {@code TenantChannelProviders} can answer with the system default instead of mail that fails
 * per message.
 */
class SendGridMailSenderFactoryTest {

    private static final String MAIL_SEND_PATH = "/v3/mail/send";

    private static final String PLATFORM_NAME = "Jalsoochak";
    private static final String PLATFORM_LOGO = "https://platform/logo.png";

    private static final String TENANT_A_KEY = "SG.tenant-a";
    private static final String TENANT_B_KEY = "SG.tenant-b";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WireMockServer wireMockServer;
    private SendGridMailSenderFactory factory;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        factory = new SendGridMailSenderFactory(WebClient.builder(), platformProperties());
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(202)));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void providerId_isSendGrid() {
        assertThat(factory.providerId()).isEqualTo(EmailProviderType.SENDGRID);
    }

    @Test
    void secretNameConstants_matchTheOnesTheProviderTypeDeclares() {
        // The factory duplicates the name because the enum is kept byte-identical to
        // tenant-service's twin. If the two ever drift, every configured tenant falls back.
        assertThat(EmailProviderType.SENDGRID.getRequiredSecretNames())
                .containsExactly(SendGridMailSenderFactory.SECRET_API_KEY);
    }

    @Test
    void create_twoTenants_eachSendsUnderItsOwnAccount() throws Exception {
        EmailSender tenantA = factory.create(
                settings("noreply@mp.gov.in", "MP Jal", null, templates("a")),
                secrets(TENANT_A_KEY));
        EmailSender tenantB = factory.create(
                settings("noreply@tr.gov.in", "TR Jal", null, templates("b")),
                secrets(TENANT_B_KEY));

        tenantA.send(new MailRequest("op@mp.in", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://a", "expiry_minutes", 30)));
        tenantB.send(new MailRequest("op@tr.in", MailTemplate.REINVITATION,
                Map.of("name", "Sunita", "activation_link", "https://b", "expiry_hours", 24)));

        JsonNode first = bodyOf(0);
        assertThat(headerOf(0)).isEqualTo("Bearer " + TENANT_A_KEY);
        assertThat(first.get("template_id").asText()).isEqualTo("d-a-passwordReset");
        assertThat(first.get("from").get("email").asText()).isEqualTo("noreply@mp.gov.in");
        assertThat(first.get("from").get("name").asText()).isEqualTo("MP Jal");

        JsonNode second = bodyOf(1);
        assertThat(headerOf(1)).isEqualTo("Bearer " + TENANT_B_KEY);
        assertThat(second.get("template_id").asText()).isEqualTo("d-b-reinvitation");
        assertThat(second.get("from").get("email").asText()).isEqualTo("noreply@tr.gov.in");
        assertThat(second.get("from").get("name").asText()).isEqualTo("TR Jal");
    }

    @Test
    void create_blankFromNameAndLogo_fallBackToThePlatformValues() throws Exception {
        // Both are optional on write. A tenant that filled in only the required fields sends from
        // its own verified address under the product's name, with the logo its templates expect.
        EmailSender sender = factory.create(
                settings("noreply@mp.gov.in", "   ", null, templates("a")), secrets(TENANT_A_KEY));

        sender.send(new MailRequest("op@mp.in", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://a", "expiry_minutes", 30)));

        JsonNode body = bodyOf(0);
        assertThat(body.get("from").get("email").asText()).isEqualTo("noreply@mp.gov.in");
        assertThat(body.get("from").get("name").asText()).isEqualTo(PLATFORM_NAME);
        assertThat(body.get("personalizations").get(0).get("dynamic_template_data")
                .get("logo_image").asText()).isEqualTo(PLATFORM_LOGO);
    }

    @Test
    void create_tenantLogo_overridesThePlatformOne() throws Exception {
        EmailSender sender = factory.create(
                settings("noreply@mp.gov.in", "MP Jal", "https://mp/logo.png", templates("a")),
                secrets(TENANT_A_KEY));

        sender.send(new MailRequest("op@mp.in", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://a", "expiry_minutes", 30)));

        assertThat(bodyOf(0).get("personalizations").get(0).get("dynamic_template_data")
                .get("logo_image").asText()).isEqualTo("https://mp/logo.png");
    }

    @Test
    void create_apiRootComesFromSystemProperties_notFromTheTenant() {
        // O2-13: the settings carry no URL at all, so there is no tenant-chosen destination for an
        // API key. Every tenant's mail leaves through notification.mail.sendgrid.api-url.
        factory.create(settings("noreply@mp.gov.in", "MP Jal", null, templates("a")),
                        secrets(TENANT_A_KEY))
                .send(new MailRequest("op@mp.in", MailTemplate.PASSWORD_RESET,
                        Map.of("reset_link", "https://a", "expiry_minutes", 30)));

        wireMockServer.verify(1, postRequestedFor(urlEqualTo(MAIL_SEND_PATH)));
    }

    @Test
    void create_platformWithNoSendGridBlock_stillBuildsATenantSender() {
        // A deployment whose own default is SMTP may carry no notification.mail.sendgrid block at
        // all, and a tenant on SendGrid has to keep working there.
        SendGridMailSenderFactory noPlatformSendGrid = new SendGridMailSenderFactory(
                WebClient.builder(),
                new MailProperties("smtp", "noreply@example.com", PLATFORM_NAME, PLATFORM_LOGO,
                        null, null));

        assertThat(noPlatformSendGrid.create(
                settings("noreply@mp.gov.in", "MP Jal", null, templates("a")), secrets(TENANT_A_KEY)))
                .isNotNull();
    }

    @Test
    void create_settingsWithoutSendGridBlock_isRefused() {
        EmailProviderSettings noBlock = new EmailProviderSettings(
                EmailProviderType.SENDGRID, "noreply@mp.gov.in", "MP Jal", null, null, null);

        assertThatThrownBy(() -> factory.create(noBlock, secrets(TENANT_A_KEY)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("sendgrid");
    }

    @Test
    void create_incompleteTemplates_areRefused() {
        // All five or none: a tenant with a partial set would send four kinds of mail from its own
        // account and fall back for the fifth, which is harder to diagnose than one ERROR.
        EmailProviderSettings.Templates partial = new EmailProviderSettings.Templates(
                "d-a-passwordReset", "d-a-reinvitation", "d-a-defaultInvitation", "  ", null);

        assertThatThrownBy(() -> factory.create(
                settings("noreply@mp.gov.in", "MP Jal", null, partial), secrets(TENANT_A_KEY)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("sendgrid.templates");

        assertThatThrownBy(() -> factory.create(
                settings("noreply@mp.gov.in", "MP Jal", null, null), secrets(TENANT_A_KEY)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("sendgrid.templates");
    }

    @Test
    void create_blankFromAddress_isRefused() {
        assertThatThrownBy(() -> factory.create(
                settings("   ", "MP Jal", null, templates("a")), secrets(TENANT_A_KEY)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("fromAddress");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private MailProperties platformProperties() {
        return new MailProperties("sendgrid", "noreply@example.com", PLATFORM_NAME, PLATFORM_LOGO,
                new MailProperties.SendGrid(wireMockServer.baseUrl(), "SG.platform",
                        new MailProperties.Templates("d-p1", "d-p2", "d-p3", "d-p4", "d-p5")),
                null);
    }

    private static EmailProviderSettings settings(String fromAddress, String fromName,
            String logoImageUrl, EmailProviderSettings.Templates templates) {
        return new EmailProviderSettings(EmailProviderType.SENDGRID, fromAddress, fromName,
                logoImageUrl, new EmailProviderSettings.SendGrid(templates), null);
    }

    private static EmailProviderSettings.Templates templates(String tenant) {
        return new EmailProviderSettings.Templates(
                "d-" + tenant + "-passwordReset",
                "d-" + tenant + "-reinvitation",
                "d-" + tenant + "-defaultInvitation",
                "d-" + tenant + "-superUserInvitation",
                "d-" + tenant + "-stateAdminInvitation");
    }

    private static TenantSecrets secrets(String apiKey) {
        return TenantSecrets.of(MessagingChannel.EMAIL,
                Map.of(SendGridMailSenderFactory.SECRET_API_KEY, apiKey));
    }

    private JsonNode bodyOf(int index) throws Exception {
        return objectMapper.readTree(request(index).getBodyAsString());
    }

    private String headerOf(int index) {
        return request(index).getHeader("Authorization");
    }

    private com.github.tomakehurst.wiremock.verification.LoggedRequest request(int index) {
        var requests = wireMockServer.findAll(postRequestedFor(urlEqualTo(MAIL_SEND_PATH)));
        assertThat(requests).hasSizeGreaterThan(index);
        return requests.get(index);
    }
}
