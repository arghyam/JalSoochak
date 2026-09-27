package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.event.WeeklyReportRequestEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeeklySituationReportSchedulerService}: officer enumeration across both
 * roles, and the week arithmetic that decides which seven days the report covers.
 */
@ExtendWith(MockitoExtension.class)
class WeeklySituationReportSchedulerServiceTest {

    @Mock
    private NudgeRepository nudgeRepository;

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private WeeklySituationReportSchedulerService service;

    private static final String SCHEMA = "tenant_mp";
    private static final int TENANT = 1;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "officerUserTypesCsv", "SECTION_OFFICER,SUB_DIVISIONAL_OFFICER");
    }

    @Test
    @DisplayName("publishes one request per officer across both roles, with subordinates only for the SDO")
    void publishesOneRequestPerOfficerAcrossBothRoles() {
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L, 12L));
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of(20L));
        when(nudgeRepository.findSubordinateSectionOfficerIds(SCHEMA, 20L)).thenReturn(List.of(11L, 12L));

        service.processWeeklyReportsForTenant(SCHEMA, TENANT, DayOfWeek.MONDAY);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer, times(3)).publishJson(eq("common-topic"), captor.capture());
        List<Object> events = captor.getAllValues();

        assertThat(events).allSatisfy(e -> {
            WeeklyReportRequestEvent event = (WeeklyReportRequestEvent) e;
            assertThat(event.getEventType()).isEqualTo("WEEKLY_REPORT_REQUEST");
            assertThat(event.getTenantId()).isEqualTo(TENANT);
            assertThat(event.getTenantSchema()).isEqualTo(SCHEMA);
        });
        assertThat(events).extracting(e -> ((WeeklyReportRequestEvent) e).getOfficerUserId())
                .containsExactlyInAnyOrder(11L, 12L, 20L);
        assertThat(events).filteredOn(e -> ((WeeklyReportRequestEvent) e).getOfficerUserId() == 20L)
                .singleElement()
                .satisfies(e -> {
                    WeeklyReportRequestEvent sdo = (WeeklyReportRequestEvent) e;
                    assertThat(sdo.getOfficerUserType()).isEqualTo("SUB_DIVISIONAL_OFFICER");
                    assertThat(sdo.getSubordinateOfficerUserIds()).containsExactlyInAnyOrder(11L, 12L);
                });
        assertThat(events).filteredOn(e -> "SECTION_OFFICER".equals(((WeeklyReportRequestEvent) e).getOfficerUserType()))
                .allSatisfy(e -> assertThat(((WeeklyReportRequestEvent) e).getSubordinateOfficerUserIds()).isNull());
        verify(nudgeRepository, never()).findSubordinateSectionOfficerIds(SCHEMA, 11L);
    }

    @Test
    @DisplayName("a role repeated in the CSV publishes one request per officer, not two")
    void deduplicatesRepeatedRolesInTheConfiguredCsv() {
        ReflectionTestUtils.setField(service, "officerUserTypesCsv",
                "SECTION_OFFICER, SECTION_OFFICER ,SUB_DIVISIONAL_OFFICER");
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L));
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of(20L));
        when(nudgeRepository.findSubordinateSectionOfficerIds(SCHEMA, 20L)).thenReturn(List.of(11L));

        service.processWeeklyReportsForTenant(SCHEMA, TENANT, DayOfWeek.MONDAY);

        // Two events, not three: a duplicate would mean a second PDF and a second WhatsApp message
        // landing on a real officer.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer, times(2)).publishJson(eq("common-topic"), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(e -> ((WeeklyReportRequestEvent) e).getOfficerUserId())
                .containsExactlyInAnyOrder(11L, 20L);
        verify(nudgeRepository, times(1))
                .findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER");
    }

    @Test
    @DisplayName("reports a complete Monday-to-Sunday week that has already ended")
    void coversTheLastCompleteWeek() {
        WeeklyReportRequestEvent event = publishOneAndCapture(DayOfWeek.MONDAY);

        LocalDate weekStart = LocalDate.parse(event.getWeekStart());
        LocalDate weekEnd = LocalDate.parse(event.getWeekEnd());

        assertThat(weekStart.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(weekEnd.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(weekStart.plusDays(6)).isEqualTo(weekEnd);
        // The window must be closed: a report covering a day that has not finished would under-count
        // every scheme that supplies later that day.
        assertThat(weekEnd).isBefore(LocalDate.now(IST));
    }

    @Test
    @DisplayName("the default Monday window is exactly the previous-Sunday rule it replaced")
    void mondayWindowMatchesTheOriginalRule() {
        WeeklyReportRequestEvent event = publishOneAndCapture(DayOfWeek.MONDAY);

        // Pins the no-op-on-deploy guarantee against the pre-change implementation.
        LocalDate expectedEnd = LocalDate.now(IST).with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        assertThat(LocalDate.parse(event.getWeekEnd())).isEqualTo(expectedEnd);
        assertThat(LocalDate.parse(event.getWeekStart())).isEqualTo(expectedEnd.minusDays(6));
    }

    @ParameterizedTest
    @EnumSource(DayOfWeek.class)
    @DisplayName("reports the last complete seven-day week beginning on the configured day")
    void coversTheLastCompleteWeekForAnyStartDay(DayOfWeek weekStartDay) {
        WeeklyReportRequestEvent event = publishOneAndCapture(weekStartDay);

        LocalDate weekStart = LocalDate.parse(event.getWeekStart());
        LocalDate weekEnd = LocalDate.parse(event.getWeekEnd());

        assertThat(weekStart.getDayOfWeek()).isEqualTo(weekStartDay);
        assertThat(weekEnd.getDayOfWeek()).isEqualTo(weekStartDay.minus(1));
        assertThat(weekStart.plusDays(6)).isEqualTo(weekEnd);
        // Holds for every start day: the window never reaches into a day still in progress.
        assertThat(weekEnd).isBefore(LocalDate.now(IST));
    }

    @ParameterizedTest
    @EnumSource(DayOfWeek.class)
    @DisplayName("the comparison week is the seven days immediately before the reported week")
    void comparisonWeekAbutsTheReportedWeek(DayOfWeek weekStartDay) {
        WeeklyReportRequestEvent event = publishOneAndCapture(weekStartDay);

        LocalDate weekStart = LocalDate.parse(event.getWeekStart());
        LocalDate previousStart = LocalDate.parse(event.getPreviousWeekStart());
        LocalDate previousEnd = LocalDate.parse(event.getPreviousWeekEnd());

        assertThat(previousStart).isEqualTo(weekStart.minusDays(7));
        assertThat(previousEnd).isEqualTo(weekStart.minusDays(1));
        assertThat(previousStart.getDayOfWeek()).isEqualTo(weekStartDay);
        assertThat(previousEnd.getDayOfWeek()).isEqualTo(weekStartDay.minus(1));
    }

    @Test
    @DisplayName("running on the day the week closes still reports the week before, never a partial one")
    void runningOnTheWeekEndDayReportsThePriorWeek() {
        // A week starting the day after today ends today, which has not finished yet.
        DayOfWeek weekStartDay = LocalDate.now(IST).getDayOfWeek().plus(1);

        WeeklyReportRequestEvent event = publishOneAndCapture(weekStartDay);

        LocalDate weekEnd = LocalDate.parse(event.getWeekEnd());
        assertThat(weekEnd).isEqualTo(LocalDate.now(IST).minusDays(7));
        assertThat(weekEnd).isBefore(LocalDate.now(IST));
    }

    @Test
    @DisplayName("the summary log reconciles with the de-duplicated request count")
    void perRoleRequestedCountsSumToTotal() {
        ReflectionTestUtils.setField(service, "officerUserTypesCsv", "SECTION_OFFICER,SECTION_OFFICER");
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L, 12L));

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WeeklySituationReportSchedulerService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.processWeeklyReportsForTenant(SCHEMA, TENANT, DayOfWeek.MONDAY);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        // The repeated role collapses to one pass, so the denominator the downstream GENERATED/SENT
        // counts reconcile against is the two officers that were actually messaged.
        verify(kafkaProducer, times(2)).publishJson(eq("common-topic"), any());
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("requested=2")
                .contains("requestedByRole={SECTION_OFFICER=2}"));
    }

    @Test
    void publishesNothingWhenNoOfficers() {
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of());
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of());

        service.processWeeklyReportsForTenant(SCHEMA, TENANT, DayOfWeek.MONDAY);

        verifyNoInteractions(kafkaProducer);
    }

    /** Runs the job for a single Section Officer and returns the one event it published. */
    private WeeklyReportRequestEvent publishOneAndCapture(DayOfWeek weekStartDay) {
        ReflectionTestUtils.setField(service, "officerUserTypesCsv", "SECTION_OFFICER");
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L));

        service.processWeeklyReportsForTenant(SCHEMA, TENANT, weekStartDay);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).publishJson(eq("common-topic"), captor.capture());
        return (WeeklyReportRequestEvent) captor.getValue();
    }
}
