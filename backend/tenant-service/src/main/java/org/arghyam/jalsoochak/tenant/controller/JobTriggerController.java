package org.arghyam.jalsoochak.tenant.controller;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Locale;

import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.DailySituationReportSchedulerService;
import org.arghyam.jalsoochak.tenant.service.EscalationSchedulerService;
import org.arghyam.jalsoochak.tenant.service.NudgeSchedulerService;
import org.arghyam.jalsoochak.tenant.service.TenantConfigService;
import org.arghyam.jalsoochak.tenant.service.WeeklySituationReportSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/jobs")
@RequiredArgsConstructor
@Slf4j
public class JobTriggerController {

    private final TenantCommonRepository tenantCommonRepository;
    private final TenantConfigService tenantConfigService;
    private final NudgeSchedulerService nudgeSchedulerService;
    private final EscalationSchedulerService escalationSchedulerService;
    private final DailySituationReportSchedulerService dailySituationReportSchedulerService;
    private final WeeklySituationReportSchedulerService weeklySituationReportSchedulerService;

    @PostMapping("/nudge")
    public ResponseEntity<String> triggerNudge() {
        return response("nudge", forEachActiveTenant((tenantId, schema) ->
                nudgeSchedulerService.processNudgesForTenant(schema, tenantId)));
    }

    @PostMapping("/escalation")
    public ResponseEntity<String> triggerEscalation() {
        return response("escalation", forEachActiveTenant((tenantId, schema) ->
                escalationSchedulerService.processEscalationsForTenant(schema, tenantId)));
    }

    @PostMapping("/daily-report")
    public ResponseEntity<String> triggerDailyReport() {
        return response("daily-report", forEachActiveTenant((tenantId, schema) ->
                dailySituationReportSchedulerService.processDailyReportsForTenant(schema, tenantId)));
    }

    @PostMapping("/weekly-report")
    public ResponseEntity<String> triggerWeeklyReport() {
        return response("weekly-report", forEachActiveTenant((tenantId, schema) -> {
            WeeklyReportScheduleConfig config = tenantConfigService.getWeeklyReportConfig(tenantId);
            DayOfWeek weekStartDay = config.getWeekStartDayOfWeek();
            weeklySituationReportSchedulerService.processWeeklyReportsForTenant(schema, tenantId, weekStartDay);
        }));
    }

    private ResponseEntity<String> response(String job, ExecutionSummary summary) {
        HttpStatus status = summary.failed() == 0 ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(job + " attempted for " + summary.attempted()
                + " tenant(s); " + summary.failed() + " failed");
    }

    private ExecutionSummary forEachActiveTenant(TenantAction action) {
        List<TenantResponseDTO> tenants = tenantCommonRepository.findAll();
        int attempted = 0;
        int failed = 0;
        for (TenantResponseDTO tenant : tenants) {
            String status = tenant.getStatus();
            boolean schedulableStatus = status != null
                    && !TenantStatusEnum.INACTIVE.name().equals(status)
                    && !TenantStatusEnum.SUSPENDED.name().equals(status)
                    && !TenantStatusEnum.ARCHIVED.name().equals(status)
                    && !TenantStatusEnum.REGISTERED.name().equals(status);
            Integer tenantId = tenant.getId();
            String stateCode = tenant.getStateCode();
            boolean invalidMetadata = tenantId == null || stateCode == null || stateCode.isBlank();
            if (!schedulableStatus || invalidMetadata) {
                if (schedulableStatus && invalidMetadata) {
                    log.warn("[JobTrigger] Skipping tenant with invalid metadata: id={}, stateCode={}",
                            tenantId, stateCode);
                }
                continue;
            }

            String schema = "tenant_" + stateCode.toLowerCase(Locale.ROOT);
            attempted++;
            try {
                action.run(tenantId, schema);
            } catch (Exception exception) {
                failed++;
                log.error("[JobTrigger] Job failed for tenant={} schema={}: {}",
                        tenantId, schema, exception.getMessage(), exception);
            }
        }
        return new ExecutionSummary(attempted, failed);
    }

    private record ExecutionSummary(int attempted, int failed) {}

    @FunctionalInterface
    private interface TenantAction {
        void run(int tenantId, String schema);
    }
}