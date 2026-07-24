package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.AggregationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Orchestrates population of the pre-aggregation tables. Computes the calendar
 * buckets (DAY, Sunday->Saturday WEEK, calendar MONTH) that a date window touches
 * and drives the idempotent UPSERTs in {@link AggregationRepository}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AggregationService {

    /** Match the scheduler zone so the final/provisional boundary is consistent regardless of JVM TZ. */
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final AggregationRepository aggregationRepository;

    /**
     * Recompute all grains (base scheme/day + DAY/WEEK/MONTH region rollups) for every
     * bucket touched by [{@code from}, {@code to}]. Safe to call repeatedly (idempotent
     * UPSERTs) — used by the nightly lookback and by the backfill runner.
     */
    @Transactional
    public void aggregateWindow(LocalDate from, LocalDate to) {
        aggregate(from, to, true);
    }

    /**
     * Intraday refresh of the base scheme/day grain and the DAY region rollups only.
     * The in-progress WEEK/MONTH buckets are deliberately NOT re-rolled here — the
     * midnight {@link #aggregateWindow} run finalizes them once the day closes. This
     * confines the hourly job's row churn (and the Postgres dead tuples it produces) to
     * the DAY grain, the only grain whose intraday value the dashboards read live; the
     * WEEK/MONTH trend series lag by at most the current day between midnight runs.
     */
    @Transactional
    public void aggregateDayGrain(LocalDate from, LocalDate to) {
        aggregate(from, to, false);
    }

    private void aggregate(LocalDate from, LocalDate to, boolean includeWeekAndMonth) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid aggregation window: " + from + " .. " + to);
        }
        LocalDate today = LocalDate.now(IST_ZONE);

        int base = aggregationRepository.upsertSchemeDaily(from, to);
        log.info("[aggregation] fact_scheme_daily_table upserted rows={} window={}..{}", base, from, to);

        // DAY buckets
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            boolean isFinal = d.isBefore(today);
            aggregationRepository.upsertRegionMetrics(PeriodScale.DAY, d, d, isFinal);
        }

        if (includeWeekAndMonth) {
            // WEEK buckets (Sunday -> Saturday) touched by the window
            for (LocalDate ws = sundayOf(from); !ws.isAfter(to); ws = ws.plusWeeks(1)) {
                LocalDate we = ws.plusDays(6);
                boolean isFinal = we.isBefore(today);
                aggregationRepository.upsertRegionMetrics(PeriodScale.WEEK, ws, we, isFinal);
            }

            // MONTH buckets touched by the window
            for (LocalDate ms = from.withDayOfMonth(1); !ms.isAfter(to); ms = ms.plusMonths(1)) {
                LocalDate me = ms.withDayOfMonth(ms.lengthOfMonth());
                boolean isFinal = me.isBefore(today);
                aggregationRepository.upsertRegionMetrics(PeriodScale.MONTH, ms, me, isFinal);
            }
        }
        log.info("[aggregation] region rollups refreshed (weekMonth={}) for window={}..{}",
                includeWeekAndMonth, from, to);
    }

    /** Aggregate reading-submission activity for a single hour. */
    @Transactional
    public void aggregateHour(LocalDateTime hourStart) {
        LocalDateTime truncated = hourStart.withMinute(0).withSecond(0).withNano(0);
        int rows = aggregationRepository.upsertSubmissionActivityHourly(truncated);
        log.info("[aggregation] hourly submission activity upserted rows={} hour={}", rows, truncated);
    }

    /** Sunday that starts the calendar week of {@code date} (Mon=1..Sun=7 -> value % 7). */
    static LocalDate sundayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() % 7);
    }
}
