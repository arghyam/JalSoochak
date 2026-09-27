package org.arghyam.jalsoochak.message.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * PER-TENANT-PROVIDERS: the feature flag and cache settings for resolving a tenant's own email and
 * SMS provider at send time. Bound from {@code notification.per-tenant-providers.*}.
 *
 * <p>One flag covers both channels (O2-5). With it off — the default — every send uses the system
 * default provider built from {@code notification.mail.*}, {@code spring.mail.*} and
 * {@code smscountry.*}, which is today's behaviour, so an existing deployment needs no config
 * change and no tenant can be affected by a settings row someone has already written.
 */
@ConfigurationProperties(prefix = "notification.per-tenant-providers")
@Getter
@Setter
public class PerTenantProviderProperties {

    /**
     * Master switch. Default {@code false}: turning it on is the rollout step, turning it off is
     * the rollback, and neither needs a code change (§9).
     */
    private boolean enabled = false;

    /**
     * How long a resolved provider stays cached before it is rebuilt from the database.
     *
     * <p>This is a backstop, not the mechanism: {@code TenantConfigUpdatedListener} evicts within
     * seconds of a settings or secret write (O2-10). The TTL only bounds how long a stale entry can
     * survive a missed event — a broker outage, a restart mid-publish — so ten minutes is chosen to
     * be short enough that nobody debugs a stale provider for long, and long enough that the
     * settings query is not on the hot path of every message.
     */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /**
     * Upper bound on cached entries, one per (tenant, channel). Two per tenant, so the default
     * holds far more tenants than the platform has; it exists so a pathological id cannot grow the
     * map without limit.
     */
    private int cacheMaxSize = 500;
}
