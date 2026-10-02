package org.arghyam.jalsoochak.tenant.controller;

import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.DailySituationReportSchedulerService;
import org.arghyam.jalsoochak.tenant.service.EscalationSchedulerService;
import org.arghyam.jalsoochak.tenant.service.NudgeSchedulerService;
import org.arghyam.jalsoochak.tenant.service.TenantConfigService;
import org.arghyam.jalsoochak.tenant.service.WeeklySituationReportSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.DayOfWeek;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JobTriggerController}. Verifies that each trigger endpoint
 * correctly iterates active tenants, skips ineligible ones, and delegates to the
 * right scheduler-service method.
 */
@ExtendWith(MockitoExtension.class)
class JobTriggerControllerTest {

    @Mock private TenantCommonRepository tenantCommonRepository;
    @Mock private TenantConfigService tenantConfigService;
    @Mock private NudgeSchedulerService nudgeSchedulerService;
    @Mock private EscalationSchedulerService escalationSchedulerService;
    @Mock private DailySituationReportSchedulerService dailySituationReportSchedulerService;
    @Mock private WeeklySituationReportSchedulerService weeklySituationReportSchedulerService;

    @InjectMocks
    private JobTriggerController controller;

    private TenantResponseDTO activeTenant(int id, String stateCode) {
        return TenantResponseDTO.builder().id(id).stateCode(stateCode)
                .status(TenantStatusEnum.ACTIVE.name()).build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(tenantConfigService.getWeeklyReportConfig(anyInt()))
                .thenReturn(WeeklyReportScheduleConfig.builder()
                        .dayOfWeek(1).hour(9).minute(0).weekStartDay(1).build());
    }

    // ── nudge ─────────────────────────────────────────────────────────────────

    @Test
    void triggerNudge_callsServiceForEachActiveTenant() {
        TenantResponseDTO t1 = activeTenant(1, "MP");
        TenantResponseDTO t2 = activeTenant(2, "UP");
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t1, t2));

        ResponseEntity<String> resp = controller.triggerNudge();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_mp", 1);
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_up", 2);
        verifyNoMoreInteractions(nudgeSchedulerService);
    }

    @Test
    void triggerNudge_skipsInactiveTenants() {
        TenantResponseDTO active = activeTenant(1, "MP");
        TenantResponseDTO inactive = TenantResponseDTO.builder().id(2).stateCode("UP")
                .status(TenantStatusEnum.INACTIVE.name()).build();
        TenantResponseDTO registered = TenantResponseDTO.builder().id(3).stateCode("RJ")
                .status(TenantStatusEnum.REGISTERED.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(active, inactive, registered));

        controller.triggerNudge();

        verify(nudgeSchedulerService, times(1)).processNudgesForTenant(anyString(), anyInt());
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_mp", 1);
    }

    @Test
    void triggerNudge_skipsTenantWithNullStateCode() {
        TenantResponseDTO bad = TenantResponseDTO.builder().id(1).stateCode(null)
                .status(TenantStatusEnum.ACTIVE.name()).build();
        when(tenantCommonRepository.findAll()).thenReturn(List.of(bad));

        controller.triggerNudge();

        verifyNoInteractions(nudgeSchedulerService);
    }

    @Test
    void triggerNudge_continuesAfterPerTenantFailure() {
        TenantResponseDTO t1 = activeTenant(1, "MP");
        TenantResponseDTO t2 = activeTenant(2, "UP");
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t1, t2));
        doThrow(new RuntimeException("db error")).when(nudgeSchedulerService)
                .processNudgesForTenant(eq("tenant_mp"), eq(1));

        // Should not throw; t2 must still be processed
        ResponseEntity<String> resp = controller.triggerNudge();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_up", 2);
    }

    // ── escalation ────────────────────────────────────────────────────────────

    @Test
    void triggerEscalation_callsServiceForEachActiveTenant() {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(activeTenant(1, "MP")));

        controller.triggerEscalation();

        verify(escalationSchedulerService).processEscalationsForTenant("tenant_mp", 1);
    }

    // ── daily report ──────────────────────────────────────────────────────────

    @Test
    void triggerDailyReport_callsServiceForEachActiveTenant() {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(activeTenant(1, "MP")));

        controller.triggerDailyReport();

        verify(dailySituationReportSchedulerService).processDailyReportsForTenant("tenant_mp", 1);
    }

    // ── weekly report ─────────────────────────────────────────────────────────

    @Test
    void triggerWeeklyReport_resolvesWeekStartDayPerTenant() {
        TenantResponseDTO t1 = activeTenant(1, "MP");
        TenantResponseDTO t2 = activeTenant(2, "UP");
        when(tenantCommonRepository.findAll()).thenReturn(List.of(t1, t2));
        when(tenantConfigService.getWeeklyReportConfig(1))
                .thenReturn(WeeklyReportScheduleConfig.builder()
                        .dayOfWeek(1).hour(9).minute(0).weekStartDay(1).build()); // Monday
        when(tenantConfigService.getWeeklyReportConfig(2))
                .thenReturn(WeeklyReportScheduleConfig.builder()
                        .dayOfWeek(4).hour(9).minute(0).weekStartDay(4).build()); // Thursday

        controller.triggerWeeklyReport();

        verify(weeklySituationReportSchedulerService)
                .processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.MONDAY);
        verify(weeklySituationReportSchedulerService)
                .processWeeklyReportsForTenant("tenant_up", 2, DayOfWeek.THURSDAY);
    }

    @Test
    void triggerWeeklyReport_stateCodeIsLowercasedInSchema() {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(activeTenant(1, "MH")));

        controller.triggerWeeklyReport();

        verify(weeklySituationReportSchedulerService)
                .processWeeklyReportsForTenant(eq("tenant_mh"), eq(1), any(DayOfWeek.class));
    }
}
