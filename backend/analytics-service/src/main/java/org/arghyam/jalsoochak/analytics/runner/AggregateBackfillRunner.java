package org.arghyam.jalsoochak.analytics.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.service.AggregationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One-off backfill of the pre-aggregation tables from existing facts. Disabled by
 * default; enable with {@code analytics.aggregation.backfill.enabled=true} and set
 * {@code analytics.aggregation.backfill.start-date}, then turn it off again.
 *
 * <p>Runs on a background thread once the application is ready, so a long backfill never
 * holds up startup, readiness or a rolling deploy. Works in monthly chunks through today's
 * Indian date, one transaction each; every chunk waits for the aggregation lock rather than
 * skipping, so it never races the scheduled jobs or another pod. Idempotent: a restart
 * mid-way simply redoes the chunks.</p>
 */
@Component
@ConditionalOnProperty(prefix = "analytics.aggregation.backfill", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AggregateBackfillRunner {

    private final AggregationService aggregationService;

    @Value("${analytics.aggregation.backfill.start-date}")
    private String startDate;

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /** Supplies only the instant; the date is always read in IST. Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread worker = new Thread(this::backfill, "aggregation-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    private void backfill() {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate today = LocalDate.ofInstant(clock.instant(), IST_ZONE);
        log.info("[aggregation-backfill] START from {} to {}", start, today);
        try {
            for (LocalDate chunkStart = start; !chunkStart.isAfter(today); chunkStart = chunkStart.plusMonths(1).withDayOfMonth(1)) {
                LocalDate chunkEnd = chunkStart.withDayOfMonth(chunkStart.lengthOfMonth());
                if (chunkEnd.isAfter(today)) {
                    chunkEnd = today;
                }
                long began = System.currentTimeMillis();
                aggregationService.backfillWindow(chunkStart, chunkEnd);
                log.info("[aggregation-backfill] chunk {}..{} done in {} ms",
                        chunkStart, chunkEnd, System.currentTimeMillis() - began);
            }
            log.info("[aggregation-backfill] DONE");
        } catch (RuntimeException e) {
            log.error("[aggregation-backfill] FAILED; completed chunks are kept, rerun to finish", e);
        }
    }
}
