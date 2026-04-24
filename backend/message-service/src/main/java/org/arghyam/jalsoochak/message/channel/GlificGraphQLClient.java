package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin WebClient wrapper for the Glific GraphQL API.
 * POSTs {@code {"query": ..., "variables": ...}} with {@code Authorization: <token>} header
 * obtained from {@link GlificAuthService} (no Bearer prefix). On an unauthenticated error,
 * refreshes the token and retries once.
 */
@Component
@Slf4j
public class GlificGraphQLClient {

    private final WebClient webClient;
    private final GlificAuthService glificAuthService;

    @Value("${glific.api-url:}")
    private String apiUrl;

    @Value("${glific.request-interval-ms:500}")
    private long requestIntervalMs;

    private final AtomicLong lastCallEpochMs = new AtomicLong(0);

    public GlificGraphQLClient(WebClient.Builder builder, GlificAuthService glificAuthService) {
        this.webClient = builder.build();
        this.glificAuthService = glificAuthService;
    }

    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long RATE_LIMIT_BASE_WAIT_MS = 5_000; // 5s, 10s, 20s
    private static final long JITTER_MS = 1_000; // ±1s jitter on 429 backoff

    public JsonNode execute(String query, Map<String, Object> variables) {
        // boundedElastic is designed for blocking work — keeps Thread.sleep and .block()
        // off the Netty event-loop and Kafka listener threads.
        return Mono.fromCallable(() -> executeWithRetry(query, variables, false, 0))
                .subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofMinutes(2));
    }

    private JsonNode executeWithRetry(String query, Map<String, Object> variables,
                                      boolean tokenRefreshed, int attempt) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new RuntimeException("Glific API URL is not configured (glific.api-url)");
        }

        throttleIfNeeded();

        // Capture the token before sending so we can compare it in auth-failure branches.
        // If another thread already refreshed the token by the time we get a 401,
        // the tokens will differ and we skip a redundant refresh call.
        String tokenUsed = glificAuthService.getAccessToken();

        JsonNode response;
        try {
            response = webClient.post()
                .uri(apiUrl)
                .header("Authorization", tokenUsed)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("query", query, "variables", variables))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));
        } catch (WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                long baseWait = RATE_LIMIT_BASE_WAIT_MS * (1L << attempt); // 5s → 10s → 20s
                long jitter = ThreadLocalRandom.current().nextLong(-JITTER_MS, JITTER_MS + 1);
                long waitMs = Math.max(0, baseWait + jitter);
                log.warn("[Glific] Rate limited (429), waiting {}ms before retry {}/{}",
                         waitMs, attempt + 1, MAX_RATE_LIMIT_RETRIES);
                try { Thread.sleep(waitMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during Glific rate-limit backoff", ie);
                }
                // Only the rate-limit counter advances; auth state is unchanged
                return executeWithRetry(query, variables, tokenRefreshed, attempt + 1);
            }
            if (!tokenRefreshed && (status == 401 || status == 403)) {
                glificAuthService.refreshIfStale(tokenUsed);
                // Do not advance attempt — the rate-limit budget should not be consumed by auth retries
                return executeWithRetry(query, variables, true, attempt);
            }
            throw new RuntimeException("Glific GraphQL HTTP error: " + ex.getStatusCode(), ex);
        }

        if (response == null) {
            throw new RuntimeException("Null response from Glific GraphQL");
        }

        if (response.has("errors") && response.get("errors").size() > 0) {
            String msg = response.get("errors").get(0).path("message").asText("unknown");
            if (!tokenRefreshed && (msg.toLowerCase().contains("unauthenticated")
                    || msg.toLowerCase().contains("unauthorized"))) {
                glificAuthService.refreshIfStale(tokenUsed);
                // Do not advance attempt — the rate-limit budget should not be consumed by auth retries
                return executeWithRetry(query, variables, true, attempt);
            }
            throw new RuntimeException("Glific GraphQL error: " + msg);
        }
        JsonNode dataNode = response.get("data");
        if (dataNode == null || dataNode.isNull()) {
            throw new RuntimeException("Glific GraphQL response missing 'data' node");
        }
        return dataNode;
    }

    private void throttleIfNeeded() {
        long last = lastCallEpochMs.get();
        long now = System.currentTimeMillis();
        long elapsed = now - last;
        if (elapsed < requestIntervalMs) {
            long sleepMs = requestIntervalMs - elapsed;
            log.debug("[Glific] Throttling: sleeping {}ms before next request", sleepMs);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during Glific rate throttle", ie);
            }
            now = System.currentTimeMillis();
        }
        // updateAndGet retries internally on CAS failure, ensuring this call's
        // timestamp is always recorded even if another thread raced ahead.
        // Math.max prevents the clock from going backwards under contention.
        final long recorded = now;
        lastCallEpochMs.updateAndGet(prev -> Math.max(prev, recorded));
    }
}
