package org.arghyam.jalsoochak.message.channel.provider.glific;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages Glific session authentication.
 * Logs in on startup via POST /api/v1/session and can refresh tokens via PUT /api/v1/session/renew.
 */
@Service
@Slf4j
public class GlificAuthService {

    @Value("${whatsapp.auth-url:https://api.arghyam.glific.com/api/v1/session}")
    private String authUrl;

    @Value("${whatsapp.username:}")
    private String username;

    @Value("${whatsapp.password:}")
    private String password;

    private final WebClient webClient;
    private final GlificWhatsAppSettings settings;

    private volatile String accessToken;
    private volatile String renewalToken;

    public GlificAuthService(WebClient.Builder builder, GlificWhatsAppSettings settings) {
        this.webClient = builder.build();
        this.settings = settings;
    }

    /**
     * Logs in, or refuses to start when the connection settings are blank and any purpose is live.
     *
     * <p>Only a deployment that suppresses every purpose may run without them: it makes no Glific call.
     * Anywhere else a blank value would let the service start and then fail every send.</p>
     */
    @PostConstruct
    public void login() {
        List<String> missing = missingConnectionSettings();
        if (!missing.isEmpty()) {
            if (!settings.dryRun().allPurposes()) {
                throw new IllegalStateException("WhatsApp delivery is enabled but " + String.join(", ", missing)
                        + " resolved empty. Set them, or suppress every purpose (NOTIFICATIONS_*_DRY_RUN=true)"
                        + " to run without a WhatsApp provider account.");
            }
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

    private List<String> missingConnectionSettings() {
        List<String> missing = new ArrayList<>();
        if (authUrl == null || authUrl.isBlank()) {
            missing.add("whatsapp.auth-url (WHATSAPP_AUTH_URL)");
        }
        if (username == null || username.isBlank()) {
            missing.add("whatsapp.username (WHATSAPP_USERNAME)");
        }
        if (password == null || password.isBlank()) {
            missing.add("whatsapp.password (WHATSAPP_PASSWORD)");
        }
        return missing;
    }

    private String requireNonBlankToken(JsonNode tokenData, String key, String flow) {
        String token = tokenData.path(key).asText("");
        if (token.isBlank()) {
            throw new RuntimeException("[GlificAuth] " + flow + " failed: missing " + key);
        }
        return token;
    }
}
