package org.arghyam.jalsoochak.analytics.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Re-aggregates a past date range in the background, for a change that rewrote facts on dates the
 * nightly lookback ({@code analytics.aggregation.daily.lookback-days}) will not reach again.
 *
 * <p>Off the Kafka consumer thread on purpose: a window of several weeks can outlast the consumer's
 * poll interval. Each chunk is a {@link AggregationService#backfillWindow} call, which waits for the
 * aggregation lock rather than skipping. A pod that dies mid-queue loses the rest; the WARN below names
 * the range, and {@code ANALYTICS_AGG_BACKFILL_*} re-runs it.
 */
@Component
@Slf4j
public class AggregationRefreshQueue {

    static final int CHUNK_DAYS = 31;

    private final AggregationService aggregationService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aggregation-refresh");
        t.setDaemon(true);
        return t;
    });

    public AggregationRefreshQueue(AggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    /** Queues {@code from..to} (both included) in {@value #CHUNK_DAYS}-day chunks, oldest first. */
    public void refresh(LocalDate from, LocalDate to, String reason) {
        executor.submit(() -> run(from, to, reason));
    }

    void run(LocalDate from, LocalDate to, String reason) {
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(CHUNK_DAYS)) {
            LocalDate end = start.plusDays(CHUNK_DAYS - 1L).isAfter(to) ? to : start.plusDays(CHUNK_DAYS - 1L);
            try {
                aggregationService.backfillWindow(start, end);
                log.info("[aggregation-refresh] re-aggregated {}..{} ({})", start, end, reason);
            } catch (RuntimeException e) {
                log.warn("[aggregation-refresh] failed for {}..{} ({}); re-run it with the aggregation backfill: {}",
                        start, to, reason, e.getMessage());
                return;
            }
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
