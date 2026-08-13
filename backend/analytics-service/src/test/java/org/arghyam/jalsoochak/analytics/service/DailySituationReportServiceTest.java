package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DailySituationReportService} — verifies the KPI assembly math
 * (MLD, LPCD, percentages, trend inputs), the SDO breakdown's scheme scoping, and the outage-section
 * toggle, using mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
class DailySituationReportServiceTest {

    @Mock
    private DailySituationReportRepository reportRepository;

    @Mock
    private SchemeRegularityRepository schemeRegularityRepository;

    private static final int TENANT = 1;
    private static final long OFFICER = 500L;
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 7, 7);
    private static final LocalDate PREV_DATE = LocalDate.of(2026, 7, 6);

    /**
     * The service under test. The outage sections (Priority Actions + Reasons for No Water Supply) are
     * hidden in the report by default, so their data is only computed when the flag is on.
     */
    private DailySituationReportService serviceWithOutageSections(boolean enabled) {
        return new DailySituationReportService(reportRepository, schemeRegularityRepository, enabled);
    }

    /** Mirrors {@code DailySituationReportService.istDayStartUtc}: IST day start expressed UTC-naive. */
    private static LocalDateTime istDayStartUtc(LocalDate day) {
        return day.atStartOfDay(ZoneId.of("Asia/Kolkata"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @Test
    void buildReport_computesKpisForBothDays() {
        when(schemeRegularityRepository.getSchemeCountByUser(TENANT, (int) OFFICER, null)).thenReturn(10);
        when(reportRepository.populationServed(TENANT, OFFICER, null)).thenReturn(1000L);

        // Yesterday (D-1)
        when(reportRepository.countSchemesSupplyingOnDay(TENANT, OFFICER, REPORT_DATE, null)).thenReturn(8);
        when(reportRepository.countSchemesSubmittingOnDay(TENANT, OFFICER, REPORT_DATE, null)).thenReturn(9);
        when(reportRepository.sumWaterSuppliedOnDay(TENANT, OFFICER, REPORT_DATE, null)).thenReturn(500_000L);
        when(reportRepository.sumSupplyDaysInRange(TENANT, OFFICER, REPORT_DATE.minusDays(6), REPORT_DATE, null))
                .thenReturn(50);

        // Previous day (D-2)
        when(reportRepository.countSchemesSupplyingOnDay(TENANT, OFFICER, PREV_DATE, null)).thenReturn(7);
        when(reportRepository.countSchemesSubmittingOnDay(TENANT, OFFICER, PREV_DATE, null)).thenReturn(8);
        when(reportRepository.sumWaterSuppliedOnDay(TENANT, OFFICER, PREV_DATE, null)).thenReturn(400_000L);
        when(reportRepository.sumSupplyDaysInRange(TENANT, OFFICER, PREV_DATE.minusDays(6), PREV_DATE, null))
                .thenReturn(49);

        // Anomalies: reportDate window has [5:3,4:1]; previous window empty.
        when(reportRepository.countAnomaliesByType(eq(TENANT), eq(OFFICER),
                eq(istDayStartUtc(REPORT_DATE)), eq(istDayStartUtc(REPORT_DATE.plusDays(1))), isNull()))
                .thenReturn(List.of(
                        DailyReportKpiDTO.TypeCount.builder().type("5").count(3).build(),
                        DailyReportKpiDTO.TypeCount.builder().type("4").count(1).build()));
        when(reportRepository.countAnomaliesByType(eq(TENANT), eq(OFFICER),
                eq(istDayStartUtc(PREV_DATE)), eq(istDayStartUtc(PREV_DATE.plusDays(1))), isNull()))
                .thenReturn(List.of());

        when(schemeRegularityRepository.getOutageReasonSchemeCountByUser(TENANT, (int) OFFICER, REPORT_DATE, REPORT_DATE))
                .thenReturn(List.of(new SchemeRegularityRepository.OutageReasonSchemeCount("PUMP_FAILURE", 2)));

        DailyReportKpiDTO dto = serviceWithOutageSections(true).buildReport(TENANT, OFFICER, REPORT_DATE);

        assertThat(dto.getReportDate()).isEqualTo("2026-07-07");
        assertThat(dto.getPreviousDate()).isEqualTo("2026-07-06");
        assertThat(dto.getTotalSchemes()).isEqualTo(10);

        DailyReportKpiDTO.DayKpis y = dto.getYesterday();
        assertThat(y.getSchemesSupplying()).isEqualTo(8);
        assertThat(y.getSchemesNotSupplying()).isEqualTo(2);
        assertThat(y.getAvgMld()).isCloseTo(0.5, within(0.001));       // 500000 / 1e6
        assertThat(y.getAvgLpcd()).isCloseTo(500.0, within(0.001));    // 500000 / 1000
        assertThat(y.getReadingSubmissionPct()).isCloseTo(90.0, within(0.001)); // 9/10
        assertThat(y.getRegularSupplyPctWeek()).isCloseTo(71.4, within(0.05));  // 50/(10*7)
        assertThat(y.getAnomalousCount()).isEqualTo(4);

        DailyReportKpiDTO.DayKpis p = dto.getPreviousDay();
        assertThat(p.getSchemesSupplying()).isEqualTo(7);
        assertThat(p.getAnomalousCount()).isEqualTo(0);

        assertThat(dto.getReasonsForNoSupply()).singleElement()
                .satisfies(r -> {
                    assertThat(r.getReason()).isEqualTo("PUMP_FAILURE");
                    assertThat(r.getCount()).isEqualTo(2);
                });
        assertThat(dto.getAnomaliesByType()).hasSize(2);
    }

    @Test
    void buildReport_handlesZeroPopulationAndZeroSchemesWithoutDivideByZero() {
        when(schemeRegularityRepository.getSchemeCountByUser(TENANT, (int) OFFICER, null)).thenReturn(0);
        when(reportRepository.populationServed(TENANT, OFFICER, null)).thenReturn(0L);
        when(reportRepository.countAnomaliesByType(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(schemeRegularityRepository.getOutageReasonSchemeCountByUser(any(), any(), any(), any()))
                .thenReturn(List.of());

        DailyReportKpiDTO dto = serviceWithOutageSections(true).buildReport(TENANT, OFFICER, REPORT_DATE);

        assertThat(dto.getYesterday().getAvgLpcd()).isZero();
        assertThat(dto.getYesterday().getRegularSupplyPctWeek()).isZero();
        assertThat(dto.getYesterday().getReadingSubmissionPct()).isZero();
    }

    @Test
    void buildReport_withSubordinates_populatesSectionOfficerSummaries() {
        long so1 = 601L;
        long so2 = 602L;
        // Main SDO report scaffolding (values irrelevant to this assertion). The SDO's own
        // buildReport still runs, so stub the officer-scoped calls it shares with buildOfficerSummary.
        when(reportRepository.countAnomaliesByType(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(schemeRegularityRepository.getSchemeCountByUser(TENANT, (int) OFFICER, null)).thenReturn(10);
        when(reportRepository.populationServed(TENANT, OFFICER, null)).thenReturn(1_000L);
        when(reportRepository.countSchemesSupplyingOnDay(TENANT, OFFICER, REPORT_DATE, null)).thenReturn(8);
        when(reportRepository.countSchemesSupplyingOnDay(TENANT, OFFICER, PREV_DATE, null)).thenReturn(7);

        // Per-SO Summary inputs (report day only), each scoped to the schemes shared with the SDO.
        when(schemeRegularityRepository.getSchemeCountByUser(TENANT, (int) so1, OFFICER)).thenReturn(154);
        when(reportRepository.populationServed(TENANT, so1, OFFICER)).thenReturn(1_000L);
        when(reportRepository.countSchemesSupplyingOnDay(TENANT, so1, REPORT_DATE, OFFICER)).thenReturn(148);
        when(schemeRegularityRepository.getSchemeCountByUser(TENANT, (int) so2, OFFICER)).thenReturn(90);
        when(reportRepository.populationServed(TENANT, so2, OFFICER)).thenReturn(500L);
        when(reportRepository.countSchemesSupplyingOnDay(TENANT, so2, REPORT_DATE, OFFICER)).thenReturn(80);

        DailyReportKpiDTO dto = serviceWithOutageSections(false)
                .buildReport(TENANT, OFFICER, REPORT_DATE, List.of(so1, so2));

        assertThat(dto.getSectionOfficerSummaries()).hasSize(2);
        assertThat(dto.getSectionOfficerSummaries())
                .anySatisfy(s -> {
                    assertThat(s.getOfficerUserId()).isEqualTo(so1);
                    assertThat(s.getTotalSchemes()).isEqualTo(154);
                    assertThat(s.getSchemesSupplying()).isEqualTo(148);
                    assertThat(s.getSchemesNotSupplying()).isEqualTo(6);
                })
                .anySatisfy(s -> {
                    assertThat(s.getOfficerUserId()).isEqualTo(so2);
                    assertThat(s.getSchemesNotSupplying()).isEqualTo(10);
                });
    }

    @Test
    void buildReport_sectionOfficerRows_areScopedToSchemesSharedWithTheSdo() {
        // Subordinate Section Officers are derived from schemes they share with the SDO, so their
        // breakdown rows must exclude schemes mapped to them but not to this SDO — every per-SO query
        // is issued with the SDO's user id as the supervisor. The SDO's own Summary stays unscoped.
        long so = 601L;
        when(reportRepository.countAnomaliesByType(any(), any(), any(), any(), any())).thenReturn(List.of());

        serviceWithOutageSections(false).buildReport(TENANT, OFFICER, REPORT_DATE, List.of(so));

        verify(schemeRegularityRepository).getSchemeCountByUser(TENANT, (int) so, OFFICER);
        verify(reportRepository).populationServed(TENANT, so, OFFICER);
        verify(reportRepository).countSchemesSupplyingOnDay(TENANT, so, REPORT_DATE, OFFICER);
        verify(reportRepository).countSchemesSubmittingOnDay(TENANT, so, REPORT_DATE, OFFICER);
        verify(reportRepository).sumWaterSuppliedOnDay(TENANT, so, REPORT_DATE, OFFICER);
        verify(reportRepository).sumSupplyDaysInRange(TENANT, so, REPORT_DATE.minusDays(6), REPORT_DATE, OFFICER);
        verify(reportRepository).countAnomaliesByType(eq(TENANT), eq(so), any(), any(), eq(OFFICER));

        // The SDO's own numbers cover all of the SDO's schemes — no supervisor above them.
        verify(schemeRegularityRepository).getSchemeCountByUser(TENANT, (int) OFFICER, null);
        verify(reportRepository).populationServed(TENANT, OFFICER, null);
        verify(reportRepository).countSchemesSupplyingOnDay(TENANT, OFFICER, REPORT_DATE, null);
    }

    @Test
    void buildReport_withoutSubordinates_hasEmptySectionOfficerSummaries() {
        when(reportRepository.countAnomaliesByType(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(schemeRegularityRepository.getOutageReasonSchemeCountByUser(any(), any(), any(), any()))
                .thenReturn(List.of());

        DailyReportKpiDTO dto = serviceWithOutageSections(true).buildReport(TENANT, OFFICER, REPORT_DATE);

        assertThat(dto.getSectionOfficerSummaries()).isEmpty();
    }

    @Test
    void buildReport_populatesPriorityActionsWithDaysNoSupply() {
        when(reportRepository.listNoSupplyByScheme(TENANT, OFFICER, REPORT_DATE, null)).thenReturn(List.of(
                new DailySituationReportRepository.NoSupplyScheme(7, "Pump Failure", LocalDate.of(2026, 7, 2)),
                new DailySituationReportRepository.NoSupplyScheme(9, "Pipeline Break", null)));

        DailyReportKpiDTO dto = serviceWithOutageSections(true).buildReport(TENANT, OFFICER, REPORT_DATE);

        assertThat(dto.getPriorityActions()).hasSize(2);
        assertThat(dto.getPriorityActions())
                .anySatisfy(pa -> {
                    assertThat(pa.getSchemeId()).isEqualTo(7);
                    assertThat(pa.getIssue()).isEqualTo("Pump Failure");
                    assertThat(pa.getDaysNoSupply()).isEqualTo(5);   // 2026-07-07 − 2026-07-02
                })
                .anySatisfy(pa -> {
                    assertThat(pa.getSchemeId()).isEqualTo(9);
                    assertThat(pa.getIssue()).isEqualTo("Pipeline Break");
                    assertThat(pa.getDaysNoSupply()).isNull();       // never supplied
                });
    }

    @Test
    void buildReport_withOutageSectionsDisabled_skipsTheirQueriesEntirely() {
        // Both sections are hidden in the PDF by default, so their data must not be computed at all —
        // not merely dropped downstream.
        when(reportRepository.countAnomaliesByType(any(), any(), any(), any(), any())).thenReturn(List.of());

        DailyReportKpiDTO dto = serviceWithOutageSections(false).buildReport(TENANT, OFFICER, REPORT_DATE);

        assertThat(dto.getPriorityActions()).isEmpty();
        assertThat(dto.getReasonsForNoSupply()).isEmpty();
        verify(reportRepository, never()).listNoSupplyByScheme(any(), any(), any(), any());
        verify(schemeRegularityRepository, never()).getOutageReasonSchemeCountByUser(any(), any(), any(), any());
        // The anomaly section is unaffected by the toggle.
        verify(reportRepository, org.mockito.Mockito.atLeastOnce())
                .countAnomaliesByType(any(), any(), any(), any(), any());
    }
}
