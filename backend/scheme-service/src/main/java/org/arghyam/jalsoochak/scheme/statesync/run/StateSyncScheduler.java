package org.arghyam.jalsoochak.scheme.statesync.run;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.TimeZone;

/**
 * Registers the delta and full crons — only when {@code state-sync.enabled=true}; with the flag off this
 * bean does not exist and nothing is scheduled.
 *
 * <p>Every replica registers the same crons; the run lock ({@link StateSyncRunRepository#claim}) is what
 * makes exactly one of them do the work. A delta that fires while the nightly full run is still going
 * finds the lock taken and skips — the full run covers it.
 */
@Component
@ConditionalOnProperty(prefix = "state-sync", name = "enabled", havingValue = "true")
@Slf4j
public class StateSyncScheduler {

    private final StateSyncProperties properties;
    private final StateSyncRunner runner;
    private ThreadPoolTaskScheduler scheduler;

    public StateSyncScheduler(StateSyncProperties properties, StateSyncRunner runner) {
        this.properties = properties;
        this.runner = runner;
    }

    @PostConstruct
    void start() {
        TimeZone zone = TimeZone.getTimeZone(properties.getZone());
        // Fail at startup, not at 2 AM, on a malformed expression.
        CronTrigger delta = new CronTrigger(properties.getDeltaCron(), zone);
        CronTrigger full = new CronTrigger(properties.getFullCron(), zone);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("state-sync-cron-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        scheduler.schedule(() -> runner.runScheduled(RunKind.DELTA), delta);
        scheduler.schedule(() -> runner.runScheduled(RunKind.FULL), full);
        log.info("[state-sync] scheduled tenant={} mode={} delta='{}' full='{}' zone={}",
                properties.getTenantCode(), properties.getMode(), properties.getDeltaCron(),
                properties.getFullCron(), properties.getZone());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
