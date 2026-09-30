package org.arghyam.jalsoochak.tenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.util.List;

import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.DailySituationReportSchedulerService;
import org.arghyam.jalsoochak.tenant.service.EscalationSchedulerService;
import org.arghyam.jalsoochak.tenant.service.NudgeSchedulerService;
import org.arghyam.jalsoochak.tenant.service.TenantConfigService;
import org.arghyam.jalsoochak.tenant.service.WeeklySituationReportSchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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

    @Test
    void nudgeRunsForActiveTenantAndSkipsInactiveTenant() {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(
                tenant(1, "MP", TenantStatusEnum.ACTIVE),
                tenant(2, "UP", TenantStatusEnum.INACTIVE)));

        var response = controller.triggerNudge();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_mp", 1);
    }

    @Test
    void weeklyReportUsesTenantWeekStartDay() {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(tenant(1, "TN", TenantStatusEnum.ACTIVE)));
        when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(WeeklyReportScheduleConfig.builder()
                .dayOfWeek(4).hour(9).minute(0).weekStartDay(4).build());

        var response = controller.triggerWeeklyReport();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(weeklySituationReportSchedulerService)
                .processWeeklyReportsForTenant("tenant_tn", 1, DayOfWeek.THURSDAY);
    }

    @Test
    void tenantFailureReturnsServerErrorAfterContinuing() {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(
                tenant(1, "MP", TenantStatusEnum.ACTIVE),
                tenant(2, "UP", TenantStatusEnum.ACTIVE)));
        doThrow(new IllegalStateException("publish failed"))
                .when(nudgeSchedulerService).processNudgesForTenant("tenant_mp", 1);

        var response = controller.triggerNudge();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(nudgeSchedulerService).processNudgesForTenant("tenant_up", 2);
    }

    private TenantResponseDTO tenant(int id, String stateCode, TenantStatusEnum status) {
        return TenantResponseDTO.builder().id(id).stateCode(stateCode).status(status.name()).build();
    }
}