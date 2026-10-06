package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.SchemePerformanceSchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemePerformanceScoreTaskTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private SchemePerformanceSchedulerService schemePerformanceSchedulerService;

    @InjectMocks
    private SchemePerformanceScoreTask schemePerformanceScoreTask;

    @Test
    void runTask_insertsSchemePerformanceForIstYesterday() {
        LocalDate yesterdayIst = LocalDate.now(IST_ZONE).minusDays(1);
        when(schemePerformanceSchedulerService.insertDailySchemePerformanceScores(yesterdayIst)).thenReturn(10);

        schemePerformanceScoreTask.runTask();

        verify(schemePerformanceSchedulerService).insertDailySchemePerformanceScores(yesterdayIst);
    }
}
