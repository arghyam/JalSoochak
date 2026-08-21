package org.arghyam.jalsoochak.message.channel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SmsCountryService} verifying HTTP behaviour:
 * correct URL construction, Basic Auth header, request body, and error handling.
 *
 * <p>Uses WireMock as a local HTTP server — no real SMSCountry API is contacted.</p>
 */
class SmsCountryServiceTest {

    private static final String AUTH_KEY = "testAuthKey";
    private static final String AUTH_TOKEN = "testAuthToken";
    private static final String SENDER_ID = "TEST-SENDER";
    private static final String DLT_PE_ID = "test-pe-id";
    private static final String DLT_TEMPLATE_ID = "test-template-id";
    private static final String DLT_HEADER_ID = "test-header-id";
    private static final String SMS_PATH = "/v0.1/Accounts/" + AUTH_KEY + "/SMSes/";

    private static final String SUCCESS_RESPONSE = """
            {"ApiId":"4236749c-0d5c-4b1e-9598-3260e688d616","Success":true,\
            "Message":"SMS Queued","MessageUUID":"4236749c-0d5c-4b1e-9598-3260e688d616"}""";

    private WireMockServer wireMockServer;
    private SmsCountryService service;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        service = new SmsCountryService(WebClient.builder());
        ReflectionTestUtils.setField(service, "baseUrl", wireMockServer.baseUrl() + "/v0.1");
        ReflectionTestUtils.setField(service, "authKey", AUTH_KEY);
        ReflectionTestUtils.setField(service, "authToken", AUTH_TOKEN);
        ReflectionTestUtils.setField(service, "senderId", SENDER_ID);
        ReflectionTestUtils.setField(service, "dltPrincipalEntityId", DLT_PE_ID);
        ReflectionTestUtils.setField(service, "dltTemplateId", DLT_TEMPLATE_ID);
        ReflectionTestUtils.setField(service, "dltHeaderId", DLT_HEADER_ID);
        ReflectionTestUtils.setField(service, "dryRun", false);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void sendOtp_success_returnsTrue() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));

        boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isTrue();

        String expectedCredentials = Base64.getEncoder()
                .encodeToString((AUTH_KEY + ":" + AUTH_TOKEN).getBytes());
        wireMockServer.verify(postRequestedFor(urlEqualTo(SMS_PATH))
                .withHeader("Authorization", equalTo("Basic " + expectedCredentials))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(containing("123456"))
                .withRequestBody(containing("919876543210"))
                .withRequestBody(containing("5 minutes"))
                .withRequestBody(containing(SENDER_ID))
                .withRequestBody(containing(DLT_PE_ID))
                .withRequestBody(containing(DLT_TEMPLATE_ID))
                .withRequestBody(containing(DLT_HEADER_ID)));
    }

    @Test
    void sendOtp_successAsStringTrue_returnsTrue() {
        // SMSCountry may return "Success": "True" as a string rather than a JSON boolean
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ApiId\":\"abc\",\"Success\":\"True\",\"Message\":\"Messages Queued\",\"MessageUUID\":\"abc\"}")));

        boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isTrue();
    }

    @Test
    void sendOtp_successFalseInBody_returnsFalse() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ApiId\":\"abc\",\"Success\":false,\"Message\":\"Invalid sender ID\"}")));

        boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isFalse();
    }

    @Test
    void sendOtp_http4xx_returnsFalse() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Message\":\"Unauthorized\"}")));

        boolean result = service.sendOtp("919876543210", "999999", 5).block();

        assertThat(result).isFalse();
    }

    @Test
    void sendOtp_http5xx_throwsForKafkaRetry() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.sendOtp("919876543210", "999999", 5).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SMSCountry OTP send failed");
    }

    @Test
    void sendOtp_dryRun_noHttpCallMade() {
        ReflectionTestUtils.setField(service, "dryRun", true);

        boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isTrue();
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    void sendOtp_missingSuccessField_returnsFalse() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ApiId\":\"abc\",\"Message\":\"Some message\"}")));

        boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isFalse();
    }

    @Test
    void sendOtp_messageBodyContainsDltApprovedTemplate() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));

        service.sendOtp("919876543210", "654321", 10).block();

        wireMockServer.verify(postRequestedFor(urlEqualTo(SMS_PATH))
                .withRequestBody(containing(
                        "Your OTP for Jalsoochak login is 654321. " +
                        "Do not share this OTP. Valid for 10 minutes.")));
    }

    @Test
    void sendOtp_emptyResponseBody_returnsFalse() {
        // SMSCountry returns HTTP 200 with no body — switchIfEmpty should handle this
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")));

        boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isFalse();
    }

    // ───────────────────── connection-failure retry ─────────────────────

    /**
     * A connection that never reached SMSCountry sent nothing, so one immediate retry cannot
     * duplicate an SMS — and at 500ms it lands far inside the OTP's 10-minute life, with no chance
     * of racing the user's 60s resend (which revokes the code this event carries).
     */
    @Test
    void sendOtp_retriesOnceWhenTheConnectionFails_thenSucceeds() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .inScenario("flaky-connection").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .inScenario("flaky-connection").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_RESPONSE)));

        Boolean result = service.sendOtp("919876543210", "123456", 5).block();

        assertThat(result).isTrue();
        wireMockServer.verify(2, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    void sendOtp_givesUpAfterOneRetry_whenTheConnectionKeepsFailing() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // Still an error signal, per the port's contract for a transient failure.
        assertThatThrownBy(() -> service.sendOtp("919876543210", "123456", 5).block())
                .isInstanceOf(RuntimeException.class);

        // Exactly one retry — this must never grow into a back-off ladder.
        wireMockServer.verify(2, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    /**
     * A 5xx may mean SMSCountry already queued the message. Retrying would risk a second SMS at
     * our cost, so the retry filter deliberately excludes it — the caller still sees the error.
     */
    @Test
    void sendOtp_doesNotRetryServerErrors() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse().withStatus(503).withBody("upstream unavailable")));

        assertThatThrownBy(() -> service.sendOtp("919876543210", "123456", 5).block())
                .isInstanceOf(RuntimeException.class);

        wireMockServer.verify(1, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    void sendOtp_doesNotRetryClientErrors() {
        wireMockServer.stubFor(post(urlEqualTo(SMS_PATH))
                .willReturn(aResponse().withStatus(401).withBody("bad credentials")));

        assertThat(service.sendOtp("919876543210", "123456", 5).block()).isFalse();

        wireMockServer.verify(1, postRequestedFor(urlEqualTo(SMS_PATH)));
    }
}
