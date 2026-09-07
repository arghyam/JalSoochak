package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.SchemeStatusSchedulerService;
import org.arghyam.jalsoochak.analytics.service.SchemeStatusSchedulerService.SchemeStatusSyncResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeStatusSyncTaskTest {

    @Mock
    private SchemeStatusSchedulerService schemeStatusSchedulerService;

    @InjectMocks
    private SchemeStatusSyncTask schemeStatusSyncTask;

    @Test
    void runTask_usesConfiguredInactiveDays() {
        ReflectionTestUtils.setField(schemeStatusSyncTask, "inactiveAfterDays", 30);
        when(schemeStatusSchedulerService.syncSchemeStatusBySubmissionRecency(30))
                .thenReturn(new SchemeStatusSyncResult(12, 9, 3));

        schemeStatusSyncTask.runTask();

        verify(schemeStatusSchedulerService).syncSchemeStatusBySubmissionRecency(30);
    }

    @Test
    void runTask_negativeInactiveDays_isSanitizedToZero() {
        ReflectionTestUtils.setField(schemeStatusSyncTask, "inactiveAfterDays", -5);
        when(schemeStatusSchedulerService.syncSchemeStatusBySubmissionRecency(0))
                .thenReturn(new SchemeStatusSyncResult(12, 9, 3));

        schemeStatusSyncTask.runTask();

        verify(schemeStatusSchedulerService).syncSchemeStatusBySubmissionRecency(0);
    }
}
