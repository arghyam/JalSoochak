package org.arghyam.jalsoochak.message.channel.glific;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GlificGraphQLClient} verifying HTTP behaviour:
 * Authorization header, successful responses, GraphQL error handling,
 * and token-refresh retry on 401/unauthenticated errors.
 *
 * <p>Uses WireMock as a local HTTP server – no real Glific API is contacted.</p>
 */
class GlificGraphQLClientTest {

    private WireMockServer wireMockServer;
    private GlificAuthService authService;
    private GlificGraphQLClient client;

    private static final String GRAPHQL_PATH = "/api";
    private static final String AUTH_PATH = "/api/v1/session";

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        // Stub the login endpoint so @PostConstruct (called manually) succeeds
        wireMockServer.stubFor(post(urlEqualTo(AUTH_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"access_token":"initial_token","renewal_token":"renew_token"}}
                                """)));

        // Build real GlificAuthService pointing to WireMock, then call login manually
        authService = new GlificAuthService(WebClient.builder());
        ReflectionTestUtils.setField(authService, "authUrl",
                wireMockServer.baseUrl() + AUTH_PATH);
        ReflectionTestUtils.setField(authService, "username", "test_user");
        ReflectionTestUtils.setField(authService, "password", "test_pass");
        authService.login();

        // Build GlificGraphQLClient pointing at WireMock's GraphQL endpoint
        client = new GlificGraphQLClient(WebClient.builder(), authService);
        ReflectionTestUtils.setField(client, "apiUrl", wireMockServer.baseUrl() + GRAPHQL_PATH);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void execute_returnsDataNode_onSuccessfulResponse() {
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"optinContact":{"contact":{"id":123}}}}
                                """)));

        JsonNode result = client.execute("query {}", Map.of("phone", "919876543210"));

        assertThat(result).isNotNull();
        assertThat(result.path("optinContact").path("contact").path("id").asLong()).isEqualTo(123L);
    }

    @Test
    void execute_sendsAuthorizationHeader_withAccessToken() {
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"result":"ok"}}
                                """)));

        client.execute("query {}", Map.of());

        wireMockServer.verify(postRequestedFor(urlEqualTo(GRAPHQL_PATH))
                .withHeader("Authorization", equalTo("initial_token")));
    }

    @Test
    void execute_throwsRuntimeException_onGraphQLError() {
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"errors":[{"message":"something went wrong"}]}
                                """)));

        assertThatThrownBy(() -> client.execute("query {}", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Glific GraphQL error")
                .hasMessageContaining("something went wrong");
    }

    @Test
    void execute_refreshesToken_andRetries_onUnauthenticatedError() {
        // First call: unauthenticated GraphQL error
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .inScenario("auth-retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"errors":[{"message":"unauthenticated"}]}
                                """))
                .willSetStateTo("refreshed"));

        // Token refresh call
        wireMockServer.stubFor(put(urlEqualTo(AUTH_PATH + "/renew"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"access_token":"refreshed_token","renewal_token":"new_renew_token"}}
                                """)));

        // Second call (after refresh): success
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .inScenario("auth-retry")
                .whenScenarioStateIs("refreshed")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"result":"success after refresh"}}
                                """)));

        JsonNode result = client.execute("query {}", Map.of());

        assertThat(result.path("result").asText()).isEqualTo("success after refresh");
        // Auth refresh endpoint must have been called
        wireMockServer.verify(putRequestedFor(urlEqualTo(AUTH_PATH + "/renew")));
    }

    @Test
    void execute_throwsAfterRetry_whenSecondAttemptAlsoFails() {
        // Both calls return unauthenticated – no infinite retry
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"errors":[{"message":"unauthenticated"}]}
                                """)));
        wireMockServer.stubFor(put(urlEqualTo(AUTH_PATH + "/renew"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"access_token":"still_bad","renewal_token":"rr"}}
                                """)));

        assertThatThrownBy(() -> client.execute("query {}", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Glific GraphQL error");

        // Verify exactly 2 POST calls (initial + 1 retry)
        wireMockServer.verify(2, postRequestedFor(urlEqualTo(GRAPHQL_PATH)));
    }

    @Test
    void execute_throwsRuntimeException_whenApiUrlIsBlank() {
        ReflectionTestUtils.setField(client, "apiUrl", "");

        assertThatThrownBy(() -> client.execute("query {}", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Glific API URL is not configured");
    }

    @Test
    void execute_throwsRuntimeException_whenResponseIsNull() {
        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .willReturn(aResponse()
                        .withStatus(204))); // no body → null response

        // 204 No Content with no body may result in null deserialization
        // Depending on WebClient behaviour, this may throw or return null data
        // We verify that a RuntimeException is thrown in either case
        assertThatThrownBy(() -> client.execute("query {}", Map.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void execute_enforcesMinimumIntervalBetweenConsecutiveCalls() {
        ReflectionTestUtils.setField(client, "requestIntervalMs", 300L);

        wireMockServer.stubFor(post(urlEqualTo(GRAPHQL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"result":"ok"}}
                                """)));

        long start = System.currentTimeMillis();
        client.execute("query {}", Map.of());
        client.execute("query {}", Map.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(300L);
    }

    @Test
    void glificAuthService_login_storesAccessToken() {
        // Token was set during @BeforeEach login()
        assertThat(authService.getAccessToken()).isEqualTo("initial_token");
    }

    @Test
    void glificAuthService_refresh_updatesAccessToken() {
        wireMockServer.stubFor(put(urlEqualTo(AUTH_PATH + "/renew"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"access_token":"refreshed_token","renewal_token":"new_renew"}}
                                """)));

        authService.refresh();

        assertThat(authService.getAccessToken()).isEqualTo("refreshed_token");
    }
}
