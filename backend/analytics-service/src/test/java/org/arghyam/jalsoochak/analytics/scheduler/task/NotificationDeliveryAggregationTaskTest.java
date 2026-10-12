package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.NotificationDeliveryAggregationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryAggregationTaskTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private NotificationDeliveryAggregationService aggregationService;

    @InjectMocks
    private NotificationDeliveryAggregationTask task;

    @Test
    void runTask_recomputesTheLookbackWindowEndingIstToday() {
        ReflectionTestUtils.setField(task, "lookbackDays", 3);
        LocalDate todayIst = LocalDate.now(IST_ZONE);

        task.runTask();

        verify(aggregationService).recompute(todayIst.minusDays(3), todayIst);
    }

    @Test
    void runTask_negativeLookback_recomputesTodayOnly() {
        ReflectionTestUtils.setField(task, "lookbackDays", -1);
        LocalDate todayIst = LocalDate.now(IST_ZONE);

        task.runTask();

        verify(aggregationService).recompute(todayIst, todayIst);
    }
}
