package org.arghyam.jalsoochak.telemetry.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriTemplateHandler;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pre-flight policy for meter-image URLs that arrive in a webhook payload.
 *
 * <p>The URL is parsed exactly the way {@code RestTemplate} will parse it before the connection is
 * opened, so the host that is checked here is the host that is dialled — a validator with its own
 * parser would leave a differential to exploit.
 *
 * <p>This is the first of two gates. It rejects obvious abuse early and with a non-retriable error;
 * {@link SsrfGuardDnsResolver} then re-checks at connect time, which is what actually closes DNS
 * rebinding and covers every redirect hop.
 */
@Slf4j
public class MediaUrlValidator {

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");
    private static final int MAX_LOGGED_HOST_LENGTH = 100;

    private final boolean enabled;
    private final boolean requireHttps;
    private final Set<String> allowedHosts;
    private final SsrfAddressPolicy addressPolicy;
    private final HostResolver hostResolver;
    private final UriTemplateHandler uriTemplateHandler = new DefaultUriBuilderFactory();

    public MediaUrlValidator(boolean enabled,
                             boolean requireHttps,
                             Set<String> allowedHosts,
                             SsrfAddressPolicy addressPolicy,
                             HostResolver hostResolver) {
        this.enabled = enabled;
        this.requireHttps = requireHttps;
        this.allowedHosts = normalizeAllowedHosts(allowedHosts);
        this.addressPolicy = addressPolicy;
        this.hostResolver = hostResolver;
    }

    /**
     * Parses and vets a caller-supplied media URL.
     *
     * @return the URI to fetch, already expanded so the caller does not re-parse the string
     * @throws MediaUrlNotAllowedException when the URL is unusable or points somewhere it must not
     */
    public URI validate(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new MediaUrlNotAllowedException("blank media url");
        }
        URI uri;
        try {
            uri = uriTemplateHandler.expand(mediaUrl.trim());
        } catch (RuntimeException e) {
            throw reject("unparseable", null, "media url could not be parsed");
        }
        validateTarget(uri);
        return uri;
    }

    /**
     * Applies the same policy to a URI the client has already resolved — a redirect target, in
     * practice. Kept separate from {@link #validate(String)} because the string form has been
     * parsed by the HTTP client by then, and re-expanding it would double-encode.
     */
    public void validateTarget(URI uri) {
        if (!enabled) {
            return;
        }
        if (uri == null || !uri.isAbsolute()) {
            throw reject("not absolute", null, "media url is not absolute");
        }

        String host = hostOf(uri);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SCHEMES.contains(scheme)) {
            throw reject("scheme not supported", host, "unsupported scheme " + scheme);
        }
        if (requireHttps && !"https".equals(scheme)) {
            throw reject("https required", host, "plain http is not permitted");
        }
        // Credentials in the authority are never present on a genuine media URL and are a standard
        // way to make a permissive parser disagree with the client about which host is being dialled.
        if (uri.getRawUserInfo() != null) {
            throw reject("userinfo present", host, "media url carries userinfo");
        }
        if (host == null || host.isBlank()) {
            throw reject("no host", null, "media url has no host");
        }
        if (!isHostAllowed(host)) {
            throw reject("host not allowlisted", host, "host is not on the media allowlist");
        }
        requirePublicAddresses(host);
    }

    private void requirePublicAddresses(String host) {
        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw reject("unresolvable host", host, "media host could not be resolved");
        }
        if (resolved == null || resolved.length == 0) {
            throw reject("unresolvable host", host, "media host resolved to nothing");
        }
        // A single internal answer condemns the name. Filtering to the public answers instead would
        // still leave the attacker a working round of DNS rebinding.
        for (InetAddress address : resolved) {
            if (!addressPolicy.isAllowed(address)) {
                throw reject("non-public address", host, "media host resolves to a non-public address");
            }
        }
    }

    private boolean isHostAllowed(String host) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        String normalized = normalizeHost(host);
        for (String allowed : allowedHosts) {
            if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private MediaUrlNotAllowedException reject(String reason, String host, String logMessage) {
        // The host is safe to log — the credentials on a pre-signed media URL live in the query
        // string — and it is what an operator needs to tune the allowlist or triage a probe.
        log.warn("media_url_rejected reason=\"{}\" host={} detail=\"{}\"",
                reason, sanitizeForLog(host), logMessage);
        return new MediaUrlNotAllowedException(reason);
    }

    private static String hostOf(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        // URI keeps the brackets on an IPv6 literal; name resolution does not want them.
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static String sanitizeForLog(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        String sanitized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return sanitized.length() <= MAX_LOGGED_HOST_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_LOGGED_HOST_LENGTH) + "...";
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        // A trailing dot is the same name to a resolver but a different string to an allowlist.
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Set<String> normalizeAllowedHosts(Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String host : allowedHosts) {
            if (host != null && !host.isBlank()) {
                normalized.add(normalizeHost(host.trim()));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    public static Set<String> parseAllowedHosts(String commaSeparatedHosts) {
        if (commaSeparatedHosts == null || commaSeparatedHosts.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(commaSeparatedHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
