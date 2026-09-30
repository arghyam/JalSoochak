package org.arghyam.jalsoochak.analytics.runner;

import org.arghyam.jalsoochak.analytics.service.AggregationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class AggregateBackfillRunnerTest {

    /** 2026-03-10 20:00 UTC = 2026-03-11 01:30 IST. */
    private static final Clock EARLY_MORNING_IST = Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneOffset.UTC);

    @Test
    void startsInTheBackground_soStartupAndReadinessAreNotHeldUp() throws Exception {
        AggregationService service = mock(AggregationService.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).backfillWindow(any(), any());
        AggregateBackfillRunner runner = runner(service, "2026-03-01");

        long started = System.nanoTime();
        runner.onApplicationReady();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // Returned while the first chunk is still blocked.
        assertThat(elapsedMs).isLessThan(1000);
        release.countDown();
        verify(service, timeout(5000)).backfillWindow(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 11));
    }

    @Test
    void backfillsMonthByMonth_throughTodayInIndia() {
        AggregationService service = mock(AggregationService.class);
        AggregateBackfillRunner runner = runner(service, "2026-01-15");

        runner.onApplicationReady();

        var order = inOrder(service);
        order.verify(service, timeout(5000)).backfillWindow(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31));
        order.verify(service, timeout(5000)).backfillWindow(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
        // The UTC date is still Mar 10; the Indian date, which reporting days use, is Mar 11.
        order.verify(service, timeout(5000)).backfillWindow(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 11));
    }

    private static AggregateBackfillRunner runner(AggregationService service, String startDate) {
        AggregateBackfillRunner runner = new AggregateBackfillRunner(service);
        ReflectionTestUtils.setField(runner, "startDate", startDate);
        ReflectionTestUtils.setField(runner, "clock", EARLY_MORNING_IST);
        return runner;
    }
}
