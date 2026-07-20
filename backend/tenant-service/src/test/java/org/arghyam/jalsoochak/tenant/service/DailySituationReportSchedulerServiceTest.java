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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DailySituationReportSchedulerService}. Verifies officer enumeration by role
 * and one {@code DAILY_REPORT_REQUEST} per officer (covering the previous IST day).
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
        String expectedDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1).toString();

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
    void publishesNothingWhenNoOfficers() {
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SECTION_OFFICER"))
                .thenReturn(List.of());
        when(nudgeRepository.findDistinctOfficerUserIdsByUserType(SCHEMA, "SUB_DIVISIONAL_OFFICER"))
                .thenReturn(List.of());

        service.processDailyReportsForTenant(SCHEMA, TENANT);

        verifyNoInteractions(kafkaProducer);
    }
}
