package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.arghyam.jalsoochak.message.exception.PermanentMailException;
import org.arghyam.jalsoochak.message.exception.TransientMailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SendGridMailSender} verifying HTTP behaviour:
 * correct template IDs, dynamic template data, Authorization header,
 * and error handling for non-2xx responses.
 *
 * <p>Uses WireMock as a local HTTP server — no real SendGrid API is contacted.</p>
 */
class SendGridMailSenderTest {

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance().build();

    private SendGridMailSender sender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MAIL_SEND_PATH        = "/v3/mail/send";
    private static final String API_KEY               = "SG.test-key";
    private static final String FROM                  = "noreply@jalsoochak.in";
    private static final String FROM_NAME             = "Jalsoochak";
    private static final String LOGO                  = "https://logo.url/logo.png";

    private static final String T_PASSWORD_RESET         = "d-9d83de3639254c18b86fa2834f4a5db1";
    private static final String T_REINVITATION           = "d-3710ff4d9d874cf1950c589c6f71d752";
    private static final String T_DEFAULT_INVITATION     = "d-7721978b67bf4f0083891657ed584447";
    private static final String T_SUPER_USER_INVITATION  = "d-6ff964820aae4e03866dd39f64976287";
    private static final String T_STATE_ADMIN_INVITATION = "d-ec98926fa9ed4046b3bf9b85c7a1a7e3";

    @BeforeEach
    void setUp() {
        MailProperties.Templates templates = new MailProperties.Templates(
                T_PASSWORD_RESET, T_REINVITATION, T_DEFAULT_INVITATION,
                T_SUPER_USER_INVITATION, T_STATE_ADMIN_INVITATION
        );
        MailProperties props = new MailProperties(
                "sendgrid", FROM, FROM_NAME, LOGO,
                new MailProperties.SendGrid(API_KEY, templates),
                null
        );
        sender = new SendGridMailSender(props, WebClient.builder());
        ReflectionTestUtils.setField(sender, "apiUrl", wireMockServer.baseUrl());
    }

