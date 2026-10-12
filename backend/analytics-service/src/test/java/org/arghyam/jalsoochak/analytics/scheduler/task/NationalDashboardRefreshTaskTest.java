package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.scheduler.ScheduledTaskClaim;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NationalDashboardRefreshTaskTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private SchemeRegularityService schemeRegularityService;
    @Mock
    private ScheduledTaskClaim scheduledTaskClaim;

    @InjectMocks
    private NationalDashboardRefreshTask nationalDashboardRefreshTask;

    @Test
    void runTask_refreshesNationalDashboardForConfiguredWindow() {
        when(scheduledTaskClaim.claimToday("national-dashboard-refresh")).thenReturn(true);
        ReflectionTestUtils.setField(nationalDashboardRefreshTask, "lookbackDays", 29);
        // Task anchors the end date to "yesterday" (IST) to keep the window stable until next run.
        LocalDate expectedEndDate = LocalDate.now(IST_ZONE).minusDays(1);
        // For lookbackDays=N, window is inclusive: [end-(N-1), end]
        LocalDate expectedStartDate = expectedEndDate.minusDays(29 - 1L);

        nationalDashboardRefreshTask.runTask();

        verify(schemeRegularityService).refreshNationalDashboard(expectedStartDate, expectedEndDate);
        verify(schemeRegularityService).getNationalDashboardLevel2MetricsForApi(expectedStartDate, expectedEndDate);
    }

    @Test
    void runTask_negativeLookbackDays_usesSingleDayWindow() {
        when(scheduledTaskClaim.claimToday("national-dashboard-refresh")).thenReturn(true);
        ReflectionTestUtils.setField(nationalDashboardRefreshTask, "lookbackDays", -7);
        // Negative lookback days are sanitized to 0, which becomes a single-day window anchored to yesterday.
        LocalDate expectedEndDate = LocalDate.now(IST_ZONE).minusDays(1);

        nationalDashboardRefreshTask.runTask();

        verify(schemeRegularityService).refreshNationalDashboard(expectedEndDate, expectedEndDate);
        verify(schemeRegularityService).getNationalDashboardLevel2MetricsForApi(expectedEndDate, expectedEndDate);
    }

    @Test
    void runTask_skipsWhenAnotherInstanceClaimedTheRun() {
        when(scheduledTaskClaim.claimToday("national-dashboard-refresh")).thenReturn(false);

        nationalDashboardRefreshTask.runTask();

        verifyNoInteractions(schemeRegularityService);
    }
}
