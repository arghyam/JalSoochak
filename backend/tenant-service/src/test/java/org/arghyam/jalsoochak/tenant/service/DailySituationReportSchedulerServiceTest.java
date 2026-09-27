package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.event.DailyReportRequestEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DailySituationReportSchedulerService}. Verifies officer enumeration by role
 * and one {@code DAILY_REPORT_REQUEST} per officer, covering the current IST day up to the run
 * instant.
 */
@ExtendWith(MockitoExtension.class)
class DailySituationReportSchedulerServiceTest {

    @Mock
    private NudgeRepository nudgeRepository;

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private DailySituationReportSchedulerService service;

    private static final String SCHEMA = "tenant_mp";
    private static final int TENANT = 1;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "officerUserTypesCsv", "SECTION_OFFICER,SUB_DIVISIONAL_OFFICER");
    }

    @Test
    void publishesOneRequestPerOfficerAcrossBothRoles() {
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L, 12L));
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of(20L));
        // Only the SDO (20L) resolves subordinate Section Officers.
        when(nudgeRepository.findSubordinateSectionOfficerIds(SCHEMA, 20L)).thenReturn(List.of(11L, 12L));

        service.processDailyReportsForTenant(SCHEMA, TENANT);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer, org.mockito.Mockito.times(3)).publishJson(eq("common-topic"), captor.capture());

        List<Object> events = captor.getAllValues();
        // The report covers TODAY so far, not yesterday — officers act on it the same afternoon.
        String expectedDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        assertThat(events).allSatisfy(e -> {
            DailyReportRequestEvent event = (DailyReportRequestEvent) e;
            assertThat(event.getEventType()).isEqualTo("DAILY_REPORT_REQUEST");
            assertThat(event.getTenantId()).isEqualTo(TENANT);
            assertThat(event.getTenantSchema()).isEqualTo(SCHEMA);
            assertThat(event.getReportDate()).isEqualTo(expectedDate);
        });
        assertThat(events).extracting(e -> ((DailyReportRequestEvent) e).getOfficerUserId())
                .containsExactlyInAnyOrder(11L, 12L, 20L);
        assertThat(events).filteredOn(e -> ((DailyReportRequestEvent) e).getOfficerUserId() == 20L)
                .singleElement()
                .satisfies(e -> {
                    DailyReportRequestEvent sdo = (DailyReportRequestEvent) e;
                    assertThat(sdo.getOfficerUserType()).isEqualTo("SUB_DIVISIONAL_OFFICER");
                    // SDO event carries the subordinate Section Officer ids for the breakdown table.
                    assertThat(sdo.getSubordinateOfficerUserIds()).containsExactlyInAnyOrder(11L, 12L);
                });
        // Section Officer events carry no subordinate list.
        assertThat(events).filteredOn(e -> ((DailyReportRequestEvent) e).getOfficerUserType().equals("SECTION_OFFICER"))
                .allSatisfy(e -> assertThat(((DailyReportRequestEvent) e).getSubordinateOfficerUserIds()).isNull());
        // Subordinate resolution happens only for the SDO, never for a Section Officer.
        verify(nudgeRepository, org.mockito.Mockito.never()).findSubordinateSectionOfficerIds(SCHEMA, 11L);
        verify(nudgeRepository, org.mockito.Mockito.never()).findSubordinateSectionOfficerIds(SCHEMA, 12L);
    }

    @Test
    void perRoleRequestedCountsSumToTotalWhenARoleIsListedTwice() {
        // A duplicated CSV entry publishes the role's officers twice, so the per-role tally in the
        // summary line must add up to requested= rather than being overwritten by the second pass.
        ReflectionTestUtils.setField(service, "officerUserTypesCsv", "SECTION_OFFICER,SECTION_OFFICER");
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L, 12L));

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(DailySituationReportSchedulerService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.processDailyReportsForTenant(SCHEMA, TENANT);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        verify(kafkaProducer, org.mockito.Mockito.times(4)).publishJson(eq("common-topic"), org.mockito.ArgumentMatchers.any());
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("requested=4")
                .contains("requestedByRole={SECTION_OFFICER=4}"));
    }

    @Test
    void carriesACutoffOnTheReportDateSoAReplayReproducesTheNumbers() {
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of(11L));
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of());

        service.processDailyReportsForTenant(SCHEMA, TENANT);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).publishJson(eq("common-topic"), captor.capture());
        DailyReportRequestEvent event = (DailyReportRequestEvent) captor.getValue();

        assertThat(event.getCutoffIst()).isNotNull();
        LocalDateTime cutoff = LocalDateTime.parse(event.getCutoffIst());
        // The cut-off closes the window the report date opens, so it must land on that same IST day.
        assertThat(cutoff.toLocalDate()).isEqualTo(LocalDate.parse(event.getReportDate()));
        assertThat(cutoff).isBeforeOrEqualTo(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void defaultsToSectionOfficersOnly() {
        // SDOs moved to the weekly report. The default matters on its own: a deployment that sets no
        // DAILY_REPORT_OFFICER_USER_TYPES must not keep sending SDOs a daily report.
        Value annotation = (Value) Objects.requireNonNull(
                        ReflectionUtils.findField(DailySituationReportSchedulerService.class, "officerUserTypesCsv"))
                .getAnnotation(Value.class);

        assertThat(annotation.value()).isEqualTo("${daily-report.officer.user-types:SECTION_OFFICER}");
    }

    @Test
    void publishesNothingWhenNoOfficers() {
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of());
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of());

        service.processDailyReportsForTenant(SCHEMA, TENANT);

        verifyNoInteractions(kafkaProducer);
    }
}
