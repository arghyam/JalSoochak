package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.DailyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages per-tenant scheduled jobs for nudges and escalations.
 *
 * <p>At startup, reads all active tenants and schedules a nudge job and an
 * escalation job for each, using the cron times from
 * {@code common_schema.tenant_config_master_table}. Missing config rows fall
 * back to the application.yml defaults.</p>
 *
 * <p>Call {@link #rescheduleForTenant(int, String)} after a tenant's config
 * is updated (write side handled by another engineer) to apply new cron times
 * immediately without a service restart.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantSchedulerManager {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final TenantCommonRepository tenantCommonRepository;
    private final TenantConfigService tenantConfigService;
    private final NudgeSchedulerService nudgeSchedulerService;
    private final EscalationSchedulerService escalationSchedulerService;
    private final DailySituationReportSchedulerService dailySituationReportSchedulerService;

    private static final String DAILY_CRON_FORMAT = "0 %d %d * * ?";
    private static final String IST_ZONE = "Asia/Kolkata";

    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> tenantLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAndScheduleAll() {
        List<TenantResponseDTO> tenants = tenantCommonRepository.findAll();
        tenants.stream()
            .filter(t -> {
                String s = t.getStatus();
                if (s == null) return false;
                // REGISTERED tenants are pre-seeded with no schema — never schedule jobs for them.
                return !TenantStatusEnum.INACTIVE.name().equals(s)
                    && !TenantStatusEnum.SUSPENDED.name().equals(s)
                    && !TenantStatusEnum.ARCHIVED.name().equals(s)
                    && !TenantStatusEnum.REGISTERED.name().equals(s);
            })
            .forEach(t -> {
                Integer tenantId = t.getId();
                String stateCode = t.getStateCode();
                if (tenantId == null || stateCode == null || stateCode.isBlank()) {
                        log.warn("[Scheduler] Skipping tenant with invalid metadata: id={}, stateCode={}", tenantId, stateCode);
                        return;
                    }
                try {
                        scheduleForTenant(tenantId, stateCode);
                    } catch (Exception ex) {
                        log.error("[Scheduler] Failed to schedule tenant={} stateCode={}", tenantId, stateCode, ex);
                    }
                });
    }

    /**
     * Called after a tenant's config is persisted to reschedule its jobs with the new cron times.
     * Validates the new config before cancelling existing futures so that a bad config value
     * cannot leave a tenant with no scheduled jobs.
     */
    public void rescheduleForTenant(int tenantId, String stateCode) {
        Object lock = tenantLocks.computeIfAbsent(tenantId, k -> new Object());
        synchronized (lock) {
            // Validate first — throws before touching existing futures if config is invalid.
            NudgeScheduleConfig nudgeCfg = tenantConfigService.getNudgeConfig(tenantId);
            EscalationScheduleConfig escalCfg = tenantConfigService.getEscalationConfig(tenantId);
            DailyReportScheduleConfig dailyCfg = tenantConfigService.getDailyReportConfig(tenantId);
            validateScheduleConfig(nudgeCfg, escalCfg, dailyCfg, tenantId);

            cancelFutures(tenantId);
            scheduleForTenant(tenantId, stateCode, nudgeCfg, escalCfg, dailyCfg);
        }
    }

    private void scheduleForTenant(int tenantId, String stateCode) {
        NudgeScheduleConfig nudgeCfg = tenantConfigService.getNudgeConfig(tenantId);
        EscalationScheduleConfig escalCfg = tenantConfigService.getEscalationConfig(tenantId);
        DailyReportScheduleConfig dailyCfg = tenantConfigService.getDailyReportConfig(tenantId);
        validateScheduleConfig(nudgeCfg, escalCfg, dailyCfg, tenantId);
        scheduleForTenant(tenantId, stateCode, nudgeCfg, escalCfg, dailyCfg);
    }

    private void scheduleForTenant(int tenantId, String stateCode,
            NudgeScheduleConfig nudgeCfg, EscalationScheduleConfig escalCfg, DailyReportScheduleConfig dailyCfg) {
        String schema = "tenant_" + stateCode.toLowerCase(java.util.Locale.ROOT);

        String nudgeCron = String.format(DAILY_CRON_FORMAT, nudgeCfg.getMinute(), nudgeCfg.getHour());
        String escalCron = String.format(DAILY_CRON_FORMAT, escalCfg.getMinute(), escalCfg.getHour());
        String dailyCron = String.format(DAILY_CRON_FORMAT, dailyCfg.getMinute(), dailyCfg.getHour());

        futures.put("nudge_" + tenantId,
                taskScheduler.schedule(
                        () -> {
                            try {
                                nudgeSchedulerService.processNudgesForTenant(schema, tenantId);
                            } catch (Exception e) {
                                log.error("[Scheduler] Nudge job failed for tenant={}: {}", tenantId, e.getMessage(), e);
                            }
                        },
                        new CronTrigger(nudgeCron, TimeZone.getTimeZone(IST_ZONE))));

        futures.put("escalation_" + tenantId,
                taskScheduler.schedule(
                        () -> {
                            try {
                                escalationSchedulerService.processEscalationsForTenant(schema, tenantId);
                            } catch (Exception e) {
                                log.error("[Scheduler] Escalation job failed for tenant={}: {}", tenantId, e.getMessage(), e);
                            }
                        },
                        new CronTrigger(escalCron, TimeZone.getTimeZone(IST_ZONE))));

        futures.put("dailyReport_" + tenantId,
                taskScheduler.schedule(
                        () -> {
                            try {
                                dailySituationReportSchedulerService.processDailyReportsForTenant(schema, tenantId);
                            } catch (Exception e) {
                                log.error("[Scheduler] Daily report job failed for tenant={}: {}", tenantId, e.getMessage(), e);
                            }
                        },
                        new CronTrigger(dailyCron, TimeZone.getTimeZone(IST_ZONE))));

        log.info("[Scheduler] Tenant {} ({}): nudge={}, escalation={}, dailyReport={}",
                tenantId, stateCode, nudgeCron, escalCron, dailyCron);
    }

    private void validateScheduleConfig(NudgeScheduleConfig nudgeCfg, EscalationScheduleConfig escalCfg,
            DailyReportScheduleConfig dailyCfg, int tenantId) {
        if (nudgeCfg.getHour() < 0 || nudgeCfg.getHour() > 23 || nudgeCfg.getMinute() < 0 || nudgeCfg.getMinute() > 59) {
            throw new IllegalArgumentException("Invalid nudge schedule for tenantId=" + tenantId);
        }
        if (escalCfg.getHour() < 0 || escalCfg.getHour() > 23 || escalCfg.getMinute() < 0 || escalCfg.getMinute() > 59) {
            throw new IllegalArgumentException("Invalid escalation schedule for tenantId=" + tenantId);
        }
        if (dailyCfg.getHour() < 0 || dailyCfg.getHour() > 23 || dailyCfg.getMinute() < 0 || dailyCfg.getMinute() > 59) {
            throw new IllegalArgumentException("Invalid daily-report schedule for tenantId=" + tenantId);
        }
    }

    private void cancelFutures(int tenantId) {
        for (String prefix : List.of("nudge_", "escalation_", "dailyReport_")) {
            ScheduledFuture<?> f = futures.remove(prefix + tenantId);
            if (f != null) f.cancel(false);
        }
    }
}
