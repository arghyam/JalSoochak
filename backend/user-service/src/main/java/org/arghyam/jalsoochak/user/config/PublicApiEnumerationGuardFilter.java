package org.arghyam.jalsoochak.user.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.arghyam.jalsoochak.user.config.properties.PublicApiGuardProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Counts how many <em>distinct</em> pump operators and schemes one anonymous caller has asked for,
 * and rejects a caller that is plainly walking the table.
 *
 * <p>A request-per-minute limit cannot tell these two callers apart: a villager refreshing one
 * scheme's page and a scraper fetching a different scheme every time both issue the same number of
 * requests. The distinguishing signal is the breadth of what they ask for — a real reader touches a
 * handful of identifiers, a scraper touches thousands. That is what this filter measures.
 *
 * <p>The tenant code is part of each entity key, so walking the same ids across tenants
 * ({@code tenantCode=AS} then {@code tenantCode=UP}) spends the budget rather than resetting it.
 *
 * <p><b>Scope and limits.</b> Counters are per-JVM and in-memory: with N replicas behind a load
 * balancer a caller gets roughly N times the budget, and a restart clears them. It is deliberately
 * a coarse net for bulk collection, layered under — not instead of — request-rate limiting at the
 * edge. A distributed scrape across many source addresses defeats it; the WARN it emits is what
 * makes that visible.
 */
