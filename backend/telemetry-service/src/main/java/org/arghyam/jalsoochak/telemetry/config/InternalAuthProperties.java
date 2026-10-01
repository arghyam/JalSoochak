package org.arghyam.jalsoochak.telemetry.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Configuration for the operations token that {@link InternalAuthFilter} checks on the internal
 * routes.
 *
 * <p>A hash, never the token, is configured, as for {@link WebhookAuthProperties}.
 *
 * <p>Unlike {@link WebhookAuthProperties}, a missing or malformed hash does <b>not</b> fail startup.
 * The internal routes are run by hand and only now and then, so a deploy that leaves the hash out
 * must not take ingestion down with it. Either way the internal routes are disabled: every call to
 * them gets 401, and the startup log says why, once.
 */
@Component
@ConfigurationProperties(prefix = "telemetry.internal.auth")
public class InternalAuthProperties {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthProperties.class);

    /** Lowercase SHA-256 hex. Anything else is almost certainly a raw token pasted by mistake. */
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private String tokenHash = "";

    /** The configured hash as bytes; {@code null} while the internal routes are disabled. */
    private byte[] resolvedTokenHash;

    @PostConstruct
    public void init() {
        String normalized = tokenHash == null ? "" : tokenHash.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            resolvedTokenHash = null;
            log.info("Internal routes disabled: telemetry.internal.auth.token-hash is not set "
                    + "(TELEMETRY_INTERNAL_AUTH_TOKEN_HASH). Every call to them gets 401.");
            return;
        }
        if (!SHA256_HEX.matcher(normalized).matches()) {
            resolvedTokenHash = null;
            // Deliberately does not echo the value: it may be the token rather than its hash.
            log.warn("Internal routes disabled: telemetry.internal.auth.token-hash is not 64-character "
                    + "SHA-256 hex (length was {}). Configure the SHA-256 hash of the token, not the token "
                    + "itself. Every call to them gets 401.", normalized.length());
            return;
        }
        resolvedTokenHash = HexFormat.of().parseHex(normalized);
        log.info("Internal routes enabled");
    }

    public boolean isEnabled() {
        return resolvedTokenHash != null;
    }

    /**
     * @return true when the internal routes are enabled and {@code rawToken} hashes to the configured
     *         hash
     */
    public boolean matches(String rawToken) {
        if (!isEnabled() || rawToken == null || rawToken.isBlank()) {
            return false;
        }
        // Constant time over two fixed-length digests, so the response time says nothing about how
        // much of the token was right.
        return MessageDigest.isEqual(sha256(rawToken.trim()), resolvedTokenHash);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }
}
