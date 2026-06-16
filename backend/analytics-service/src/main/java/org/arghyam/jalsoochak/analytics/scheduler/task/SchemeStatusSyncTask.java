package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.SchemeStatusSchedulerService;
import org.arghyam.jalsoochak.analytics.service.SchemeStatusSchedulerService.SchemeStatusSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchemeStatusSyncTask implements AnalyticsScheduledTask {

    private final SchemeStatusSchedulerService schemeStatusSchedulerService;

    @Value("${analytics.scheduler.scheme-status.inactive-after-days:30}")
    private int inactiveAfterDays;

    @Override
    public String taskName() {
        return "scheme-status-sync";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 0 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        int sanitizedInactiveAfterDays = Math.max(0, inactiveAfterDays);
        log.info("Running scheduled task '{}' with inactive-after-days={}", taskName(), sanitizedInactiveAfterDays);

        SchemeStatusSyncResult result =
                schemeStatusSchedulerService.syncSchemeStatusBySubmissionRecency(sanitizedInactiveAfterDays);

        log.info(
                "Completed scheduled task '{}': total={}, active={}, inactive={}",
                taskName(),
                result.totalSchemes(),
                result.activeMarkedCount(),
                result.inactiveMarkedCount());
        log.info("Scheduler END '{}'", taskName());
    }
}
