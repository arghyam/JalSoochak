package org.arghyam.jalsoochak.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link NotificationJobScheduler}: when a job is due, which pod runs it, and how one
 * tenant's failure is kept from the rest. The clock is driven through {@code runDueJobs}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationJobSchedulerTest {

    private static final String HOST = "tenant-service-0";
    private static final long RUN_ID = 7L;
    /** A Monday. */
    private static final LocalDate MON = LocalDate.of(2026, 9, 28);

    @Mock private TenantCommonRepository tenantCommonRepository;
    @Mock private TenantConfigService tenantConfigService;
    @Mock private ScheduledJobRunRepository runRepository;
    @Mock private NudgeSchedulerService nudgeService;
    @Mock private EscalationSchedulerService escalationService;
    @Mock private DailySituationReportSchedulerService dailyReportService;
    @Mock private WeeklySituationReportSchedulerService weeklyReportService;

    private NotificationJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = schedulerWithGrace(Duration.ofMinutes(15));
        // Every job at its own slot, so a tick at one slot runs exactly one job.
        lenient().when(tenantConfigService.getNudgeConfig(anyInt())).thenReturn(nudgeAt(18, 0));
        lenient().when(tenantConfigService.getEscalationConfig(anyInt())).thenReturn(escalationAt(19, 0));
        lenient().when(tenantConfigService.getDailyReportConfig(anyInt())).thenReturn(dailyReportAt(16, 0));
        lenient().when(tenantConfigService.getWeeklyReportConfig(anyInt())).thenReturn(weeklyReportAt(1, 9, 0, 1));
        lenient().when(runRepository.claim(any(), anyInt(), any(), any(), anyString()))
                .thenReturn(Optional.of(RUN_ID));
    }

    @Nested
    @DisplayName("slot and grace")
    class SlotAndGrace {

        @Test
        @DisplayName("runs at the slot, a few milliseconds into the minute")
        void runsAtTheSlot() {
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 0, 0, 3_000_000));

            verify(runRepository).claim(ScheduledJobType.DAILY_REPORT, 1, MON, MON.atTime(16, 0), HOST);
            verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
            verify(runRepository).markSucceeded(RUN_ID);
        }

        @Test
        @DisplayName("is not due a minute early")
        void notDueAMinuteEarly() {
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(15, 59, 59));

            verify(runRepository, never()).claim(any(), anyInt(), any(), any(), anyString());
            verifyNoInteractions(dailyReportService);
        }

        @Test
        @DisplayName("still runs at slot + grace, claimed at the slot rather than the tick")
        void runsAtTheEndOfTheGrace() {
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 15, 30));

            verify(runRepository).claim(ScheduledJobType.DAILY_REPORT, 1, MON, MON.atTime(16, 0), HOST);
            verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
        }

        @Test
        @DisplayName("is skipped once the grace has passed")
        void skippedAfterTheGrace() {
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 16));

            verify(runRepository, never()).claim(any(), anyInt(), any(), any(), anyString());
            verifyNoInteractions(dailyReportService);
        }

        @Test
        @DisplayName("with zero grace, runs only in the slot minute")
        void zeroGraceRunsOnlyInTheSlotMinute() {
            scheduler = schedulerWithGrace(Duration.ZERO);
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 1));
            verifyNoInteractions(dailyReportService);

            scheduler.runDueJobs(MON.atTime(16, 0, 40));
            verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
        }

        @Test
        @DisplayName("never crosses midnight: a late-evening slot is not due just after midnight")
        void neverCrossesMidnight() {
            when(tenantConfigService.getDailyReportConfig(1)).thenReturn(dailyReportAt(23, 50));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.plusDays(1).atTime(0, 2));

            verify(runRepository, never()).claim(any(), anyInt(), any(), any(), anyString());
            verifyNoInteractions(dailyReportService);
        }
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @ParameterizedTest(name = "{0} at {1}")
        @CsvSource({
                "NUDGE, 18:00",
                "ESCALATION, 19:00",
                "DAILY_REPORT, 16:00",
                "WEEKLY_REPORT, 09:00"
        })
        @DisplayName("each job goes to its own service at its own slot, with the slot's date")
        void routesEachJobToItsOwnService(ScheduledJobType jobType, LocalTime slot) {
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(slot));

            verify(runRepository).claim(eq(jobType), eq(1), any(), eq(MON.atTime(slot)), eq(HOST));
            switch (jobType) {
                case NUDGE -> verify(nudgeService).processNudgesForTenant("tenant_mp", 1, MON);
                case ESCALATION -> verify(escalationService).processEscalationsForTenant("tenant_mp", 1, MON);
                case DAILY_REPORT -> verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
                case WEEKLY_REPORT -> verify(weeklyReportService)
                        .processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.MONDAY, MON);
            }
            verifyNoMoreInteractions(nudgeService, escalationService, dailyReportService, weeklyReportService);
        }
    }

    @Nested
    @DisplayName("claims and failures")
    class ClaimsAndFailures {

        @Test
        @DisplayName("a lost claim does not run the job")
        void lostClaimDoesNotRun() {
            when(runRepository.claim(any(), anyInt(), any(), any(), anyString())).thenReturn(Optional.empty());
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 0));

            verifyNoInteractions(dailyReportService);
            verify(runRepository, never()).markSucceeded(anyLong());
            verify(runRepository, never()).markFailed(anyLong(), any());
        }

        @Test
        @DisplayName("a failed job is marked FAILED and the tenant's other due jobs still run")
        void failedJobIsRecordedAndOthersRun() {
            when(tenantConfigService.getEscalationConfig(1)).thenReturn(escalationAt(18, 0));
            when(runRepository.claim(eq(ScheduledJobType.NUDGE), anyInt(), any(), any(), anyString()))
                    .thenReturn(Optional.of(11L));
            when(runRepository.claim(eq(ScheduledJobType.ESCALATION), anyInt(), any(), any(), anyString()))
                    .thenReturn(Optional.of(12L));
            doThrow(new IllegalStateException("kafka down"))
                    .when(nudgeService).processNudgesForTenant("tenant_mp", 1, MON);
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(18, 0));

            verify(runRepository).markFailed(11L, "java.lang.IllegalStateException: kafka down");
            verify(runRepository, never()).markSucceeded(11L);
            verify(escalationService).processEscalationsForTenant("tenant_mp", 1, MON);
            verify(runRepository).markSucceeded(12L);
        }

        @Test
        @DisplayName("a long error message is capped")
        void longErrorMessageIsCapped() {
            doThrow(new IllegalStateException("x".repeat(5000)))
                    .when(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 0));

            ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
            verify(runRepository).markFailed(eq(RUN_ID), error.capture());
            assertThat(error.getValue())
                    .hasSize(NotificationJobScheduler.ERROR_MESSAGE_MAX_LENGTH)
                    .startsWith("java.lang.IllegalStateException: xxx");
        }

        @Test
        @DisplayName("a config read that throws skips only that job, without a claim, and the next tick runs it")
        void failedConfigReadSkipsOnlyThatJobUntilTheNextTick() {
            when(tenantConfigService.getEscalationConfig(1)).thenReturn(escalationAt(18, 0));
            when(tenantConfigService.getNudgeConfig(1))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"))
                    .thenReturn(nudgeAt(18, 0));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(18, 0));

            verify(runRepository, never()).claim(eq(ScheduledJobType.NUDGE), anyInt(), any(), any(), anyString());
            verifyNoInteractions(nudgeService);
            verify(escalationService).processEscalationsForTenant("tenant_mp", 1, MON);

            scheduler.runDueJobs(MON.atTime(18, 1));

            verify(runRepository).claim(ScheduledJobType.NUDGE, 1, MON, MON.atTime(18, 0), HOST);
            verify(nudgeService).processNudgesForTenant("tenant_mp", 1, MON);
        }

        @Test
        @DisplayName("a tick whose tenant listing fails logs and returns")
        void tickSurvivesAFailedTenantListing() {
            when(tenantCommonRepository.findAll()).thenThrow(new DataAccessResourceFailureException("down"));

            assertThatCode(scheduler::tick).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("tenant selection")
    class TenantSelection {

        @ParameterizedTest
        @EnumSource(value = TenantStatusEnum.class, names = {"ONBOARDED", "CONFIGURED", "ACTIVE", "DEGRADED"})
        @DisplayName("runs for a tenant in a schedulable status")
        void runsForSchedulableStatus(TenantStatusEnum status) {
            givenTenants(tenant(1, "MP", status));

            scheduler.runDueJobs(MON.atTime(16, 0));

            verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
        }

        @ParameterizedTest
        @EnumSource(value = TenantStatusEnum.class, names = {"ONBOARDED", "CONFIGURED", "ACTIVE", "DEGRADED"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("skips a tenant in any other status")
        void skipsUnschedulableStatus(TenantStatusEnum status) {
            givenTenants(tenant(1, "MP", status));

            scheduler.runDueJobs(MON.atTime(16, 0));

            verifyNoInteractions(tenantConfigService, runRepository, dailyReportService);
        }

        @Test
        @DisplayName("skips a tenant with no status")
        void skipsNullStatus() {
            givenTenants(TenantResponseDTO.builder().id(1).stateCode("MP").build());

            scheduler.runDueJobs(MON.atTime(16, 0));

            verifyNoInteractions(tenantConfigService, runRepository, dailyReportService);
        }

        @Test
        @DisplayName("skips a tenant with a null id or a null or blank state code, and runs the next one")
        void skipsInvalidMetadata() {
            givenTenants(
                    tenant(null, "UP", TenantStatusEnum.ACTIVE),
                    tenant(2, null, TenantStatusEnum.ACTIVE),
                    tenant(3, "  ", TenantStatusEnum.ACTIVE),
                    tenant(4, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 0));

            verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 4, MON);
            verifyNoMoreInteractions(dailyReportService);
        }

        @Test
        @DisplayName("two tenants each run against their own schema at their own time")
        void tenantsRunAgainstTheirOwnSchemaAndTime() {
            when(tenantConfigService.getDailyReportConfig(2)).thenReturn(dailyReportAt(17, 0));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE), tenant(2, "TR", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(16, 0));
            verify(dailyReportService).processDailyReportsForTenant("tenant_mp", 1, MON);
            verifyNoMoreInteractions(dailyReportService);

            scheduler.runDueJobs(MON.atTime(17, 0));
            verify(dailyReportService).processDailyReportsForTenant("tenant_tr", 2, MON);
            verify(runRepository).claim(ScheduledJobType.DAILY_REPORT, 2, MON, MON.atTime(17, 0), HOST);
        }
    }

    @Nested
    @DisplayName("weekly report")
    class WeeklyReport {

        private final LocalDate thursday = LocalDate.of(2026, 10, 1);

        @Test
        @DisplayName("runs on its day, claimed under the first day of the reported week")
        void runsOnItsDayClaimedUnderTheReportedWeek() {
            when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(weeklyReportAt(4, 9, 0, 1));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(thursday.atTime(9, 0));

            verify(runRepository).claim(ScheduledJobType.WEEKLY_REPORT, 1, LocalDate.of(2026, 9, 21),
                    thursday.atTime(9, 0), HOST);
            verify(weeklyReportService).processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.MONDAY, thursday);
        }

        @Test
        @DisplayName("is not due on any other day")
        void notDueOnOtherDays() {
            when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(weeklyReportAt(4, 9, 0, 1));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(thursday.minusDays(1).atTime(9, 0));

            verify(runRepository, never()).claim(any(), anyInt(), any(), any(), anyString());
            verifyNoInteractions(weeklyReportService);
        }

        @Test
        @DisplayName("cron day 0 is Sunday")
        void cronDayZeroIsSunday() {
            when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(weeklyReportAt(0, 9, 0, 1));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));
            LocalDate sunday = LocalDate.of(2026, 10, 4);

            scheduler.runDueJobs(sunday.minusDays(6).atTime(9, 0));
            verifyNoInteractions(weeklyReportService);

            scheduler.runDueJobs(sunday.atTime(9, 0));
            verify(weeklyReportService).processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.MONDAY, sunday);
        }

        @Test
        @DisplayName("moving the firing day within a week reuses that week's key, so it is not sent twice")
        void movingTheFiringDayReusesTheWeeksKey() {
            when(tenantConfigService.getWeeklyReportConfig(1))
                    .thenReturn(weeklyReportAt(1, 9, 0, 1))
                    .thenReturn(weeklyReportAt(3, 9, 0, 1));
            LocalDate weekKey = LocalDate.of(2026, 9, 21);
            when(runRepository.claim(ScheduledJobType.WEEKLY_REPORT, 1, weekKey, MON.atTime(9, 0), HOST))
                    .thenReturn(Optional.of(RUN_ID));
            when(runRepository.claim(ScheduledJobType.WEEKLY_REPORT, 1, weekKey, MON.plusDays(2).atTime(9, 0), HOST))
                    .thenReturn(Optional.empty());
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            scheduler.runDueJobs(MON.atTime(9, 0));
            scheduler.runDueJobs(MON.plusDays(2).atTime(9, 0));

            verify(weeklyReportService).processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.MONDAY, MON);
            verifyNoMoreInteractions(weeklyReportService);
        }

        @Test
        @DisplayName("passes the configured weekStartDay through")
        void passesWeekStartDayThrough() {
            when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(weeklyReportAt(5, 9, 0, 4));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));
            LocalDate friday = LocalDate.of(2026, 10, 2);

            scheduler.runDueJobs(friday.atTime(9, 0));

            verify(runRepository).claim(ScheduledJobType.WEEKLY_REPORT, 1, LocalDate.of(2026, 9, 24),
                    friday.atTime(9, 0), HOST);
            verify(weeklyReportService).processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.THURSDAY, friday);
        }

        @Test
        @DisplayName("warns when the firing day does not match the week start")
        void warnsOnMisalignment() {
            when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(weeklyReportAt(4, 9, 0, 1));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));

            List<ILoggingEvent> logs = captureLogs(() -> scheduler.runDueJobs(thursday.atTime(9, 0)));

            assertThat(logs).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("fires on THURSDAY but the reported week starts on MONDAY");
            });
        }

        @Test
        @DisplayName("does not warn for 0 vs 7, which are both Sunday")
        void noWarningForZeroVersusSeven() {
            when(tenantConfigService.getWeeklyReportConfig(1)).thenReturn(weeklyReportAt(0, 9, 0, 7));
            givenTenants(tenant(1, "MP", TenantStatusEnum.ACTIVE));
            LocalDate sunday = LocalDate.of(2026, 10, 4);

            List<ILoggingEvent> logs = captureLogs(() -> scheduler.runDueJobs(sunday.atTime(9, 0)));

            verify(weeklyReportService).processWeeklyReportsForTenant("tenant_mp", 1, DayOfWeek.SUNDAY, sunday);
            assertThat(logs).noneMatch(event -> event.getLevel() == Level.WARN);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private NotificationJobScheduler schedulerWithGrace(Duration grace) {
        NotificationSchedulerProperties properties = new NotificationSchedulerProperties();
        properties.setGrace(grace);
        return new NotificationJobScheduler(tenantCommonRepository, tenantConfigService, runRepository,
                nudgeService, escalationService, dailyReportService, weeklyReportService, properties, HOST);
    }

    private void givenTenants(TenantResponseDTO... tenants) {
        when(tenantCommonRepository.findAll()).thenReturn(List.of(tenants));
    }

    private static TenantResponseDTO tenant(Integer id, String stateCode, TenantStatusEnum status) {
        return TenantResponseDTO.builder().id(id).stateCode(stateCode).status(status.name()).build();
    }

    private static NudgeScheduleConfig nudgeAt(int hour, int minute) {
        return NudgeScheduleConfig.builder().hour(hour).minute(minute).build();
    }

    private static EscalationScheduleConfig escalationAt(int hour, int minute) {
        return EscalationScheduleConfig.builder().hour(hour).minute(minute)
                .level1Days(3).level1OfficerType("SECTION_OFFICER")
                .level2Days(7).level2OfficerType("DISTRICT_OFFICER")
                .build();
    }

    private static DailyReportScheduleConfig dailyReportAt(int hour, int minute) {
        return DailyReportScheduleConfig.builder().hour(hour).minute(minute).build();
    }

    private static WeeklyReportScheduleConfig weeklyReportAt(int dayOfWeek, int hour, int minute, int weekStartDay) {
        return WeeklyReportScheduleConfig.builder()
                .dayOfWeek(dayOfWeek).hour(hour).minute(minute).weekStartDay(weekStartDay).build();
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationJobScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }
}