@Component
public class PublicApiEnumerationGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicApiEnumerationGuardFilter.class);

    private static final String PUBLIC_PREFIX = "/api/v1/pumpoperator/pump-operators/";
    private static final String BY_UUID_PATH = PUBLIC_PREFIX + "by-uuid/";
    private static final String BY_SCHEME_PATH = PUBLIC_PREFIX + "by-scheme";

    /** Leading hex characters of the client digest written to logs — enough to correlate, not to reverse. */
    private static final int LOGGED_KEY_CHARS = 12;

    private final PublicApiGuardProperties properties;

    /**
     * Per-JVM random salt for the client digest. Random rather than configured because the counters
     * it keys are themselves per-JVM and cleared on restart, so nothing needs the digest to be
     * stable across processes — and a salt that is never persisted or logged cannot leak. Without
     * it, a digest of an IPv4 address is reversible by brute force: the space is only 2^32.
     */
    private final byte[] clientKeySalt = newSalt();

    /**
     * A {@code @WebMvcTest} slice instantiates filter beans but does not scan
     * {@code @ConfigurationProperties}, so the properties bean is resolved leniently and falls back
     * to its defaults. In a full context the scanned bean is always present and wins.
     */
    @Autowired
    public PublicApiEnumerationGuardFilter(ObjectProvider<PublicApiGuardProperties> properties) {
        this(properties.getIfAvailable(PublicApiGuardProperties::new));
    }

    PublicApiEnumerationGuardFilter(PublicApiGuardProperties properties) {
        this.properties = properties;
    }

    /** Access-ordered so the eldest entry is the least recently seen client, not the oldest inserted. */
    private final Map<String, ClientWindow> windows = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ClientWindow> eldest) {
                    return size() > Math.max(1, properties.getMaxTrackedClients());
                }
            });

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !servletPath(request).startsWith(PUBLIC_PREFIX);
    }

    /**
     * The request path with any servlet context path removed, so the prefix checks below keep
     * matching if the service is ever deployed under one. Comparing the raw URI would make this
     * guard silently stop firing — a security control failing open on a deployment setting.
     */
    private static String servletPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Set<String> entities = entityKeys(request);
        if (entities.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String client = clientKey(request);
        int distinct = record(client, entities);

        if (distinct > properties.getMaxDistinctEntities()) {
            // Logged at WARN with a truncated hashed client key: the raw address is itself personal
            // data, and the prefix is stable enough to correlate repeated abuse from one source.
            log.warn("Public API enumeration budget exceeded: client={} distinctEntities={} uri={} — {}",
                    loggedClient(client), distinct, request.getRequestURI(),
                    properties.isBlocking() ? "rejected" : "observed only");
            if (properties.isBlocking()) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(properties.getWindowSeconds()));
                return;
            }
        } else if (distinct > properties.getWarnDistinctEntities()) {
            log.warn("Public API enumeration threshold crossed: client={} distinctEntities={} uri={}",
                    loggedClient(client), distinct, request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The identifiers this request asks for, each namespaced by tenant so the same numeric id in a
     * different tenant counts as a separate entity.
     */
    private Set<String> entityKeys(HttpServletRequest request) {
        String tenantCode = request.getParameter("tenantCode");
        String tenant = tenantCode == null ? "?" : tenantCode.trim().toLowerCase();
        String uri = servletPath(request);
        Set<String> keys = new HashSet<>();

        if (uri.startsWith(BY_UUID_PATH)) {
            String uuid = uri.substring(BY_UUID_PATH.length());
            if (!uuid.isBlank()) {
                keys.add(tenant + ":u:" + uuid);
            }
        }
        if (uri.startsWith(BY_SCHEME_PATH)) {
            addSchemeKeys(keys, tenant, request.getParameterValues("schemeId"));
            addSchemeKeys(keys, tenant, request.getParameterValues("schemeIds"));
        }
        return keys;
    }

    private void addSchemeKeys(Set<String> keys, String tenant, String[] values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            // schemeIds arrives either repeated or comma-joined depending on the client.
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    keys.add(tenant + ":s:" + trimmed);
                }
            }
        }
    }

    /** @return distinct entities this client has requested in the current window, after recording. */
    private int record(String client, Set<String> entities) {
        Instant now = Instant.now();
        Duration window = Duration.ofSeconds(Math.max(1, properties.getWindowSeconds()));
        int ceiling = Math.max(1, properties.getMaxDistinctEntities());

        synchronized (windows) {
            ClientWindow current = windows.get(client);
            if (current == null || Duration.between(current.startedAt, now).compareTo(window) >= 0) {
                current = new ClientWindow(now);
                windows.put(client, current);
            }
            // Hashes rather than the keys themselves: a collision only undercounts a coarse abuse
            // signal, and it keeps the guard's worst-case footprint bounded and predictable.
            // Growth stops once the ceiling is passed — the verdict cannot change after that.
            if (current.seen.size() <= ceiling) {
                for (String entity : entities) {
                    current.seen.add(entity.hashCode());
                }
            }
            return current.seen.size();
        }
    }

    /**
     * Stable, non-reversible client identifier: the full SHA-256 of a salted client address.
     *
     * <p>The full digest is the map key on purpose. A short hash would collide — over 50,000
     * tracked clients a 32-bit key collides with roughly one-in-four odds — and a collision here
     * fails <em>closed</em>: two unrelated callers would share one budget and an ordinary visitor
     * would be turned away for someone else's scraping. Only the logged form is truncated.
     *
     * <p>Falls back to the socket address unless an edge proxy header is explicitly configured —
     * see {@link PublicApiGuardProperties#getClientIpHeader()} for why trusting that header
     * carelessly makes the guard bypassable.
     */
    private String clientKey(HttpServletRequest request) {
        String address = null;
        String header = properties.getClientIpHeader();
        if (header != null && !header.isBlank()) {
            String value = request.getHeader(header.trim());
            if (value != null && !value.isBlank()) {
                // Left-most entry is the originating client for a proxy that appends correctly.
                address = value.split(",")[0].trim();
            }
        }
        if (address == null || address.isBlank()) {
            address = request.getRemoteAddr();
        }
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every conformant JRE ships SHA-256; reaching this means the platform is broken.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        sha256.update(clientKeySalt);
        return HexFormat.of().formatHex(sha256.digest(address.getBytes(StandardCharsets.UTF_8)));
    }

    /** The client key as it may appear in a log line: a correlatable prefix, never the whole digest. */
    private static String loggedClient(String clientKey) {
        return clientKey.length() <= LOGGED_KEY_CHARS ? clientKey : clientKey.substring(0, LOGGED_KEY_CHARS);
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static final class ClientWindow {
        private final Instant startedAt;
        private final Set<Integer> seen = new HashSet<>();

        private ClientWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
