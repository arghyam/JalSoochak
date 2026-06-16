package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.AggregationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for the calendar-bucket computation in {@link AggregationService}
 * (Sunday->Saturday weeks, calendar months) — the off-by-one-prone Java logic.
 * SQL correctness is covered separately by the Testcontainers integration tests.
 */
@ExtendWith(MockitoExtension.class)
class AggregationServiceTest {

    @Mock
    private AggregationRepository aggregationRepository;

    @InjectMocks
    private AggregationService service;

    @Test
    void aggregateWindow_buildsDaySundayWeekAndCalendarMonthBuckets() {
        // 2026-01-01 is a Thursday; window spans into the next Sunday-week.
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 5); // Monday

        service.aggregateWindow(from, to);

        // Base scheme/day refresh runs once for the whole window.
        verify(aggregationRepository, times(1)).upsertSchemeDaily(from, to);

        ArgumentCaptor<PeriodScale> scaleCaptor = ArgumentCaptor.forClass(PeriodScale.class);
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(aggregationRepository, times(8)).upsertRegionMetrics(
                scaleCaptor.capture(), startCaptor.capture(), endCaptor.capture(), eq(true));

        List<PeriodScale> scales = scaleCaptor.getAllValues();
        // 5 DAY + 2 WEEK + 1 MONTH = 8
        assertThat(scales.stream().filter(s -> s == PeriodScale.DAY).count()).isEqualTo(5);
        assertThat(scales.stream().filter(s -> s == PeriodScale.WEEK).count()).isEqualTo(2);
        assertThat(scales.stream().filter(s -> s == PeriodScale.MONTH).count()).isEqualTo(1);

        // WEEK buckets are Sunday-aligned: Dec 28 2025 and Jan 4 2026.
        List<LocalDate> weekStarts = weekStarts(scaleCaptor.getAllValues(), startCaptor.getAllValues());
        assertThat(weekStarts).containsExactlyInAnyOrder(
                LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 4));

        // MONTH bucket is the calendar month Jan 1 -> Jan 31.
        verify(aggregationRepository, times(1))
                .upsertRegionMetrics(eq(PeriodScale.MONTH), eq(LocalDate.of(2026, 1, 1)),
                        eq(LocalDate.of(2026, 1, 31)), eq(true));

        // Distribution rollups run for the same set of buckets.
        verify(aggregationRepository, times(8)).upsertRegionDistribution(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static List<LocalDate> weekStarts(List<PeriodScale> scales, List<LocalDate> starts) {
        return java.util.stream.IntStream.range(0, scales.size())
                .filter(i -> scales.get(i) == PeriodScale.WEEK)
                .mapToObj(starts::get)
                .collect(Collectors.toList());
    }

    @Test
    void sundayOf_returnsSundayForEveryWeekday() {
        // Week of 2026-01-04 (Sunday) .. 2026-01-10 (Saturday) all map to Jan 4.
        for (int i = 0; i <= 6; i++) {
            assertThat(AggregationService.sundayOf(LocalDate.of(2026, 1, 4).plusDays(i)))
                    .isEqualTo(LocalDate.of(2026, 1, 4));
        }
    }
}