    // ─────────────────────── Template ID resolution ────────────────────────────

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PASSWORD_RESET,         d-9d83de3639254c18b86fa2834f4a5db1",
            "REINVITATION,           d-3710ff4d9d874cf1950c589c6f71d752",
            "DEFAULT_INVITATION,     d-7721978b67bf4f0083891657ed584447",
            "SUPER_USER_INVITATION,  d-6ff964820aae4e03866dd39f64976287",
            "STATE_ADMIN_INVITATION, d-ec98926fa9ed4046b3bf9b85c7a1a7e3"
    })
    void send_usesCorrectTemplateId_forAllTemplates(String templateName, String expectedId) throws Exception {
        stubOk();
        MailTemplate template = MailTemplate.valueOf(templateName.strip());

        sender.send(new MailRequest("to@example.com", template, baseVarsFor(template)));

        JsonNode body = captureRequestBody();
        assertThat(body.get("template_id").asText()).isEqualTo(expectedId.strip());
    }

    // ─────────────────────── Dynamic template data ─────────────────────────────

    @Test
    void send_injectsLogoImageFromProperties() throws Exception {
        stubOk();
        sender.send(new MailRequest("to@example.com", MailTemplate.DEFAULT_INVITATION,
                Map.of("name", "Rohan", "activation_link", "https://act", "expiry_hours", 72)));

        JsonNode data = dynamicData();
        assertThat(data.get("logo_image").asText()).isEqualTo(LOGO);
    }

    @Test
    void send_doesNotRequireLogoInMailRequest() throws Exception {
        stubOk();
        sender.send(new MailRequest("to@example.com", MailTemplate.DEFAULT_INVITATION,
                Map.of("name", "Rohan", "activation_link", "https://act", "expiry_hours", 72)));

        assertThat(dynamicData().has("logo_image")).isTrue();
    }

    @Test
    void send_stateAdminInvitation_includesStateName() throws Exception {
        stubOk();
        sender.send(new MailRequest("sa@mp.gov", MailTemplate.STATE_ADMIN_INVITATION,
                Map.of("name", "Nitish", "state_name", "Madhya Pradesh",
                        "activation_link", "https://act/sa", "expiry_hours", 24)));

        assertThat(dynamicData().get("state_name").asText()).isEqualTo("Madhya Pradesh");
    }

    @Test
    void send_passwordReset_includesResetLinkAndExpiry() throws Exception {
        stubOk();
        sender.send(new MailRequest("user@example.com", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset/tok", "expiry_minutes", 30)));

        JsonNode data = dynamicData();
        assertThat(data.get("reset_link").asText()).isEqualTo("https://reset/tok");
        assertThat(data.get("expiry_minutes").asInt()).isEqualTo(30);
    }

    // ─────────────────────── HTTP request structure ────────────────────────────

    @Test
    void send_sendsAuthorizationHeader_withBearerToken() {
        stubOk();
        sender.send(new MailRequest("to@example.com", MailTemplate.DEFAULT_INVITATION,
                Map.of("name", "Test", "activation_link", "https://a", "expiry_hours", 24)));

        wireMockServer.verify(postRequestedFor(urlEqualTo(MAIL_SEND_PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY)));
    }

    // ─────────────────────── Failure handling ──────────────────────────────────

    @Test
    void send_throwsPermanent_whenSendGridReturns4xx() {
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"message\":\"invalid\"}]}")));

        assertThatThrownBy(() -> sender.send(passwordReset()))
                .isInstanceOf(PermanentMailException.class)
                .hasMessageContaining("SendGrid returned HTTP 400");
    }

    @Test
    void send_throwsPermanent_whenApiKeyIsRejected() {
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"errors\":[{\"message\":\"unauthorized\"}]}")));

        assertThatThrownBy(() -> sender.send(passwordReset()))
                .isInstanceOf(PermanentMailException.class);
    }

    @Test
    void send_throwsTransient_whenSendGridReturns5xx() {
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        assertThatThrownBy(() -> sender.send(passwordReset()))
                .isInstanceOf(TransientMailException.class)
                .hasMessageContaining("SendGrid returned HTTP 500");
    }

    @Test
    void send_throwsTransient_whenRateLimited() {
        // 429 is the one 4xx that means "come back later" rather than "this will never work".
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(429).withBody("{\"errors\":[{\"message\":\"rate limit\"}]}")));

        assertThatThrownBy(() -> sender.send(passwordReset()))
                .isInstanceOf(TransientMailException.class)
                .hasMessageContaining("SendGrid returned HTTP 429");
    }

    @Test
    void send_throwsTransient_whenSendGridIsUnreachable() {
        // Point the sender at a port nothing is listening on: the request never completes,
        // so nothing could have been delivered and a replay is safe.
        ReflectionTestUtils.setField(sender, "apiUrl", "http://localhost:1");

        assertThatThrownBy(() -> sender.send(passwordReset()))
                .isInstanceOf(TransientMailException.class);
    }

    @Test
    void send_throwsPermanent_whenSendGridStallsPastTheTimeout() {
        // A hung provider must not park the Kafka listener thread indefinitely. The outcome is
        // ambiguous — SendGrid may have accepted the payload — so it is deliberately permanent.
        ReflectionTestUtils.setField(sender, "sendTimeout", Duration.ofMillis(150));
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(202).withFixedDelay(3_000)));

        assertThatThrownBy(() -> sender.send(passwordReset()))
                .isInstanceOf(PermanentMailException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void send_appliesADefaultTimeout_soAHungProviderCannotStallTheListenerForever() {
        assertThat((Duration) ReflectionTestUtils.getField(sender, "sendTimeout"))
                .isNotNull()
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    // ─────────────────────── Helpers ───────────────────────────────────────────

    private void stubOk() {
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(202)));
    }

    private static MailRequest passwordReset() {
        return new MailRequest("to@example.com", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://link", "expiry_minutes", 30));
    }

    private JsonNode captureRequestBody() throws Exception {
        var requests = wireMockServer.findAll(postRequestedFor(urlEqualTo(MAIL_SEND_PATH)));
        assertThat(requests).isNotEmpty();
        return objectMapper.readTree(requests.get(requests.size() - 1).getBodyAsString());
    }

    private JsonNode dynamicData() throws Exception {
        return captureRequestBody().get("personalizations").get(0).get("dynamic_template_data");
    }

    private static Map<String, Object> baseVarsFor(MailTemplate template) {
        return switch (template) {
            case PASSWORD_RESET -> Map.of("reset_link", "https://r", "expiry_minutes", 30);
            case STATE_ADMIN_INVITATION -> Map.of(
                    "name", "Admin", "state_name", "Bihar",
                    "activation_link", "https://a", "expiry_hours", 24);
            default -> Map.of("name", "User", "activation_link", "https://a", "expiry_hours", 24);
        };
    }
}
