package org.arghyam.jalsoochak.user.clients;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

@WireMockTest
class CaptchaClientTest {

    private static final String SITEVERIFY_PATH = "/recaptcha/api/siteverify";
    private static final String SECRET = "test-secret";
    private static final String TOKEN = "user-captcha-token";

    private CaptchaClient captchaClient;
    private String verifyUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo runtimeInfo) {
        verifyUrl = runtimeInfo.getHttpBaseUrl() + SITEVERIFY_PATH;
        captchaClient = new CaptchaClient(5000, 10000);
    }

    @Test
    void verify_success_parsesResponseAndSendsFormParams() {
        stubFor(post(urlEqualTo(SITEVERIFY_PATH))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {"success":true,"challenge_ts":"2026-07-16T00:00:00Z","hostname":"localhost"}
                                """)));

        RecaptchaVerifyResponse response = captchaClient.verify(verifyUrl, SECRET, TOKEN);

        assertNotNull(response);
        assertTrue(response.success());
        assertEquals("localhost", response.hostname());

        verify(postRequestedFor(urlEqualTo(SITEVERIFY_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("secret=" + SECRET))
                .withRequestBody(containing("response=" + TOKEN)));
    }

    @Test
    void verify_failure_parsesErrorCodes() {
        stubFor(post(urlEqualTo(SITEVERIFY_PATH))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {"success":false,"error-codes":["invalid-input-response","timeout-or-duplicate"]}
                                """)));

        RecaptchaVerifyResponse response = captchaClient.verify(verifyUrl, SECRET, TOKEN);

        assertNotNull(response);
        assertFalse(response.success());
        assertEquals(2, response.errorCodes().size());
        assertTrue(response.errorCodes().contains("invalid-input-response"));
    }

    @Test
    void verify_providerHttpError_mapsToBadGateway() {
        stubFor(post(urlEqualTo(SITEVERIFY_PATH))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"server_error\"}")));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> captchaClient.verify(verifyUrl, SECRET, TOKEN));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals("CAPTCHA verification request failed", exception.getReason());
    }

    @Test
    void verify_transportFailure_mapsToBadGateway() {
        stubFor(post(urlEqualTo(SITEVERIFY_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> captchaClient.verify(verifyUrl, SECRET, TOKEN));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals("CAPTCHA verification request failed", exception.getReason());
    }
}
