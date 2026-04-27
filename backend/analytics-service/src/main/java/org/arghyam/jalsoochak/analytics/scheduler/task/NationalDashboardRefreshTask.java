package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@ConditionalOnProperty(prefix = "analytics", name = "single-tenant-mode", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NationalDashboardRefreshTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final SchemeRegularityService schemeRegularityService;

    @Value("${analytics.scheduler.national-dashboard.lookback-days:30}")
    private int lookbackDays;

    @Override
    public String taskName() {
        return "national-dashboard-refresh";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 19 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        int sanitizedLookbackDays = Math.max(0, lookbackDays);
        // Stable window for 7PM→7PM: anchor to yesterday (IST) to avoid midnight drift.
        LocalDate endDate = LocalDate.now(IST_ZONE).minusDays(1);
        LocalDate startDate = (sanitizedLookbackDays <= 0)
                ? endDate
                : endDate.minusDays(sanitizedLookbackDays - 1L);

        log.info("Running scheduled task '{}' for range {} to {}", taskName(), startDate, endDate);
        schemeRegularityService.refreshNationalDashboard(startDate, endDate);
        log.info("Completed scheduled task '{}' for range {} to {}", taskName(), startDate, endDate);
        log.info("Scheduler END '{}'", taskName());
    }
}
