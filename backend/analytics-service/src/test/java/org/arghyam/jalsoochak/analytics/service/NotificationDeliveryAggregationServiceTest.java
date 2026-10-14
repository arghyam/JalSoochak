package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.repository.NotificationDeliveryAggregationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryAggregationServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 3);
    private static final LocalDate TO = LocalDate.of(2026, 10, 6);

    @Mock
    private NotificationDeliveryAggregationRepository repository;

    @InjectMocks
    private NotificationDeliveryAggregationService service;

    @Test
    void recompute_rebuildsBothRollupsForTheWindow() {
        when(repository.tryLock()).thenReturn(true);

        service.recompute(FROM, TO);

        InOrder order = inOrder(repository);
        order.verify(repository).tryLock();
        order.verify(repository).upsertDeliveryDaily(FROM, TO);
        order.verify(repository).deleteVanishedDeliveryDaily(FROM, TO);
        order.verify(repository).upsertFailureDaily(FROM, TO);
        order.verify(repository).deleteVanishedFailureDaily(FROM, TO);
    }

    @Test
    void recompute_whileAnotherInstanceHoldsTheLock_isSkipped() {
        when(repository.tryLock()).thenReturn(false);

        service.recompute(FROM, TO);

        verify(repository).tryLock();
        verifyNoMoreInteractions(repository);
    }
}
