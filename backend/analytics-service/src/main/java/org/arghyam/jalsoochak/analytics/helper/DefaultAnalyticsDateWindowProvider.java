package org.arghyam.jalsoochak.analytics.helper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Single source of truth for the "default" date window used by APIs when start_date/end_date
 * are omitted, and by warm-cache tasks that must pre-populate those exact keys.
 *
 * Window definition (inclusive):
 * - endDate = yesterday in configured zone
 * - startDate = endDate - (lookbackDays - 1)
 */
@Component
public class DefaultAnalyticsDateWindowProvider {

    private final ZoneId zone;
    private final int lookbackDays;

    public DefaultAnalyticsDateWindowProvider(
            @Value("${analytics.scheduler.common.zone:Asia/Kolkata}") String zone,
            @Value("${analytics.scheduler.national-dashboard.lookback-days:30}") int lookbackDays
    ) {
        this.zone = ZoneId.of(zone);
        this.lookbackDays = Math.max(1, lookbackDays);
    }

    public DateWindow defaultWindow() {
        LocalDate endDate = LocalDate.now(zone).minusDays(1);
        LocalDate startDate = endDate.minusDays(lookbackDays - 1L);
        return new DateWindow(startDate, endDate);
    }

    public record DateWindow(LocalDate startDate, LocalDate endDate) {}
}

