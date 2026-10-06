package org.arghyam.jalsoochak.tenant.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.arghyam.jalsoochak.tenant.config.DailyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.properties.NotificationSchedulerProperties;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.ScheduledJobType;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.repository.ScheduledJobRunRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs each tenant's nudge, escalation, daily report and weekly report at the tenant's configured
 * IST time, once per period however many tenant-service pods are up.
 *
 * <p>Every pod ticks once a minute. For each schedulable tenant and job it re-reads the tenant's
 * config, and when the job is due it claims the run in {@code scheduled_job_run_table}. Only the pod
 * whose claim inserts the row runs the job, so a run happens at most once per period: a failed run,
 * or one left RUNNING by a pod that died, is not retried.</p>
 *
 * <p>A job is due from its slot until the slot plus {@code notification-scheduler.grace}, which
 * covers a restart or a rolling deploy. The slot is always built on today's IST date, so the window
 * never crosses midnight.</p>
 *
 * <p>Config is re-read every tick, so a schedule change reaches every pod within a minute. A failed
 * config read skips the job for that tick without claiming it; the next tick in the window retries.</p>
 */
@Component
@ConditionalOnProperty(prefix = "notification-scheduler", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class NotificationJobScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Long enough for the exception class and a useful message; a stack trace belongs in the log. */
    static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    /** REGISTERED tenants are pre-seeded with no schema, so they never get jobs. */
    private static final Set<String> UNSCHEDULED_STATUSES = Set.of(
            TenantStatusEnum.INACTIVE.name(),
            TenantStatusEnum.SUSPENDED.name(),
            TenantStatusEnum.ARCHIVED.name(),
            TenantStatusEnum.REGISTERED.name());

    private final TenantCommonRepository tenantCommonRepository;
    private final TenantConfigService tenantConfigService;
    private final ScheduledJobRunRepository scheduledJobRunRepository;
    private final NudgeSchedulerService nudgeSchedulerService;
    private final EscalationSchedulerService escalationSchedulerService;
    private final DailySituationReportSchedulerService dailySituationReportSchedulerService;
    private final WeeklySituationReportSchedulerService weeklySituationReportSchedulerService;
    private final Duration grace;
    private final String instanceId;

    public NotificationJobScheduler(TenantCommonRepository tenantCommonRepository,
            TenantConfigService tenantConfigService,
            ScheduledJobRunRepository scheduledJobRunRepository,
            NudgeSchedulerService nudgeSchedulerService,
            EscalationSchedulerService escalationSchedulerService,
            DailySituationReportSchedulerService dailySituationReportSchedulerService,
            WeeklySituationReportSchedulerService weeklySituationReportSchedulerService,
            NotificationSchedulerProperties properties,
            @Value("${spring.cloud.client.hostname}") String instanceId) {
        this.tenantCommonRepository = tenantCommonRepository;
        this.tenantConfigService = tenantConfigService;
        this.scheduledJobRunRepository = scheduledJobRunRepository;
        this.nudgeSchedulerService = nudgeSchedulerService;
        this.escalationSchedulerService = escalationSchedulerService;
        this.dailySituationReportSchedulerService = dailySituationReportSchedulerService;
        this.weeklySituationReportSchedulerService = weeklySituationReportSchedulerService;
        this.grace = properties.getGrace();
        this.instanceId = instanceId;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void tick() {
        try {
            runDueJobs(LocalDateTime.now(IST));
        } catch (RuntimeException e) {
            log.error("[NotificationScheduler] Tick failed", e);
        }
    }

    /** Runs every job due at {@code nowIst}. Package-private so tests can drive the clock. */
    void runDueJobs(LocalDateTime nowIst) {
        // The tick fires a few milliseconds into the minute; the slots are whole minutes.
        LocalDateTime now = nowIst.truncatedTo(ChronoUnit.MINUTES);
        List<TenantResponseDTO> tenants = tenantCommonRepository.findAll();
        for (TenantResponseDTO tenant : tenants) {
            if (!isSchedulable(tenant)) {
                continue;
            }
            int tenantId = tenant.getId();
            String schema = "tenant_" + tenant.getStateCode().toLowerCase(Locale.ROOT);
            for (ScheduledJobType jobType : ScheduledJobType.values()) {
                runIfDue(jobType, tenantId, schema, now);
            }
        }
    }

    private boolean isSchedulable(TenantResponseDTO tenant) {
        String status = tenant.getStatus();
        if (status == null || UNSCHEDULED_STATUSES.contains(status)) {
            return false;
        }
        String stateCode = tenant.getStateCode();
        if (tenant.getId() == null || stateCode == null || stateCode.isBlank()) {
            log.warn("[NotificationScheduler] Skipping tenant with invalid metadata: id={}, stateCode={}",
                    tenant.getId(), stateCode);
            return false;
        }
        return true;
    }

    /** Evaluates and runs one tenant's job. Nothing it throws reaches another tenant or job. */
    private void runIfDue(ScheduledJobType jobType, int tenantId, String schema, LocalDateTime now) {
        try {
            Optional<DueRun> due = dueRun(jobType, tenantId, schema, now);
            if (due.isEmpty()) {
                return;
            }
            DueRun run = due.get();
            Optional<Long> runId = scheduledJobRunRepository.claim(
                    jobType, tenantId, run.periodKey(), run.slot(), instanceId);
            if (runId.isEmpty()) {
                log.debug("[NotificationScheduler] {} tenant={} period={} already claimed",
                        jobType, tenantId, run.periodKey());
                return;
            }
            execute(jobType, tenantId, run, runId.get());
        } catch (RuntimeException e) {
            log.error("[NotificationScheduler] {} tenant={} could not be evaluated or recorded this tick",
                    jobType, tenantId, e);
        }
    }

    private void execute(ScheduledJobType jobType, int tenantId, DueRun run, long runId) {
        log.info("[NotificationScheduler] {} tenant={} period={} slot={} claimed by {}",
                jobType, tenantId, run.periodKey(), run.slot(), instanceId);
        try {
            run.job().run();
        } catch (RuntimeException e) {
            log.error("[NotificationScheduler] {} tenant={} period={} FAILED; not retried",
                    jobType, tenantId, run.periodKey(), e);
            scheduledJobRunRepository.markFailed(runId, errorMessage(e));
            return;
        }
        scheduledJobRunRepository.markSucceeded(runId);
        log.info("[NotificationScheduler] {} tenant={} period={} SUCCEEDED", jobType, tenantId, run.periodKey());
    }

    private Optional<DueRun> dueRun(ScheduledJobType jobType, int tenantId, String schema, LocalDateTime now) {
        return switch (jobType) {
            case NUDGE -> {
                NudgeScheduleConfig cfg = tenantConfigService.getNudgeConfig(tenantId);
                yield dailyRun(now, cfg.getHour(), cfg.getMinute(),
                        runDate -> nudgeSchedulerService.processNudgesForTenant(schema, tenantId, runDate));
            }
            case ESCALATION -> {
                EscalationScheduleConfig cfg = tenantConfigService.getEscalationConfig(tenantId);
                yield dailyRun(now, cfg.getHour(), cfg.getMinute(),
                        runDate -> escalationSchedulerService.processEscalationsForTenant(schema, tenantId, runDate));
            }
            case DAILY_REPORT -> {
                DailyReportScheduleConfig cfg = tenantConfigService.getDailyReportConfig(tenantId);
                yield dailyRun(now, cfg.getHour(), cfg.getMinute(),
                        runDate -> dailySituationReportSchedulerService.processDailyReportsForTenant(
                                schema, tenantId, runDate));
            }
            case WEEKLY_REPORT -> weeklyRun(tenantId, schema, now);
        };
    }

    private Optional<DueRun> dailyRun(LocalDateTime now, int hour, int minute, Consumer<LocalDate> job) {
        return dueSlot(now, hour, minute).map(slot -> {
            LocalDate runDate = slot.toLocalDate();
            return new DueRun(slot, runDate, () -> job.accept(runDate));
        });
    }

    private Optional<DueRun> weeklyRun(int tenantId, String schema, LocalDateTime now) {
        WeeklyReportScheduleConfig cfg = tenantConfigService.getWeeklyReportConfig(tenantId);
        DayOfWeek firingDay = WeeklyReportScheduleConfig.toDayOfWeek(cfg.getDayOfWeek());
        if (now.getDayOfWeek() != firingDay) {
            return Optional.empty();
        }
        DayOfWeek weekStartDay = cfg.getWeekStartDayOfWeek();
        return dueSlot(now, cfg.getHour(), cfg.getMinute()).map(slot -> {
            LocalDate runDate = slot.toLocalDate();
            // Keyed on the reported week, not the run day: moving the firing day within a week then
            // finds that week's claim instead of sending its report again.
            LocalDate periodKey = WeeklySituationReportSchedulerService.reportedWeekStart(runDate, weekStartDay);
            return new DueRun(slot, periodKey, () -> {
                warnIfMisaligned(tenantId, firingDay, weekStartDay);
                weeklySituationReportSchedulerService.processWeeklyReportsForTenant(
                        schema, tenantId, weekStartDay, runDate);
            });
        });
    }

    /** Today's slot at {@code hour:minute}, if {@code now} is inside its due window. */
    private Optional<LocalDateTime> dueSlot(LocalDateTime now, int hour, int minute) {
        LocalDateTime slot = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute));
        boolean due = !now.isBefore(slot) && !now.isAfter(slot.plus(grace));
        return due ? Optional.of(slot) : Optional.empty();
    }

    /**
     * Legal but usually unintended, so it is logged once per weekly run: the further the firing day
     * is past the end of the reported week, the staler the data is on arrival.
     */
    private static void warnIfMisaligned(int tenantId, DayOfWeek firingDay, DayOfWeek weekStartDay) {
        // Compares converted days, not the raw ints: cron 0 and 7 are both Sunday.
        if (firingDay == weekStartDay) {
            return;
        }
        DayOfWeek weekEndDay = weekStartDay.minus(1);
        log.warn("[NotificationScheduler] Tenant {}: weekly report fires on {} but the reported week starts on"
                        + " {} and ends on {} — its newest data will be {} day(s) old on delivery",
                tenantId, firingDay, weekStartDay, weekEndDay, daysBetweenForward(weekEndDay, firingDay));
    }

    /** Whole days from {@code from} forward to {@code to} on the weekly cycle, a full 7 for the same day. */
    private static int daysBetweenForward(DayOfWeek from, DayOfWeek to) {
        int diff = to.getValue() - from.getValue();
        return diff <= 0 ? diff + 7 : diff;
    }

    /** The exception class and message, capped; no stack trace. */
    static String errorMessage(Throwable e) {
        String message = e.toString();
        return message.length() <= ERROR_MESSAGE_MAX_LENGTH ? message : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    /** A due run: the slot it is claimed at, the period it is claimed under, and the work. */
    private record DueRun(LocalDateTime slot, LocalDate periodKey, Runnable job) {
    }
}
