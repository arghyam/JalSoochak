package org.arghyam.jalsoochak.message.config;

import java.util.Base64;

import org.arghyam.jalsoochak.message.channel.SmsSender;
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
 * PER-TENANT-PROVIDERS: tests for the system default SMS sender (O2-4).
 *
 * <p>This is the regression proof for the conversion. The bean is built from the same
 * {@code smscountry.*} properties the singleton {@code @Component} read, and must put exactly the
 * same request on the wire — the same account, the same DLT ids and the one hard-coded OTP text —
 * because it is what every send uses while the feature flag is off.
 */
class SmsConfigTest {

    private static final String AUTH_KEY = "systemKey";
    private static final String AUTH_TOKEN = "systemToken";
    private static final String SMS_PATH = "/v0.1/Accounts/" + AUTH_KEY + "/SMSes/";

    private static final String SUCCESS_RESPONSE = """
            {"ApiId":"api-1","Success":true,"Message":"SMS Queued","MessageUUID":"uuid-1"}""";

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

    @Test
    void systemDefaultSmsSender_sendsTheSameRequestTheSingletonDid() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));

        SmsSender sender = new SmsConfig()
                .systemDefaultSmsSender(WebClient.builder(), properties("ARGHYM"), false);

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
        SmsSender sender = new SmsConfig()
                .systemDefaultSmsSender(WebClient.builder(), properties("ARGHYM"), true);

        assertThat(sender.sendOtp("919876543210", "123456", 5).block()).isTrue();
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    void systemDefaultSmsSender_blankSenderId_failsFastAsItDidBefore() {
        // The former @Value("${smscountry.sender-id}") had no default, so an unset sender id
        // stopped the context. That check has to survive the move off @Value.
        assertThatThrownBy(() -> new SmsConfig()
                .systemDefaultSmsSender(WebClient.builder(), properties("  "), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMSCOUNTRY_SENDER_ID");

        assertThatThrownBy(() -> new SmsConfig()
                .systemDefaultSmsSender(WebClient.builder(), properties(null), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void properties_blankBaseUrl_fallsBackToTheApiRootTheValueDefaultNamed() {
        SmsCountryProperties defaulted =
                new SmsCountryProperties(null, null, null, "ARGHYM", null, null, null);

        assertThat(defaulted.baseUrl()).isEqualTo(SmsCountryProperties.DEFAULT_BASE_URL);
    }

    @Test
    void properties_toString_carriesNoCredentials() {
        assertThat(properties("ARGHYM").toString())
                .contains("ARGHYM")
                .doesNotContain(AUTH_KEY)
                .doesNotContain(AUTH_TOKEN);
    }

    private SmsCountryProperties properties(String senderId) {
        return new SmsCountryProperties(
                wireMockServer.baseUrl() + "/v0.1",
                AUTH_KEY, AUTH_TOKEN, senderId,
                "system-pe", "system-tmpl", "system-hdr");
    }
}
