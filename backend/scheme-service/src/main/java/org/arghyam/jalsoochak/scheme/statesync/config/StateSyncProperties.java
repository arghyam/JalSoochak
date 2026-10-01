package org.arghyam.jalsoochak.scheme.statesync.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the state master-data sync (Assam: JJM Brain).
 *
 * <p>{@link #enabled} is the master switch. While it is false no schedule is registered, the admin
 * endpoints refuse to start a run, and nothing calls the upstream API — the feature is inert.
 *
 * <p>{@link #mode} decides what an enabled run does with what it learns. {@code DRY_RUN} (the
 * default) applies every change inside a transaction that is then rolled back, so the run's counts
 * and issue rows describe exactly what {@code APPLY} would do without writing any of it.
 */
@ConfigurationProperties(prefix = "state-sync")
@Getter
@Setter
public class StateSyncProperties {

    public enum Mode { DRY_RUN, APPLY }

    private boolean enabled = false;

    private Mode mode = Mode.DRY_RUN;

    /** State code of the tenant being synced, e.g. {@code as}. */
    private String tenantCode;

    /**
     * {@code user_table.id} stamped on {@code created_by} / {@code updated_by}. The scheme mapping
     * tables require it (NOT NULL, FK to user_table) and a scheduled run has no caller to take it from.
     */
    private Integer actorUserId;

    /** Hourly scheme delta. Spring 6-field cron, evaluated in {@link #zone}. */
    private String deltaCron = "0 15 * * * *";

    /** Nightly full reconcile: masters, users, every scheme, archived and blocked lists. */
    private String fullCron = "0 0 2 * * *";

    private String zone = "Asia/Kolkata";

    /**
     * Re-read this much before the last watermark on every delta. The upstream {@code updated_at}
     * carries no timezone, and a re-read row is harmless because every write is idempotent.
     */
    private Duration deltaOverlap = Duration.ofMinutes(30);

    /** A RUNNING claim whose heartbeat is older than this is treated as a crashed pod's and taken over. */
    private Duration staleRunAfter = Duration.ofHours(2);

    /** An archived scheme with a flow reading this recent keeps its officers, and an issue is raised. */
    private int archiveSpareReadingDays = 90;

    /**
     * Whether an empty officer / village / sub-division list on a scheme retires the mappings we hold.
     * Off until the state confirms an empty list means "none" rather than "not captured".
     */
    private boolean retireOnEmptyList = false;

    /**
     * Each delta also looks up this many lenient-ingestion placeholder schemes (newest first) by their
     * IMIS id, so a reading filed against a scheme we did not know turns into a real scheme within one
     * delta interval instead of waiting for the nightly run. 0 switches the look-up off.
     */
    private int placeholderLookupsPerRun = 25;

    /** A placeholder the upstream does not know either is not asked about again for this long. */
    private Duration placeholderRetryAfter = Duration.ofHours(24);

    private Jjm jjm = new Jjm();

    @Getter
    @Setter
    public static class Jjm {
        private String baseUrl = "https://jjmbrain.in/api/v1";
        private String apiKey;
        /** Minimum gap between two requests — a polite ceiling until the state publishes a rate limit. */
        private Duration minRequestInterval = Duration.ofMillis(500);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
        private int maxAttempts = 4;
        private Duration initialBackoff = Duration.ofSeconds(2);
        /** Hard stop for a paged crawl, in case the upstream never returns an empty page. */
        private int maxPages = 2000;
    }
}
