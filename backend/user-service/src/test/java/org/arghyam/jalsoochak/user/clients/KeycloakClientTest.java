package org.arghyam.jalsoochak.user.clients;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.arghyam.jalsoochak.user.exceptions.KeycloakLogoutException;

import com.github.tomakehurst.wiremock.http.Fault;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class KeycloakClientTest {

    private KeycloakClient keycloakClient;
    private String authServerUrl;
    private static final String REALM = "test-realm";
    private static final String CLIENT_ID = "test-client";
    private static final String CLIENT_SECRET = "test-secret";

    @BeforeEach
    void setUp(WireMockRuntimeInfo runtimeInfo) {
        authServerUrl = runtimeInfo.getHttpBaseUrl();
        keycloakClient = new KeycloakClient(
                authServerUrl,
                REALM,
                CLIENT_ID,
                CLIENT_SECRET,
                5000,
                10000
        );
    }

    @Test
    void testConstructor_initializesCorrectly() {
        assertNotNull(keycloakClient);
    }

    @Test
    void testObtainToken_success() {
        // Given
        String expectedResponse = """
            {
                "access_token": "access123",
                "refresh_token": "refresh123",
                "expires_in": 3600,
                "refresh_expires_in": 7200,
                "token_type": "Bearer",
                "id_token": "id123",
                "session_state": "session123",
                "scope": "openid profile email"
            }
            """;

        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(expectedResponse)));

        // When
        KeycloakTokenResponse response = keycloakClient.obtainToken("testuser", "testpass");

        // Then
        assertNotNull(response);
        assertEquals("access123", response.accessToken());
        assertEquals("refresh123", response.refreshToken());
        assertEquals(3600, response.expiresIn());
        assertEquals(7200, response.refreshExpiresIn());
        assertEquals("Bearer", response.tokenType());
        assertEquals("id123", response.idToken());
        assertEquals("session123", response.sessionState());
        assertEquals("openid profile email", response.scope());

        // Verify request parameters
        verify(postRequestedFor(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .withRequestBody(containing("client_id=" + CLIENT_ID))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET))
                .withRequestBody(containing("username=testuser"))
                .withRequestBody(containing("password=testpass"))
                .withRequestBody(containing("grant_type=password"))
                .withRequestBody(containing("scope=openid")));
    }

    @Test
    void testObtainToken_unauthorizedError() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.UNAUTHORIZED.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.obtainToken("wronguser", "wrongpass"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid username, password, or refresh token", exception.getReason());
    }

    @Test
    void testObtainToken_badRequestError() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.BAD_REQUEST.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_request\",\"error_description\":\"Missing grant_type parameter\"}")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.obtainToken("testuser", "testpass"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Malformed token request or invalid grant parameters", exception.getReason());
    }

    @Test
    void testObtainToken_accountTemporarilyLocked_mapsToTooManyRequests() {
        // Given – Keycloak brute-force temporary lockout: invalid_grant / "Account temporarily
        // disabled" on the token endpoint, observed as HTTP 401 on the deployed Keycloak version.
        // Detection is body-based (requires JdkClientHttpRequestFactory to expose the 401 body),
        // so it maps to 429 rather than the generic wrong-credentials 401 message.
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.UNAUTHORIZED.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Account temporarily disabled\"}")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.obtainToken("lockeduser", "wrongpass"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        assertEquals("Account temporarily locked due to too many failed login attempts. "
                + "Please try again in a few minutes.", exception.getReason());
    }

    @Test
    void testObtainToken_gatewayError() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.BAD_GATEWAY.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"server_error\",\"error_description\":\"Keycloak unavailable\"}")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.obtainToken("testuser", "testpass"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals("Keycloak token request failed", exception.getReason());
    }

    @Test
    void testRefreshToken_success() {
        // Given
        String expectedResponse = """
            {
                "access_token": "new-access123",
                "refresh_token": "new-refresh123",
                "expires_in": 3600,
                "refresh_expires_in": 7200,
                "token_type": "Bearer",
                "id_token": "new-id123",
                "session_state": "new-session123",
                "scope": "openid profile email"
            }
            """;

        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(expectedResponse)));

        // When
        KeycloakTokenResponse response = keycloakClient.refreshToken("old-refresh-token");

        // Then
        assertNotNull(response);
        assertEquals("new-access123", response.accessToken());
        assertEquals("new-refresh123", response.refreshToken());
        assertEquals(3600, response.expiresIn());
        assertEquals(7200, response.refreshExpiresIn());
        assertEquals("Bearer", response.tokenType());
        assertEquals("new-id123", response.idToken());
        assertEquals("new-session123", response.sessionState());
        assertEquals("openid profile email", response.scope());

        // Verify request parameters
        verify(postRequestedFor(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .withRequestBody(containing("client_id=" + CLIENT_ID))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET))
                .withRequestBody(containing("refresh_token=old-refresh-token"))
                .withRequestBody(containing("grant_type=refresh_token")));
    }

    @Test
    void testRefreshToken_unauthorizedError() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.UNAUTHORIZED.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid refresh token\"}")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.refreshToken("invalid-refresh-token"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid username, password, or refresh token", exception.getReason());
    }

    @Test
    void testRefreshToken_badRequestError() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.BAD_REQUEST.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_request\",\"error_description\":\"Missing refresh_token parameter\"}")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.refreshToken(""));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Malformed token request or invalid grant parameters", exception.getReason());
    }

    @Test
    void testLogout_success() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/logout"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.NO_CONTENT.value())));

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> keycloakClient.logout("refresh-token-to-logout"));

        // Verify request parameters
        verify(postRequestedFor(urlEqualTo("/realms/test-realm/protocol/openid-connect/logout"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("client_id=" + CLIENT_ID))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET))
                .withRequestBody(containing("refresh_token=refresh-token-to-logout")));
    }

    @Test
    void testLogout_serverError() {
        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/logout"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"server_error\",\"error_description\":\"Logout failed\"}")));

        // When & Then
        KeycloakLogoutException exception = assertThrows(KeycloakLogoutException.class,
                () -> keycloakClient.logout("refresh-token"));

        assertEquals("Keycloak logout failed", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void testLogout_networkError() {
        // Given - Simulate true network failure with connection reset fault
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/logout"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // When & Then
        KeycloakLogoutException exception = assertThrows(KeycloakLogoutException.class,
                () -> keycloakClient.logout("refresh-token"));

        assertEquals("Keycloak logout failed", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void testPostForToken_genericException() {
        // Given - Create a scenario that causes a generic exception
        // This could happen due to network issues, malformed responses, etc.
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("invalid-json{")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.obtainToken("testuser", "testpass"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals("Keycloak token request failed", exception.getReason());
    }

    @Test
    void testPostForToken_responseStatusExceptionRethrown() {
        // This test verifies that ResponseStatusException is rethrown without wrapping
        // We can't directly test this scenario easily, but we can verify the exception handling
        // by triggering a 401 which should return ResponseStatusException directly

        // Given
        stubFor(post(urlEqualTo("/realms/test-realm/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.UNAUTHORIZED.value())));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> keycloakClient.obtainToken("testuser", "testpass"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertNotNull(exception.getReason());
        assertTrue(exception.getReason().contains("Invalid username, password, or refresh token"));
    }
}
