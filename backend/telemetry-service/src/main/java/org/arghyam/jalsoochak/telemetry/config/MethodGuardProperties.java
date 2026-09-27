package org.arghyam.jalsoochak.telemetry.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Configuration for {@link DisallowedHttpMethodFilter}.
 *
 * <p>Follows {@link WebhookAuthProperties}: a mode enum parsed once at startup, a bad value failing
 * the boot rather than silently degrading, and a single {@code log.info} so the effective setting is
 * visible in the pod logs.
 *
 * <p><b>Why there is no {@code AUDIT} mode</b>, unlike {@link WebhookAuthProperties}. {@code AUDIT}
 * exists there to discover callers nobody knew about before a credential starts rejecting them. Here
 * there are none to discover: an OPTIONS request to this service currently receives an empty 200
 * carrying only an {@code Allow} header, and a browser CORS preflight already fails (this service
 * publishes no {@code CorsConfigurationSource}), so no client can be functionally depending on the
 * behaviour being removed. {@code OFF} is retained purely as a no-rebuild rollback.
 */
@Component
@ConfigurationProperties(prefix = "security.method-guard")
public class MethodGuardProperties {

    private static final Logger log = LoggerFactory.getLogger(MethodGuardProperties.class);

    public enum Mode {
        /** Reject anything outside the allowed-method set with 405, and suppress {@code Allow}. */
        ENFORCE,
        /** Skip the check entirely and leave responses untouched. This is the kill switch. */
        OFF
    }

    private String mode = Mode.ENFORCE.name();

    private Mode resolvedMode = Mode.ENFORCE;

    @PostConstruct
    public void init() {
        this.resolvedMode = parseMode(mode);
        log.info("HTTP method guard initialised: mode={} allowedMethods={}",
                resolvedMode, DisallowedHttpMethodFilter.ALLOWED_METHODS);
    }

    static Mode parseMode(String raw) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) {
            throw new IllegalStateException("security.method-guard.mode must not be blank");
        }
        try {
            return Mode.valueOf(candidate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Unknown security.method-guard.mode '" + candidate + "'. Expected one of ENFORCE, OFF.", e);
        }
    }

    public Mode getResolvedMode() {
        return resolvedMode;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
