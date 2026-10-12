package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DimDateBackfillTaskTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private DateDimensionService dateDimensionService;

    @InjectMocks
    private DimDateBackfillTask dimDateBackfillTask;

    @Test
    void runTask_backfillsPreviousDayInIst() {
        LocalDate expectedTargetDate = LocalDate.now(IST_ZONE).minusDays(1);

        dimDateBackfillTask.runTask();

        verify(dateDimensionService).ensureDateExists(expectedTargetDate);
    }

    @Test
    void runTask_completesWhenAnotherInstanceCreatedTheDateFirst() {
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(dateDimensionService).ensureDateExists(any(LocalDate.class));

        assertThatCode(() -> dimDateBackfillTask.runTask()).doesNotThrowAnyException();
    }

    @Test
    void runTask_propagatesOtherFailures() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("connection refused");
        doThrow(failure).when(dateDimensionService).ensureDateExists(any(LocalDate.class));

        assertThatThrownBy(() -> dimDateBackfillTask.runTask()).isSameAs(failure);
    }
}
