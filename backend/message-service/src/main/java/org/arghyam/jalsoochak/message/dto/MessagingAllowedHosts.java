package org.arghyam.jalsoochak.message.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PER-TENANT-PROVIDERS: the {@code MESSAGING_PROVIDER_ALLOWED_HOSTS} system config value — the
 * hosts a tenant's SMTP settings are allowed to name (O2-13).
 *
 * <p>The read-side twin of tenant-service's {@code MessagingAllowedHostsConfigDTO}. Only the
 * matching half is carried over: validating and normalising the patterns is the writer's job, and a
 * super user is the only one who can write them. This side takes the stored list as it finds it and
 * normalises each pattern as it compares, so a pattern stored by an older tenant-service that did
 * not lower-case still matches.
 *
 * <p>An entry is either an exact host name or a {@code *.suffix} wildcard, which matches any host
 * below the suffix at any depth but <em>not</em> the suffix itself.
 *
 * <p>An unset, empty or unparseable list means no tenant may use SMTP. Failing closed is the whole
 * point of the key: the alternative would be that a missing row silently allows every host, which
 * is exactly the state an attacker would try to arrange.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessagingAllowedHosts(List<String> smtp) {

    private static final String WILDCARD_PREFIX = "*.";

    /** The fail-closed value: no host is allowed. */
    public static final MessagingAllowedHosts NONE = new MessagingAllowedHosts(List.of());

    public MessagingAllowedHosts {
        // Copied through ArrayList rather than List.copyOf, which throws on a null element: a
        // stored list is written by another service and a null entry there is a pattern to skip,
        // not a reason to fail the read and take every SMTP tenant down with it.
        smtp = smtp == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(smtp));
    }

    /** Whether {@code host} is covered, compared case-insensitively. */
    public boolean allowsSmtpHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String candidate = host.trim().toLowerCase(Locale.ROOT);
        for (String raw : smtp) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.startsWith(WILDCARD_PREFIX)) {
                // endsWith(".suffix") alone would let the apex through on a bare suffix of equal
                // length, so the candidate must be strictly longer than ".suffix".
                String suffix = pattern.substring(1);
                if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                    return true;
                }
            } else if (pattern.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
