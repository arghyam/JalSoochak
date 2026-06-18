package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.SchemePerformanceSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchemePerformanceScoreTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final SchemePerformanceSchedulerService schemePerformanceSchedulerService;

    @Override
    public String taskName() {
        return "scheme-performance-score";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 0 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        LocalDate targetDate = LocalDate.now(IST_ZONE);
        log.info("Running scheduled task '{}' for date {}", taskName(), targetDate);
        int insertedRows = schemePerformanceSchedulerService.insertDailySchemePerformanceScores(targetDate);
        log.info("Completed scheduled task '{}' for date {} with insertedRows={}", taskName(), targetDate, insertedRows);
        log.info("Scheduler END '{}'", taskName());
    }
}
