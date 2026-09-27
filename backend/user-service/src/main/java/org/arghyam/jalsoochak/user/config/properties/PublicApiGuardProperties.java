package org.arghyam.jalsoochak.user.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the anonymous public API abuse guard.
 *
 * <p>Request-rate limiting belongs at the edge (nginx / ingress), where a flood is absorbed before
 * it reaches a JVM thread. What the edge cannot do is count <em>distinct entities</em> a caller has
 * asked for — and that is the signal that separates a villager reading one scheme from a scraper
 * walking the whole tenant. This guard covers only that gap; it is not a substitute for the edge.
 */
@ConfigurationProperties(prefix = "public-api.guard")
@Component
@Getter
@Setter
public class PublicApiGuardProperties {

    /** Master off-switch. When false the filter passes every request straight through. */
    private boolean enabled = true;

    /** When false, threshold breaches are logged but never rejected — use to observe before enforcing. */
    private boolean blocking = true;

    /** Length of the rolling window, in seconds, over which distinct entities are counted. */
    private int windowSeconds = 3600;

    /**
     * Distinct operator/scheme identifiers one client may request per window before a WARN is
     * logged. Set well above normal browsing: one village page fans out across every scheme in the
     * village, so an analyst comparing many villages legitimately accumulates hundreds per hour.
     */
    private int warnDistinctEntities = 250;

    /**
     * Distinct identifiers per window above which requests are rejected with 429.
     *
     * <p>Deliberately far above any plausible human session and far below a full harvest — the
     * tenant this was written for has ~20,000 operators, so a scrape is caught with a 20x margin
     * while a heavy real user is not. Tune from the WARN volume once it has run in production;
     * setting {@code blocking=false} first lets you observe without turning anyone away.
     */
    private int maxDistinctEntities = 1000;

    /**
     * Largest number of client keys tracked at once. Bounds the guard's own memory so that a flood
     * from many source addresses cannot exhaust the heap; the least-recently-seen key is evicted.
     */
    private int maxTrackedClients = 50_000;

    /**
     * Header carrying the real client address, e.g. {@code X-Forwarded-For}. Leave unset to use the
     * socket address.
     *
     * <p>Only set this when the edge proxy <em>overwrites</em> the header rather than appending to
     * it. If a client can supply the header itself, it can rotate the value per request and the
     * guard becomes trivially bypassable — worse than not running it at all.
     */
    private String clientIpHeader = "";
}
