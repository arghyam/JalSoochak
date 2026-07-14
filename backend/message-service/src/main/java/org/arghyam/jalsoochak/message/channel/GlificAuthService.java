package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Manages Glific session authentication.
 * Logs in on startup via POST /api/v1/session and can refresh tokens via PUT /api/v1/session/renew.
 */
@Service
@Slf4j
public class GlificAuthService {

    @Value("${glific.auth-url:https://api.arghyam.glific.com/api/v1/session}")
    private String authUrl;

    @Value("${glific.username:}")
    private String username;

    @Value("${glific.password:}")
    private String password;

    private final WebClient webClient;

    private volatile String accessToken;
    private volatile String renewalToken;

    public GlificAuthService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    @PostConstruct
    public void login() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("[GlificAuth] Credentials are not configured; WhatsApp/Glific flows will remain disabled");
            return;
        }
        log.info("[GlificAuth] Logging in to Glific...");
        try {
            JsonNode data = webClient.post()
                    .uri(authUrl)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("user", Map.of("phone", username, "password", password)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));

            if (data == null || !data.has("data")) {
                throw new RuntimeException("[GlificAuth] Login failed: null or unexpected response");
            }
            JsonNode tokenData = data.path("data");
            accessToken = requireNonBlankToken(tokenData, "access_token", "login");
            renewalToken = requireNonBlankToken(tokenData, "renewal_token", "login");
            log.info("[GlificAuth] Login successful, access token acquired");
        } catch (Exception e) {
            log.error("[GlificAuth] Login failed; verify Glific credentials. WhatsApp flows will remain disabled. Error: {}", e.getMessage());
            // Do not rethrow; let the context initialization finish.
        }
    }

    public String getAccessToken() {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("[GlificAuth] Access token unavailable; verify Glific credentials/login");
        }
        return accessToken;
    }

    /**
     * Refreshes the access token only if {@code staleToken} still matches the current token.
     * The equality check and the refresh are performed atomically under the same lock, preventing
     * redundant refreshes when multiple threads detect a 401 concurrently.
     */
    public synchronized void refreshIfStale(String staleToken) {
        if (staleToken != null && staleToken.equals(accessToken)) {
            refresh();
        }
    }

    /** Refreshes tokens using the renewal token (PUT /api/v1/session/renew).
     *  Falls back to a full re-login if the renewal token itself is rejected (401). */
    public synchronized void refresh() {
        log.info("[GlificAuth] Refreshing access token...");
        String renewUrl = authUrl + "/renew";
        JsonNode data;
        try {
            data = webClient.put()
                    .uri(renewUrl)
                    .header("Authorization", renewalToken)
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.Unauthorized e) {
            log.warn("[GlificAuth] Renewal token rejected (401); falling back to full re-login");
            login();
            return;
        }

        if (data == null || !data.has("data")) {
            throw new RuntimeException("[GlificAuth] Token refresh failed");
        }
        JsonNode tokenData = data.path("data");
        accessToken = requireNonBlankToken(tokenData, "access_token", "refresh");
        renewalToken = requireNonBlankToken(tokenData, "renewal_token", "refresh");
        log.info("[GlificAuth] Token refreshed successfully");
    }

    private String requireNonBlankToken(JsonNode tokenData, String key, String flow) {
        String token = tokenData.path(key).asText("");
        if (token.isBlank()) {
            throw new RuntimeException("[GlificAuth] " + flow + " failed: missing " + key);
        }
        return token;
    }
}
