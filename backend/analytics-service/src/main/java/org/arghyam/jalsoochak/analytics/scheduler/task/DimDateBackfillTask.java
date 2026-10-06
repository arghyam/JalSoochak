package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class DimDateBackfillTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final DateDimensionService dateDimensionService;

    @Override
    public String taskName() {
        return "dim-date-backfill";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.dim-date-backfill.cron:0 10 0 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        LocalDate targetDate = LocalDate.now(IST_ZONE).minusDays(1);
        log.info("Running scheduled task '{}' for targetDate={}", taskName(), targetDate);
        try {
            dateDimensionService.ensureDateExists(targetDate);
        } catch (DataIntegrityViolationException e) {
            // Every pod runs this cron; the pods that lose the insert race hit the primary key.
            log.info("dim_date entry for {} was created by another instance", targetDate);
        }
        log.info("Completed scheduled task '{}' for targetDate={}", taskName(), targetDate);
        log.info("Scheduler END '{}'", taskName());
    }
}
