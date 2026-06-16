package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.service.AggregationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Nightly (midnight IST) population of the KPI pre-aggregation tables.
 *
 * <p>Recomputes the last {@code lookback-days} days so late-arriving / corrected /
 * soft-deleted readings are absorbed, and refreshes the DAY/WEEK/MONTH region
 * rollups that those days touch. Runs on the shared analytics scheduler cron,
 * which now fires at midnight so a "day" is 12am->12am.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DailyAggregationTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final AggregationService aggregationService;

    @Value("${analytics.aggregation.daily.lookback-days:3}")
    private int lookbackDays;

    @Override
    public String taskName() {
        return "daily-aggregation";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 0 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        int sanitizedLookback = Math.max(0, lookbackDays);
        LocalDate today = LocalDate.now(IST_ZONE);
        LocalDate from = today.minusDays(sanitizedLookback);
        log.info("Running scheduled task '{}' for window {}..{}", taskName(), from, today);
        aggregationService.aggregateWindow(from, today);
        log.info("Completed scheduled task '{}' for window {}..{}", taskName(), from, today);
        log.info("Scheduler END '{}'", taskName());
    }
}
