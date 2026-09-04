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
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
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

    private final PublicApiGuardProperties properties;

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
        return !properties.isEnabled() || !request.getRequestURI().startsWith(PUBLIC_PREFIX);
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
            // Logged at WARN with a hashed client key: the raw address is itself personal data, and
            // the hash is stable enough to correlate repeated abuse from one source.
            log.warn("Public API enumeration budget exceeded: client={} distinctEntities={} uri={} — {}",
                    client, distinct, request.getRequestURI(),
                    properties.isBlocking() ? "rejected" : "observed only");
            if (properties.isBlocking()) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(properties.getWindowSeconds()));
                return;
            }
        } else if (distinct > properties.getWarnDistinctEntities()) {
            log.warn("Public API enumeration threshold crossed: client={} distinctEntities={} uri={}",
                    client, distinct, request.getRequestURI());
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
        String uri = request.getRequestURI();
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
     * Stable, non-reversible client identifier. Falls back to the socket address unless an edge
     * proxy header is explicitly configured — see {@link PublicApiGuardProperties#getClientIpHeader()}
     * for why trusting that header carelessly makes the guard bypassable.
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
        return address == null ? "unknown" : Integer.toHexString(address.hashCode());
    }

    private static final class ClientWindow {
        private final Instant startedAt;
        private final Set<Integer> seen = new HashSet<>();

        private ClientWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
