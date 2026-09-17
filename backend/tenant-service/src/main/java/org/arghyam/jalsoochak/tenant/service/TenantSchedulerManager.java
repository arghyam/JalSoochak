package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.DailyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages per-tenant scheduled jobs for nudges, escalations, and the daily and weekly water
 * situation reports.
 *
 * <p>At startup, reads all active tenants and schedules all four jobs for each, using the cron times
 * from {@code common_schema.tenant_config_master_table}. Missing config rows fall
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
    private final WeeklySituationReportSchedulerService weeklySituationReportSchedulerService;

    private static final String DAILY_CRON_FORMAT = "0 %d %d * * ?";
    /** {@code sec min hour day-of-month month day-of-week} — e.g. {@code 0 0 9 ? * 1} for Monday 09:00. */
    private static final String WEEKLY_CRON_FORMAT = "0 %d %d ? * %d";
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
            WeeklyReportScheduleConfig weeklyCfg = tenantConfigService.getWeeklyReportConfig(tenantId);
            validateScheduleConfig(nudgeCfg, escalCfg, dailyCfg, weeklyCfg, tenantId);

            cancelFutures(tenantId);
            scheduleForTenant(tenantId, stateCode, nudgeCfg, escalCfg, dailyCfg, weeklyCfg);
        }
    }

    private void scheduleForTenant(int tenantId, String stateCode) {
        NudgeScheduleConfig nudgeCfg = tenantConfigService.getNudgeConfig(tenantId);
        EscalationScheduleConfig escalCfg = tenantConfigService.getEscalationConfig(tenantId);
        DailyReportScheduleConfig dailyCfg = tenantConfigService.getDailyReportConfig(tenantId);
        WeeklyReportScheduleConfig weeklyCfg = tenantConfigService.getWeeklyReportConfig(tenantId);
        validateScheduleConfig(nudgeCfg, escalCfg, dailyCfg, weeklyCfg, tenantId);
        scheduleForTenant(tenantId, stateCode, nudgeCfg, escalCfg, dailyCfg, weeklyCfg);
    }

    private void scheduleForTenant(int tenantId, String stateCode,
            NudgeScheduleConfig nudgeCfg, EscalationScheduleConfig escalCfg, DailyReportScheduleConfig dailyCfg,
            WeeklyReportScheduleConfig weeklyCfg) {
        String schema = "tenant_" + stateCode.toLowerCase(java.util.Locale.ROOT);

        String nudgeCron = String.format(DAILY_CRON_FORMAT, nudgeCfg.getMinute(), nudgeCfg.getHour());
        String escalCron = String.format(DAILY_CRON_FORMAT, escalCfg.getMinute(), escalCfg.getHour());
        String dailyCron = String.format(DAILY_CRON_FORMAT, dailyCfg.getMinute(), dailyCfg.getHour());
        String weeklyCron = String.format(WEEKLY_CRON_FORMAT,
                weeklyCfg.getMinute(), weeklyCfg.getHour(), weeklyCfg.getDayOfWeek());

        // Safe: validateScheduleConfig has already range-checked both fields.
        DayOfWeek weekStartDay = weeklyCfg.getWeekStartDayOfWeek();
        DayOfWeek weeklyCronDay = WeeklyReportScheduleConfig.toDayOfWeek(weeklyCfg.getDayOfWeek());
        // Compare the converted days, not the raw ints: cron 0 and 7 are both Sunday, and comparing
        // ints would warn about a tenant whose two settings actually agree.
        if (weeklyCronDay != weekStartDay) {
            log.warn("[Scheduler] Tenant {} ({}): weekly report fires on {} but the reported week starts on"
                            + " {} and ends on {} — its newest data will be {} day(s) old on delivery",
                    tenantId, stateCode, weeklyCronDay, weekStartDay, weekStartDay.minus(1),
                    daysBetweenForward(weekStartDay.minus(1), weeklyCronDay));
        }

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

        futures.put("weeklyReport_" + tenantId,
                taskScheduler.schedule(
                        () -> {
                            try {
                                weeklySituationReportSchedulerService.processWeeklyReportsForTenant(
                                        schema, tenantId, weekStartDay);
                            } catch (Exception e) {
                                log.error("[Scheduler] Weekly report job failed for tenant={}: {}", tenantId, e.getMessage(), e);
                            }
                        },
                        new CronTrigger(weeklyCron, TimeZone.getTimeZone(IST_ZONE))));

        log.info("[Scheduler] Tenant {} ({}): nudge={}, escalation={}, dailyReport={}, weeklyReport={}"
                        + " (week {}..{})",
                tenantId, stateCode, nudgeCron, escalCron, dailyCron, weeklyCron,
                weekStartDay, weekStartDay.minus(1));
    }

    /**
     * Whole days from {@code from} forward to {@code to} on the weekly cycle, counting a full 7 when
     * the two are the same day. Used only to say how stale the data will be in the mismatch warning.
     */
    private static int daysBetweenForward(DayOfWeek from, DayOfWeek to) {
        int diff = to.getValue() - from.getValue();
        return diff <= 0 ? diff + 7 : diff;
    }

    private void validateScheduleConfig(NudgeScheduleConfig nudgeCfg, EscalationScheduleConfig escalCfg,
            DailyReportScheduleConfig dailyCfg, WeeklyReportScheduleConfig weeklyCfg, int tenantId) {
        if (nudgeCfg.getHour() < 0 || nudgeCfg.getHour() > 23 || nudgeCfg.getMinute() < 0 || nudgeCfg.getMinute() > 59) {
            throw new IllegalArgumentException("Invalid nudge schedule for tenantId=" + tenantId);
        }
        if (escalCfg.getHour() < 0 || escalCfg.getHour() > 23 || escalCfg.getMinute() < 0 || escalCfg.getMinute() > 59) {
            throw new IllegalArgumentException("Invalid escalation schedule for tenantId=" + tenantId);
        }
        if (dailyCfg.getHour() < 0 || dailyCfg.getHour() > 23 || dailyCfg.getMinute() < 0 || dailyCfg.getMinute() > 59) {
            throw new IllegalArgumentException("Invalid daily-report schedule for tenantId=" + tenantId);
        }
        if (weeklyCfg.getHour() < 0 || weeklyCfg.getHour() > 23
                || weeklyCfg.getMinute() < 0 || weeklyCfg.getMinute() > 59
                // 0-7 in the cron convention; both 0 and 7 are Sunday. weekStartDay must be range-checked
                // here and not left to WeeklyReportScheduleConfig.toDayOfWeek: that throws, and a throw
                // from inside scheduleForTenant lands after cancelFutures, leaving the tenant with no
                // jobs at all. Validating first keeps a bad value from unscheduling anything.
                || weeklyCfg.getDayOfWeek() < 0 || weeklyCfg.getDayOfWeek() > 7
                || weeklyCfg.getWeekStartDay() < 0 || weeklyCfg.getWeekStartDay() > 7) {
            throw new IllegalArgumentException("Invalid weekly-report schedule for tenantId=" + tenantId);
        }
    }

    private void cancelFutures(int tenantId) {
        for (String prefix : List.of("nudge_", "escalation_", "dailyReport_", "weeklyReport_")) {
            ScheduledFuture<?> f = futures.remove(prefix + tenantId);
            if (f != null) f.cancel(false);
        }
    }
}
