package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.service.AggregationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Top-of-hour aggregation: (1) populates fact_submission_activity_hourly_table for the hour
 * that just completed, and (2) re-rolls the CURRENT day's DAY-grain aggregates so dashboard
 * counts change every hour as new submissions arrive. Only the DAY grain is refreshed
 * hourly (today's rows stay provisional); the in-progress WEEK/MONTH buckets are rolled by
 * the midnight task, keeping the hourly job's write churn off those larger grains.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubmissionActivityHourlyTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final AggregationService aggregationService;

    @Override
    public String taskName() {
        return "submission-activity-hourly";
    }

    @Override
    @Scheduled(
            cron = "${analytics.aggregation.hourly.cron:0 5 * * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        LocalDateTime now = LocalDateTime.now(IST_ZONE);
        LocalDateTime previousHour = now.minusHours(1).withMinute(0).withSecond(0).withNano(0);
        LocalDate today = now.toLocalDate();
        log.info("Running scheduled task '{}' for hour={} and current-day refresh={}",
                taskName(), previousHour, today);
        aggregationService.aggregateHour(previousHour);
        // Refresh today's provisional DAY rollups so dashboard counts update hourly.
        // WEEK/MONTH are left to the midnight run to limit intraday dead-tuple churn.
        aggregationService.aggregateDayGrain(today, today);
        log.info("Completed scheduled task '{}' for hour={}", taskName(), previousHour);
        log.info("Scheduler END '{}'", taskName());
    }
}
