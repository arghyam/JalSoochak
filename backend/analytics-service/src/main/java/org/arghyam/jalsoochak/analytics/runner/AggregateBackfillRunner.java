package org.arghyam.jalsoochak.analytics.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.service.AggregationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * One-off backfill of the pre-aggregation tables from existing facts. Disabled by
 * default; enable with {@code analytics.aggregation.backfill.enabled=true} and set
 * {@code analytics.aggregation.backfill.start-date}. Runs once on startup in
 * monthly chunks (idempotent), then can be turned off again.
 */
@Component
@ConditionalOnProperty(prefix = "analytics.aggregation.backfill", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AggregateBackfillRunner implements ApplicationRunner {

    private final AggregationService aggregationService;

    @Value("${analytics.aggregation.backfill.start-date}")
    private String startDate;

    @Override
    public void run(ApplicationArguments args) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate today = LocalDate.now();
        log.info("[aggregation-backfill] START from {} to {}", start, today);

        for (LocalDate chunkStart = start; !chunkStart.isAfter(today); chunkStart = chunkStart.plusMonths(1)) {
            LocalDate chunkEnd = chunkStart.withDayOfMonth(chunkStart.lengthOfMonth());
            if (chunkEnd.isAfter(today)) {
                chunkEnd = today;
            }
            log.info("[aggregation-backfill] chunk {}..{}", chunkStart, chunkEnd);
            aggregationService.aggregateWindow(chunkStart, chunkEnd);
        }
        log.info("[aggregation-backfill] DONE");
    }
}
