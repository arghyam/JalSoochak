package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.service.NotificationDeliveryAggregationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Hourly rebuild of the notification delivery rollups for the last {@code lookback-days} IST days,
 * so delivery receipts that arrive after the send (read receipts, late failures) are absorbed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryAggregationTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final NotificationDeliveryAggregationService aggregationService;

    @Value("${analytics.notification-delivery.aggregation.lookback-days:3}")
    private int lookbackDays;

    @Override
    public String taskName() {
        return "notification-delivery-aggregation";
    }

    @Override
    @Scheduled(
            cron = "${analytics.notification-delivery.aggregation.cron:0 15 * * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        int sanitizedLookback = Math.max(0, lookbackDays);
        LocalDate today = LocalDate.now(IST_ZONE);
        LocalDate from = today.minusDays(sanitizedLookback);
        log.info("Running scheduled task '{}' for window {}..{}", taskName(), from, today);
        aggregationService.recompute(from, today);
        log.info("Completed scheduled task '{}' for window {}..{}", taskName(), from, today);
        log.info("Scheduler END '{}'", taskName());
    }
}
