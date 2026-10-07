package org.arghyam.jalsoochak.analytics.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AggregationRefreshQueueTest {

    @Test
    void reaggregatesALongRangeInChunks() {
        AggregationService aggregation = mock(AggregationService.class);

        new AggregationRefreshQueue(aggregation).run(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 15), "test");

        verify(aggregation).backfillWindow(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        verify(aggregation).backfillWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15));
        verifyNoMoreInteractions(aggregation);
    }

    @Test
    void stopsAtTheFirstFailedChunk() {
        AggregationService aggregation = mock(AggregationService.class);
        doThrow(new IllegalStateException("db down"))
                .when(aggregation).backfillWindow(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        new AggregationRefreshQueue(aggregation).run(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 15), "test");

        verify(aggregation).backfillWindow(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        verifyNoMoreInteractions(aggregation);
    }
}
