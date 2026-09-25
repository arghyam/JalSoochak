package org.arghyam.jalsoochak.message.config;

import java.util.Base64;
import java.util.Map;

import org.arghyam.jalsoochak.message.channel.provider.EmailSender;
import org.arghyam.jalsoochak.message.channel.provider.SmsSender;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * PER-TENANT-PROVIDERS: tests for the system default senders (O2-4).
 *
 * <p>This is the regression proof for the conversion. Each bean is built from the same properties
 * the singleton {@code @Component}s read, and must do exactly what they did — the same account, the
 * same template ids, the same DLT ids, the same hard-coded OTP text and the same fail-fast checks —
 * because these are what every send uses while the feature flag is off.
 */
class SystemDefaultProvidersTest {

    private static final String AUTH_KEY = "systemKey";
    private static final String AUTH_TOKEN = "systemToken";
    private static final String SMS_PATH = "/v0.1/Accounts/" + AUTH_KEY + "/SMSes/";

    private static final String MAIL_SEND_PATH = "/v3/mail/send";
    private static final String API_KEY = "SG.system-key";
    private static final String FROM = "noreply@jalsoochak.in";
    private static final String FROM_NAME = "Jalsoochak";
    private static final String LOGO = "https://logo.url/logo.png";

    private static final String SUCCESS_RESPONSE = """
            {"ApiId":"api-1","Success":true,"Message":"SMS Queued","MessageUUID":"uuid-1"}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SystemDefaultProviders providers = new SystemDefaultProviders();

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    // ── SMS ─────────────────────────────────────────────────────────────────────

    @Test
    void systemDefaultSmsSender_sendsTheSameRequestTheSingletonDid() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));

        SmsSender sender = providers.systemDefaultSmsSender(
                WebClient.builder(), smsProperties("ARGHYM"), false);

        assertThat(sender.sendOtp("919876543210", "123456", 5).block()).isTrue();

        String credentials = Base64.getEncoder()
                .encodeToString((AUTH_KEY + ":" + AUTH_TOKEN).getBytes());
        wireMockServer.verify(postRequestedFor(urlEqualTo(SMS_PATH))
                .withHeader("Authorization", equalTo("Basic " + credentials))
                .withRequestBody(containing("ARGHYM"))
                .withRequestBody(containing("system-pe"))
                .withRequestBody(containing("system-tmpl"))
                .withRequestBody(containing("system-hdr"))
                .withRequestBody(containing("Your OTP for Jalsoochak login is 123456. "
                        + "Do not share this OTP. Valid for 5 minutes.")));
    }

    @Test
    void systemDefaultSmsSender_respectsTheDryRunSwitch() {
        SmsSender sender = providers.systemDefaultSmsSender(
                WebClient.builder(), smsProperties("ARGHYM"), true);

        assertThat(sender.sendOtp("919876543210", "123456", 5).block()).isTrue();
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    void systemDefaultSmsSender_blankSenderId_failsFastAsItDidBefore() {
        // The former @Value("${smscountry.sender-id}") had no default, so an unset sender id
        // stopped the context. That check has to survive the move off @Value.
        assertThatThrownBy(() -> providers.systemDefaultSmsSender(
                WebClient.builder(), smsProperties("  "), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMSCOUNTRY_SENDER_ID");

        assertThatThrownBy(() -> providers.systemDefaultSmsSender(
                WebClient.builder(), smsProperties(null), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void smsProperties_blankBaseUrl_fallsBackToTheApiRootTheValueDefaultNamed() {
        SmsCountryProperties defaulted =
                new SmsCountryProperties(null, null, null, "ARGHYM", null, null, null);

        assertThat(defaulted.baseUrl()).isEqualTo(SmsCountryProperties.DEFAULT_BASE_URL);
    }

    @Test
    void smsProperties_clearedKeys_bindToEmptyStringsNotNulls() {
        // How an operator "clears" a key in a values file is a YAML null, which binds null however
        // the ${VAR:default} in application.yml reads. The six non-URL fields were
        // @Value("${smscountry.x:}") before, so an unset one bound "".
        SmsCountryProperties cleared =
                new SmsCountryProperties(null, null, null, "ARGHYM", null, null, null);

        assertThat(cleared.authKey()).isEmpty();
        assertThat(cleared.authToken()).isEmpty();
        assertThat(cleared.dltPrincipalEntityId()).isEmpty();
        assertThat(cleared.dltTemplateId()).isEmpty();
        assertThat(cleared.dltHeaderId()).isEmpty();
    }

    @Test
    void systemDefaultSmsSender_clearedDltKeys_stillSendsRatherThanThrowing() {
        // sendOtp assembles its body with Map.of, which rejects a null value. A cleared DLT key
        // would therefore NPE on the system-default sender — every login OTP on the platform, with
        // a stack trace that names nothing about configuration.
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));

        SmsSender sender = providers.systemDefaultSmsSender(WebClient.builder(),
                new SmsCountryProperties(wireMockServer.baseUrl() + "/v0.1",
                        AUTH_KEY, AUTH_TOKEN, "ARGHYM", null, null, null),
                false);

        assertThat(sender.sendOtp("919876543210", "123456", 5).block()).isTrue();
        wireMockServer.verify(postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    void smsProperties_toString_carriesNoCredentials() {
        assertThat(smsProperties("ARGHYM").toString())
                .contains("ARGHYM")
                .doesNotContain(AUTH_KEY)
                .doesNotContain(AUTH_TOKEN);
    }

    // ── Email: SendGrid ─────────────────────────────────────────────────────────

    @Test
    void systemDefaultSendGridSender_sendsTheSameRequestTheSingletonDid() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo(MAIL_SEND_PATH))
                .willReturn(aResponse().withStatus(202)));

        EmailSender sender = providers.systemDefaultSendGridSender(
                mailProperties(sendGrid(API_KEY, templates())), WebClient.builder());

        sender.send(new MailRequest("user@example.com", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset/tok", "expiry_minutes", 30)));

        var requests = wireMockServer.findAll(postRequestedFor(urlEqualTo(MAIL_SEND_PATH)));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);

        var body = objectMapper.readTree(requests.get(0).getBodyAsString());
        assertThat(body.get("template_id").asText()).isEqualTo("d-pw-reset");
        assertThat(body.get("from").get("email").asText()).isEqualTo(FROM);
        assertThat(body.get("from").get("name").asText()).isEqualTo(FROM_NAME);
        var data = body.get("personalizations").get(0).get("dynamic_template_data");
        assertThat(data.get("logo_image").asText()).isEqualTo(LOGO);
        assertThat(data.get("reset_link").asText()).isEqualTo("https://reset/tok");
    }

    @Test
    void systemDefaultSendGridSender_missingBlockKeyOrTemplates_failsFastAsItDidBefore() {
        // The three checks the SendGridMailSender constructor made at context startup. They moved
        // here with the adapter's conversion and must still stop the context, with the messages
        // that name the property and the environment variable to set.
        assertThatThrownBy(() -> providers.systemDefaultSendGridSender(
                mailProperties(null), WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.mail.sendgrid must be configured");

        assertThatThrownBy(() -> providers.systemDefaultSendGridSender(
                mailProperties(sendGrid("  ", templates())), WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENDGRID_API_KEY");

        assertThatThrownBy(() -> providers.systemDefaultSendGridSender(
                mailProperties(sendGrid(API_KEY, null)), WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.mail.sendgrid.templates must be configured");
    }

    @Test
    void systemDefaultEmailSenders_blankFromAddress_failFast() {
        // notification.mail.from-address carries no @NotBlank, and both adapters need it: SendGrid
        // puts it in a Map.of and would NPE per send, SMTP hands it to SimpleMailMessage.setFrom
        // and would be refused by the relay. Both tenant factories already require it, so the
        // system default was the last path that could build a sender with no sender.
        assertThatThrownBy(() -> providers.systemDefaultSendGridSender(
                new MailProperties("sendgrid", "  ", FROM_NAME, LOGO,
                        sendGrid(API_KEY, templates()), null),
                WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM_EMAIL");

        assertThatThrownBy(() -> providers.systemDefaultSmtpSender(
                new MailProperties("smtp", null, FROM_NAME, LOGO, null, null),
                mock(JavaMailSender.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM_EMAIL");
    }

    @Test
    void sendGridProperties_blankApiUrl_fallsBackToTheApiRootTheValueDefaultNamed() {
        MailProperties.SendGrid defaulted = new MailProperties.SendGrid(null, API_KEY, templates());

        assertThat(defaulted.apiUrl()).isEqualTo(MailProperties.SendGrid.DEFAULT_API_URL);
    }

    @Test
    void sendGridProperties_toString_carriesNoCredentials() {
        assertThat(sendGrid(API_KEY, templates()).toString()).doesNotContain(API_KEY);
    }

    // ── Email: SMTP ─────────────────────────────────────────────────────────────

    @Test
    void systemDefaultSmtpSender_sendsThroughTheAutoConfiguredJavaMailSender() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MailProperties.SmtpTemplate template = new MailProperties.SmtpTemplate(
                "Reset Your JalSoochak Password", "Reset: {reset_link} in {expiry_minutes} minutes.");
        MailProperties properties = new MailProperties("smtp", FROM, FROM_NAME, LOGO, null,
                new MailProperties.Smtp(new MailProperties.SmtpTemplates(
                        template, template, template, template, template)));

        EmailSender sender = providers.systemDefaultSmtpSender(properties, javaMailSender);
        sender.send(new MailRequest("user@example.com", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://reset/tok", "expiry_minutes", 30)));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("Reset Your JalSoochak Password");
        assertThat(message.getText()).isEqualTo("Reset: https://reset/tok in 30 minutes.");
    }

    @Test
    void systemDefaultSmtpSender_missingTemplates_stillFailsAtSendRatherThanAtStartup() {
        // Deliberately not a startup check: a deployment whose templates are missing failed at the
        // first send before SmtpMailSender took them as a parameter, and moving that to startup
        // would be a behaviour change this PR does not make.
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        EmailSender sender = providers.systemDefaultSmtpSender(
                new MailProperties("smtp", FROM, FROM_NAME, LOGO, null, null), javaMailSender);

        assertThatThrownBy(() -> sender.send(new MailRequest("user@example.com",
                MailTemplate.PASSWORD_RESET, Map.of("reset_link", "https://r", "expiry_minutes", 30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.mail.smtp.templates must be configured");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private SmsCountryProperties smsProperties(String senderId) {
        return new SmsCountryProperties(
                wireMockServer.baseUrl() + "/v0.1",
                AUTH_KEY, AUTH_TOKEN, senderId,
                "system-pe", "system-tmpl", "system-hdr");
    }

    private MailProperties mailProperties(MailProperties.SendGrid sendgrid) {
        return new MailProperties("sendgrid", FROM, FROM_NAME, LOGO, sendgrid, null);
    }

    private MailProperties.SendGrid sendGrid(String apiKey, MailProperties.Templates templates) {
        return new MailProperties.SendGrid(wireMockServer.baseUrl(), apiKey, templates);
    }

    private static MailProperties.Templates templates() {
        return new MailProperties.Templates(
                "d-pw-reset", "d-reinvite", "d-default", "d-superuser", "d-stateadmin");
    }
}
