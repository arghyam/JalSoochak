package org.arghyam.jalsoochak.analytics.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Picks one pod to run a once-a-day task. Every pod fires the same cron; the first to set
 * {@code <app>:scheduler-claim:<task>:<IST date>} in Redis runs the task, and the others skip it.
 *
 * <p>Only for tasks that just warm Redis: when Redis can't be reached the task is skipped, since it
 * could not write anything anyway. A claim is kept when its run fails, so that day is not retried;
 * the caches then fill on the first request.</p>
 */
@Component
@Slf4j
public class ScheduledTaskClaim {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    /** Outlives the IST day the key names, so a pod that fires late that day still sees the claim. */
    private static final Duration CLAIM_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;
    private final String appName;
    private final String instanceId;

    /** Supplies only the instant; the date is always read in IST. Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public ScheduledTaskClaim(
            StringRedisTemplate redisTemplate,
            @Value("${spring.application.name}") String appName,
            @Value("${spring.cloud.client.hostname}") String instanceId) {
        this.redisTemplate = redisTemplate;
        this.appName = appName;
        this.instanceId = instanceId;
    }

    /**
     * Claims today's (IST) run of {@code taskName} for this pod.
     *
     * @return {@code true} if this pod should run the task; {@code false} if another pod already
     *         claimed it or Redis could not be reached
     */
    public boolean claimToday(String taskName) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), IST_ZONE);
        String key = appName + ":scheduler-claim:" + taskName + ":" + today;
        try {
            if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, instanceId, CLAIM_TTL))) {
                return true;
            }
            log.info("Scheduler SKIP '{}': another instance claimed the run for {}", taskName, today);
            return false;
        } catch (DataAccessException ex) {
            log.warn("Scheduler SKIP '{}': could not claim the run for {} in Redis", taskName, today, ex);
            return false;
        }
    }
}
