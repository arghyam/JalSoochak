package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.event.SchemeReadingsReassignedEvent;
import org.arghyam.jalsoochak.analytics.repository.FactIngestionRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeReassignmentRepository;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityRecalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeReassignmentServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 2);

    @Mock private SchemeReassignmentRepository repository;
    @Mock private FactIngestionRepository factIngestionRepository;
    @Mock private WaterQuantityRecalculationService recalculation;
    @Mock private AggregationRefreshQueue refreshQueue;
    @Mock private PlatformTransactionManager transactionManager;

    private SchemeReassignmentService service;

    @BeforeEach
    void setUp() {
        service = new SchemeReassignmentService(repository, factIngestionRepository, recalculation, refreshQueue,
                transactionManager);
    }

    private static SchemeReadingsReassignedEvent event(Integer from, Integer to) {
        SchemeReadingsReassignedEvent e = new SchemeReadingsReassignedEvent();
        e.setTenantId(1);
        e.setFromSchemeId(from);
        e.setToSchemeId(to);
        return e;
    }

    @Test
    void locksBothSchemesMovesFactsRecalculatesEachDayThenRefreshesTheAggregates() {
        when(repository.moveMeterReadings(1, 99, 10)).thenReturn(List.of(D1, D2));

        SchemeReassignmentService.Outcome outcome = service.reassign(event(99, 10));

        assertThat(outcome.dates()).containsExactly(D1, D2);
        InOrder order = inOrder(factIngestionRepository, repository, recalculation, refreshQueue);
        order.verify(factIngestionRepository).lockSchemes(1, Set.of(99, 10));
        order.verify(repository).moveMeterReadings(1, 99, 10);
        order.verify(recalculation).recalculateAfterRemoval(1, 99, D1);
        order.verify(recalculation).recalculateAfterReading(1, 10, D1);
        order.verify(recalculation).recalculateAfterRemoval(1, 99, D2);
        order.verify(recalculation).recalculateAfterReading(1, 10, D2);
        order.verify(repository).dropScheme(1, 99);
        order.verify(refreshQueue).refresh(any(), any(), anyString());
        verify(refreshQueue).refresh(D1, D2, "scheme 99 -> 10 tenant 1");
    }

    @Test
    void aRepeatWithNothingLeftToMoveDoesNotReaggregate() {
        when(repository.moveMeterReadings(1, 99, 10)).thenReturn(List.of());

        service.reassign(event(99, 10));

        verify(refreshQueue, never()).refresh(any(), any(), anyString());
    }

    @Test
    void ignoresAMalformedOrSelfReassignment() {
        service.reassign(event(10, 10));
        service.reassign(event(null, 10));

        verifyNoInteractions(repository, factIngestionRepository, recalculation, refreshQueue);
    }
}
