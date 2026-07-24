package org.arghyam.jalsoochak.user.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.arghyam.jalsoochak.user.exceptions.KeycloakLogoutException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;


@Slf4j
@Component
public class KeycloakClient {

    private final RestClient restClient;

    private final String tokenUrl;
    private final String logoutUrl;
    private final String clientId;
    private final String clientSecret;

    public KeycloakClient(@Value("${keycloak.auth-server-url}") String authServerUrl,
                          @Value("${keycloak.realm}") String realm,
                          @Value("${keycloak.resource}") String clientId,
                          @Value("${keycloak.credentials.secret}") String clientSecret,
                          @Value("${http-client.connect-timeout-ms}") int connectTimeoutMs,
                          @Value("${http-client.read-timeout-ms}") int readTimeoutMs) {
        this.tokenUrl = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        this.logoutUrl = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/logout";
        this.clientId = clientId;
        this.clientSecret = clientSecret;

        // Use JdkClientHttpRequestFactory (java.net.http.HttpClient) rather than
        // SimpleClientHttpRequestFactory: the latter's HttpURLConnection swallows the response
        // body on 4xx statuses (notably 401), which hid Keycloak's brute-force lockout message.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public KeycloakTokenResponse obtainToken(String username, String password) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("username", username);
        body.add("password", password);
        body.add("grant_type", "password");
        body.add("scope", "openid");

        return postForToken(tokenUrl, body);
    }

    public KeycloakTokenResponse refreshToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        return postForToken(tokenUrl, body);
    }

    public void logout(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        try {
            restClient.post()
                    .uri(logoutUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Keycloak logout failed", e);
            throw new KeycloakLogoutException("Keycloak logout failed", e);
        }
    }

    private KeycloakTokenResponse postForToken(String url, MultiValueMap<String, String> body) {
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(KeycloakTokenResponse.class);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("Keycloak token error: status={}, body={}", e.getStatusCode().value(), responseBody);
            // Realm-level brute-force temporary lockout comes back as an invalid_grant "Account
            // temporarily disabled" error (observed as HTTP 401 on this Keycloak version), so
            // detect it from the body before the status branches below map it to the generic
            // wrong-credentials message. Reading the body on 401 requires JdkClientHttpRequestFactory
            // (see constructor) — SimpleClientHttpRequestFactory's HttpURLConnection drops it.
            if (isAccountTemporarilyLocked(responseBody)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, 
                        "Account temporarily locked due to too many failed login attempts. "
                                + "Please try again in a few minutes.", e);
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username, password, or refresh token", e);
            }
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed token request or invalid grant parameters", e);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak token request failed", e);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Keycloak token error", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak token request failed", e);
        }
    }

    /**
     * Detects Keycloak's brute-force temporary-lockout response. When realm-level brute-force
     * detection locks an account, the token endpoint returns an {@code invalid_grant} error whose
     * description contains "temporarily disabled". Matched against the raw response body.
     */
    private boolean isAccountTemporarilyLocked(String responseBody) {
        return responseBody != null
                && responseBody.toLowerCase(Locale.ROOT).contains("temporarily disabled");
    }
}
