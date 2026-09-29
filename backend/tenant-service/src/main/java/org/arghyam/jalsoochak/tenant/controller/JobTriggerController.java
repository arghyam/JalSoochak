package org.arghyam.jalsoochak.tenant.controller;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Locale;

import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.DailySituationReportSchedulerService;
import org.arghyam.jalsoochak.tenant.service.EscalationSchedulerService;
import org.arghyam.jalsoochak.tenant.service.NudgeSchedulerService;
import org.arghyam.jalsoochak.tenant.service.TenantConfigService;
import org.arghyam.jalsoochak.tenant.service.WeeklySituationReportSchedulerService;
import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal HTTP trigger endpoints consumed exclusively by K8s CronJob pods.
 *
 * <p>Each endpoint iterates all active tenants and delegates to the same
 * scheduler-service methods that the (now removed) {@code TenantSchedulerManager}
 * used to call on a timer. The K8s CronJob owns the <em>when</em>; this controller
 * owns the <em>what</em> and <em>who</em> (all tenants).</p>
 *
 * <p>Security: all paths under {@code /internal/**} require a valid
 * {@code X-Internal-Token} header, enforced by
 * {@link org.arghyam.jalsoochak.tenant.config.InternalJobSecurityFilter}.
 * The endpoint is not reachable from outside the cluster.</p>
 */
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

    // ─────────────────────────── nudge ───────────────────────────────────────

    /**
     * Triggers the nudge job for every active tenant.
     * Called by the {@code tenant-service-nudge} K8s CronJob.
     */
    @PostMapping("/nudge")
    public ResponseEntity<String> triggerNudge() {
        log.info("[JobTrigger] nudge: starting across all tenants");
        int count = forEachActiveTenant((tenantId, schema) ->
                nudgeSchedulerService.processNudgesForTenant(schema, tenantId));
        log.info("[JobTrigger] nudge: finished — {} tenant(s) processed", count);
        return ResponseEntity.ok("nudge triggered for " + count + " tenant(s)");
    }

    // ──────────────────────── escalation ─────────────────────────────────────

    /**
     * Triggers the escalation job for every active tenant.
     * Called by the {@code tenant-service-escalation} K8s CronJob.
     */
    @PostMapping("/escalation")
    public ResponseEntity<String> triggerEscalation() {
        log.info("[JobTrigger] escalation: starting across all tenants");
        int count = forEachActiveTenant((tenantId, schema) ->
                escalationSchedulerService.processEscalationsForTenant(schema, tenantId));
        log.info("[JobTrigger] escalation: finished — {} tenant(s) processed", count);
        return ResponseEntity.ok("escalation triggered for " + count + " tenant(s)");
    }

    // ──────────────────────── daily report ───────────────────────────────────

    /**
     * Triggers the daily situation report for every active tenant.
     * Called by the {@code tenant-service-daily-report} K8s CronJob.
     */
    @PostMapping("/daily-report")
    public ResponseEntity<String> triggerDailyReport() {
        log.info("[JobTrigger] daily-report: starting across all tenants");
        int count = forEachActiveTenant((tenantId, schema) ->
                dailySituationReportSchedulerService.processDailyReportsForTenant(schema, tenantId));
        log.info("[JobTrigger] daily-report: finished — {} tenant(s) processed", count);
        return ResponseEntity.ok("daily-report triggered for " + count + " tenant(s)");
    }

    // ──────────────────────── weekly report ──────────────────────────────────

    /**
     * Triggers the weekly situation report for every active tenant.
     * Called by the {@code tenant-service-weekly-report} K8s CronJob.
     *
     * <p>The week-start day is resolved per-tenant from
     * {@code tenant_config_master_table} (falling back to the application.yml
     * default), exactly as the old {@code TenantSchedulerManager} did.</p>
     */
    @PostMapping("/weekly-report")
    public ResponseEntity<String> triggerWeeklyReport() {
        log.info("[JobTrigger] weekly-report: starting across all tenants");
        int count = forEachActiveTenant((tenantId, schema) -> {
            WeeklyReportScheduleConfig cfg = tenantConfigService.getWeeklyReportConfig(tenantId);
            DayOfWeek weekStartDay = cfg.getWeekStartDayOfWeek();
            weeklySituationReportSchedulerService.processWeeklyReportsForTenant(
                    schema, tenantId, weekStartDay);
        });
        log.info("[JobTrigger] weekly-report: finished — {} tenant(s) processed", count);
        return ResponseEntity.ok("weekly-report triggered for " + count + " tenant(s)");
    }

    // ──────────────────────── helpers ────────────────────────────────────────

    /**
     * Iterates all schedulable tenants (non-null id/stateCode, not INACTIVE/SUSPENDED/ARCHIVED/REGISTERED)
     * and calls {@code action} for each one, catching and logging per-tenant failures so that one bad
     * tenant cannot block the remaining ones.
     *
     * @return the number of tenants for which the action was attempted (including those that failed)
     */
    private int forEachActiveTenant(TenantAction action) {
        List<TenantResponseDTO> tenants = tenantCommonRepository.findAll();
        int count = 0;
        for (TenantResponseDTO t : tenants) {
            String status = t.getStatus();
            if (status == null
                    || TenantStatusEnum.INACTIVE.name().equals(status)
                    || TenantStatusEnum.SUSPENDED.name().equals(status)
                    || TenantStatusEnum.ARCHIVED.name().equals(status)
                    || TenantStatusEnum.REGISTERED.name().equals(status)) {
                continue;
            }
            Integer tenantId = t.getId();
            String stateCode = t.getStateCode();
            if (tenantId == null || stateCode == null || stateCode.isBlank()) {
                log.warn("[JobTrigger] Skipping tenant with invalid metadata: id={}, stateCode={}", tenantId, stateCode);
                continue;
            }
            String schema = "tenant_" + stateCode.toLowerCase(Locale.ROOT);
            try {
                action.run(tenantId, schema);
                count++;
            } catch (Exception e) {
                log.error("[JobTrigger] Job failed for tenant={} schema={}: {}", tenantId, schema, e.getMessage(), e);
                count++; // still counts as attempted
            }
        }
        return count;
    }

    @FunctionalInterface
    private interface TenantAction {
        void run(int tenantId, String schema);
    }
}
