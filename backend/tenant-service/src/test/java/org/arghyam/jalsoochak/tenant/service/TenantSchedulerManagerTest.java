package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.DailyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TenantSchedulerManager} scheduling logic.
 */
@ExtendWith(MockitoExtension.class)
class TenantSchedulerManagerTest {

    @Mock
    private ThreadPoolTaskScheduler taskScheduler;

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private TenantConfigService tenantConfigService;

    @Mock
    private NudgeSchedulerService nudgeSchedulerService;

    @Mock
    private EscalationSchedulerService escalationSchedulerService;

    @Mock
    private DailySituationReportSchedulerService dailySituationReportSchedulerService;

    @InjectMocks
    private TenantSchedulerManager manager;

    @SuppressWarnings("rawtypes")
    @Mock
    private ScheduledFuture future;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(future);
    }

    // ── loadAndScheduleAll ──────────────────────────────────────────────────────

    @Test
    void loadAndScheduleAll_schedulesNudgeEscalationAndDailyReport_forEachActiveTenant() {
        TenantResponseDTO t1 = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        TenantResponseDTO t2 = TenantResponseDTO.builder().id(2).stateCode("UP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t1, t2));

        stubConfigs(1, 8, 0, 9, 0);
        stubConfigs(2, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        // per tenant: nudge + escalation + dailyReport = 3; 2 tenants = 6 schedule calls
        verify(taskScheduler, times(6)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_skipsInactiveTenants() {
        TenantResponseDTO active = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        TenantResponseDTO inactive = TenantResponseDTO.builder().id(2).stateCode("UP").status(TenantStatusEnum.INACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(active, inactive));

        stubConfigs(1, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        // Only 3 calls for the active tenant (nudge + escalation + dailyReport)
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_skipsRegisteredTenants() {
        TenantResponseDTO active = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        TenantResponseDTO registered = TenantResponseDTO.builder().id(2).stateCode("UP").status(TenantStatusEnum.REGISTERED.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(active, registered));

        stubConfigs(1, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        // Only 3 calls for the active tenant (nudge + escalation + dailyReport); the pre-seeded REGISTERED tenant (no schema) is excluded
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_skipsNullStatusTenants() {
        TenantResponseDTO active = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        TenantResponseDTO nullStatus = TenantResponseDTO.builder().id(2).stateCode("UP").status(null).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(active, nullStatus));

        stubConfigs(1, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        // Only 3 calls for the active tenant (nudge + escalation + dailyReport); null-status tenant is excluded
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_buildsCronExpression_fromConfigHourAndMinute() {
        TenantResponseDTO t1 = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t1));

        stubConfigs(1, 10, 30, 11, 15);

        manager.loadAndScheduleAll();

        ArgumentCaptor<CronTrigger> triggerCaptor = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), triggerCaptor.capture());

        List<CronTrigger> triggers = triggerCaptor.getAllValues();
        // nudge cron: 0 30 10 * * ?
        assertThat(triggers.get(0).toString()).contains("0 30 10");
        // escalation cron: 0 15 11 * * ?
        assertThat(triggers.get(1).toString()).contains("0 15 11");
    }

    // ── rescheduleForTenant ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleForTenant_cancelsOldFuturesBeforeSchedulingNew() {
        // First schedule
        TenantResponseDTO t1 = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t1));
        stubConfigs(1, 8, 0, 9, 0);
        manager.loadAndScheduleAll();

        // Now reschedule with new config
        stubConfigs(1, 10, 30, 11, 0);
        manager.rescheduleForTenant(1, "MP");

        // Old futures should have been cancelled (3 from initial schedule)
        verify(future, times(3)).cancel(false);
        // And 3 new futures scheduled (total 6 schedule calls: 3 initial + 3 new)
        verify(taskScheduler, times(6)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void rescheduleForTenant_doesNotCancelNonExistentFutures_whenNoneScheduled() {
        // reschedule without prior loadAndScheduleAll
        stubConfigs(99, 8, 0, 9, 0);

        manager.rescheduleForTenant(99, "GJ");

        // No cancel calls – no existing futures
        verify(future, never()).cancel(anyBoolean());
        // But 3 new futures should be scheduled
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    // ── isolation / security tests ───────────────────────────────────────────────

    @Test
    void loadAndScheduleAll_nudgeTask_boundToTenantOwnSchema() {
        TenantResponseDTO t = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t));
        stubConfigs(1, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(3)).schedule(runnableCaptor.capture(), any(CronTrigger.class));

        runnableCaptor.getAllValues().get(0).run(); // nudge runnable

        verify(nudgeSchedulerService).processNudgesForTenant("tenant_mp", 1);
        verifyNoInteractions(escalationSchedulerService);
    }

    @Test
    void loadAndScheduleAll_escalationTask_boundToTenantOwnSchema() {
        TenantResponseDTO t = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t));
        stubConfigs(1, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(3)).schedule(runnableCaptor.capture(), any(CronTrigger.class));

        runnableCaptor.getAllValues().get(1).run(); // escalation runnable

        verify(escalationSchedulerService).processEscalationsForTenant("tenant_mp", 1);
        verifyNoInteractions(nudgeSchedulerService);
    }

    @Test
    void loadAndScheduleAll_twoTenants_eachTaskBoundToItsOwnSchema() {
        TenantResponseDTO mp = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        TenantResponseDTO up = TenantResponseDTO.builder().id(2).stateCode("UP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(mp, up));
        stubConfigs(1, 8, 0, 9, 0);
        stubConfigs(2, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(6)).schedule(runnableCaptor.capture(), any(CronTrigger.class));

        List<Runnable> runnables = runnableCaptor.getAllValues();
        runnables.get(0).run(); // nudge for MP
        runnables.get(3).run(); // nudge for UP

        verify(nudgeSchedulerService).processNudgesForTenant("tenant_mp", 1);
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_up", 2);
        // Each tenant's task must not invoke the other tenant's schema
        verify(nudgeSchedulerService, never()).processNudgesForTenant("tenant_mp", 2);
        verify(nudgeSchedulerService, never()).processNudgesForTenant("tenant_up", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleForTenant_invalidConfig_doesNotCancelExistingFutures() {
        TenantResponseDTO t = TenantResponseDTO.builder().id(1).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t));
        stubConfigs(1, 8, 0, 9, 0);
        manager.loadAndScheduleAll();

        // Provide an out-of-range hour so validation throws before any cancel
        when(tenantConfigService.getNudgeConfig(1))
                .thenReturn(NudgeScheduleConfig.builder().hour(25).minute(0).build());

        assertThatThrownBy(() -> manager.rescheduleForTenant(1, "MP"))
                .isInstanceOf(IllegalArgumentException.class);

        // The two original futures must still be alive — cancel must not have been called
        verify(future, never()).cancel(anyBoolean());
    }

    @Test
    void loadAndScheduleAll_skipsTenant_whenTenantIdIsNull() {
        TenantResponseDTO nullId = TenantResponseDTO.builder().id(null).stateCode("MP")
                .status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(nullId));

        manager.loadAndScheduleAll();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_skipsTenant_whenStateCodeIsNull() {
        TenantResponseDTO nullState = TenantResponseDTO.builder().id(1).stateCode(null)
                .status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(nullState));

        manager.loadAndScheduleAll();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_skipsTenant_whenStateCodeIsBlank() {
        TenantResponseDTO blankState = TenantResponseDTO.builder().id(1).stateCode("  ")
                .status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(blankState));

        manager.loadAndScheduleAll();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_continuesForRemainingTenants_whenOneScheduleFails() {
        TenantResponseDTO bad = TenantResponseDTO.builder().id(1).stateCode("MP")
                .status(TenantStatusEnum.ACTIVE.name()).build();
        TenantResponseDTO good = TenantResponseDTO.builder().id(2).stateCode("UP")
                .status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(bad, good));

        when(tenantConfigService.getNudgeConfig(1))
                .thenThrow(new RuntimeException("Config unavailable for tenant 1"));
        stubConfigs(2, 8, 0, 9, 0);

        manager.loadAndScheduleAll();

        // good tenant still scheduled (3 jobs)
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void rescheduleForTenant_throwsIllegalArgument_whenEscalationConfigHourInvalid() {
        TenantResponseDTO t = TenantResponseDTO.builder().id(1).stateCode("MP")
                .status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t));
        stubConfigs(1, 8, 0, 9, 0);
        manager.loadAndScheduleAll();

        // EscalationScheduleConfig.builder() validates at build() time so use a mock.
        // validateScheduleConfig checks hour first and short-circuits, so only stub getHour().
        EscalationScheduleConfig invalidEscalCfg = mock(EscalationScheduleConfig.class);
        when(invalidEscalCfg.getHour()).thenReturn(25);

        when(tenantConfigService.getNudgeConfig(1))
                .thenReturn(NudgeScheduleConfig.builder().hour(8).minute(0).build());
        when(tenantConfigService.getEscalationConfig(1)).thenReturn(invalidEscalCfg);

        assertThatThrownBy(() -> manager.rescheduleForTenant(1, "MP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid escalation schedule");
    }

    @Test
    void loadAndScheduleAll_skipsSuspendedTenants() {
        TenantResponseDTO suspended = TenantResponseDTO.builder().id(1).stateCode("MP")
                .status(TenantStatusEnum.SUSPENDED.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(suspended));

        manager.loadAndScheduleAll();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void loadAndScheduleAll_skipsArchivedTenants() {
        TenantResponseDTO archived = TenantResponseDTO.builder().id(1).stateCode("MP")
                .status(TenantStatusEnum.ARCHIVED.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(archived));

        manager.loadAndScheduleAll();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void stubConfigs(int tenantId, int nudgeHour, int nudgeMin, int escalHour, int escalMin) {
        when(tenantConfigService.getNudgeConfig(tenantId))
                .thenReturn(NudgeScheduleConfig.builder().hour(nudgeHour).minute(nudgeMin).build());
        when(tenantConfigService.getEscalationConfig(tenantId))
                .thenReturn(EscalationScheduleConfig.builder()
                        .hour(escalHour).minute(escalMin)
                        .level1Days(3).level1OfficerType("SECTION_OFFICER")
                        .level2Days(7).level2OfficerType("DISTRICT_OFFICER")
                        .build());
        when(tenantConfigService.getDailyReportConfig(tenantId))
                .thenReturn(DailyReportScheduleConfig.builder().hour(6).minute(0).build());
    }
}
