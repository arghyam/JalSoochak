package org.arghyam.jalsoochak.telemetry.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration for the Glific webhook shared-secret credential.
 *
 * <p>Hashes, never plaintext, are configured — matching the precedent already set by
 * {@code tenant_master_table.api_key_hash} and {@code TelemetryApiKeyService}. That buys a
 * constant-time comparison over fixed-length input for free, and means a leaked deployment manifest
 * or {@code kubectl describe} yields nothing usable.
 *
 * <p>Misconfiguration <b>fails startup</b> rather than degrading to "permit" or to "reject
 * everything". In a rolling deploy a failed start leaves the previous container serving, which is the
 * safe outcome for a live WhatsApp flow.
 */
@Component
@ConfigurationProperties(prefix = "telemetry.webhook.auth")
public class WebhookAuthProperties {

    private static final Logger log = LoggerFactory.getLogger(WebhookAuthProperties.class);

    /** Lowercase SHA-256 hex. Anything else is almost certainly a raw token pasted by mistake. */
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public enum Mode {
        /** Reject unauthenticated webhook requests with 401. */
        ENFORCE,
        /** Evaluate and record the outcome, but always serve. This is the kill switch. */
        AUDIT,
        /** Skip the check entirely. */
        OFF
    }

    private String mode = Mode.ENFORCE.name();
    private String headerName = "X-Webhook-Token";
    private String tokenHashes = "";

    private Mode resolvedMode = Mode.ENFORCE;
    private Set<String> resolvedTokenHashes = Set.of();

    @PostConstruct
    public void init() {
        this.resolvedMode = parseMode(mode);
        this.resolvedTokenHashes = parseTokenHashes(tokenHashes);

        if (headerName == null || headerName.isBlank()) {
            throw new IllegalStateException(
                    "telemetry.webhook.auth.header-name must not be blank");
        }
        this.headerName = headerName.trim();

        if (resolvedMode == Mode.ENFORCE && resolvedTokenHashes.isEmpty()) {
            throw new IllegalStateException(
                    "telemetry.webhook.auth.mode=ENFORCE requires at least one entry in "
                            + "telemetry.webhook.auth.token-hashes (set TELEMETRY_WEBHOOK_AUTH_TOKEN_HASHES). "
                            + "Refusing to start rather than reject every Glific webhook call.");
        }

        // Count only — never the hashes themselves.
        log.info("Glific webhook auth initialised: mode={} header={} configuredTokens={}",
                resolvedMode, headerName, resolvedTokenHashes.size());
    }

    private static Mode parseMode(String raw) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) {
            throw new IllegalStateException("telemetry.webhook.auth.mode must not be blank");
        }
        try {
            return Mode.valueOf(candidate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Unknown telemetry.webhook.auth.mode '" + candidate + "'. Expected one of ENFORCE, AUDIT, OFF.", e);
        }
    }

    private static Set<String> parseTokenHashes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String entry : raw.split(",")) {
            String normalized = entry.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (!SHA256_HEX.matcher(normalized).matches()) {
                // Deliberately does not echo the value: it may be a raw token rather than a hash.
                throw new IllegalStateException(
                        "telemetry.webhook.auth.token-hashes contains an entry that is not 64-character "
                                + "lowercase SHA-256 hex (length was " + normalized.length() + "). "
                                + "Configure the SHA-256 hash of the token, not the token itself.");
            }
            parsed.add(normalized);
        }
        return Set.copyOf(parsed);
    }

    /**
     * @return true when {@code rawToken} hashes to one of the configured hashes.
     */
    public boolean matches(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || resolvedTokenHashes.isEmpty()) {
            return false;
        }
        byte[] candidate = sha256Hex(rawToken.trim()).getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String known : resolvedTokenHashes) {
            // |= rather than a short-circuiting || or an early return: every configured hash is
            // compared on every call, so response time does not reveal which entry matched.
            matched |= MessageDigest.isEqual(candidate, known.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }

    /**
     * Local SHA-256 rather than {@code TelemetryApiKeyService.hash}: that bean pulls in
     * {@code TenantConfigRepository}, and this credential is deliberately checked without touching
     * the database on the hot path of 26 endpoints.
     */
    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public Mode getResolvedMode() {
        return resolvedMode;
    }

    public Set<String> getResolvedTokenHashes() {
        return resolvedTokenHashes;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getTokenHashes() {
        return tokenHashes;
    }

    public void setTokenHashes(String tokenHashes) {
        this.tokenHashes = tokenHashes;
    }
}
