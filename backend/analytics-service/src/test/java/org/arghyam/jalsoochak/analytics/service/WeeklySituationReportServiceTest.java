package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.WeeklyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.TenantPopulationRepository;
import org.arghyam.jalsoochak.analytics.repository.WeeklySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.WeeklySituationReportRepository.SchemeWeekSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the weekly report's arithmetic: the role-dependent supply-day bars, the low-LPCD
 * band and its two deliberate exclusions, the volume-weighted average LPCD, and the SDO's
 * per-Section-Officer narrowing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklySituationReportServiceTest {

    @Mock
    private WeeklySituationReportRepository reportRepository;

    @Mock
    private TenantPopulationRepository tenantPopulationRepository;

    private WeeklySituationReportService service;

    private static final int TENANT = 1;
    private static final long OFFICER = 500L;
    private static final long SDO = 900L;
    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 1);   // Monday
    private static final LocalDate WEEK_END = LocalDate.of(2026, 6, 7);     // Sunday
    private static final LocalDate PREV_START = WEEK_START.minusDays(7);
    private static final LocalDate PREV_END = WEEK_END.minusDays(7);

    private static final String SO = "SECTION_OFFICER";
    private static final String SDO_ROLE = "SUB_DIVISIONAL_OFFICER";

    @BeforeEach
    void setUp() {
        service = new WeeklySituationReportService(reportRepository, tenantPopulationRepository, 15, 1, 4);
        when(tenantPopulationRepository.personsPerHousehold(anyInt())).thenReturn(5);
        when(reportRepository.listSchemeWeekSnapshots(anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private void givenWeek(SchemeWeekSnapshot... schemes) {
        when(reportRepository.listSchemeWeekSnapshots(TENANT, OFFICER, WEEK_START, WEEK_END, null))
                .thenReturn(List.of(schemes));
    }

    private WeeklyReportKpiDTO build(String role) {
        return service.buildReport(TENANT, OFFICER, role, WEEK_START, WEEK_END, PREV_START, PREV_END, null);
    }

    /** A scheme supplying enough litres to clear the 15 LPCD bar for its size over a week. */
    private static SchemeWeekSnapshot healthy(int id, int supplyDays) {
        // 100 households × 5 people × 7 days × 20 LPCD = 70,000 L
        return new SchemeWeekSnapshot(id, 100, supplyDays, 70_000);
    }

    @Nested
    @DisplayName("supply-day thresholds")
    class SupplyThresholds {

        @Test
        void sectionOfficerCountsASchemeSupplyingOnASingleDay() {
            givenWeek(healthy(1, 1), healthy(2, 0));

            assertThat(build(SO).getWeek().getSchemesSupplying()).isEqualTo(1);
        }

        @Test
        void subDivisionalOfficerRequiresFourDays() {
            // The same week reads differently to the two officers, on purpose: the SO asks whether a
            // scheme ran at all, the SDO whether it ran often enough.
            givenWeek(healthy(1, 3), healthy(2, 4));

            assertThat(build(SDO_ROLE).getWeek().getSchemesSupplying()).isEqualTo(1);
            assertThat(build(SO).getWeek().getSchemesSupplying()).isEqualTo(2);
        }

        @Test
        void bothRolesAgreeOnWhatNotSupplyingMeans() {
            givenWeek(healthy(1, 0), healthy(2, 1), healthy(3, 5));

            assertThat(build(SO).getWeek().getSchemesNotSupplying()).isEqualTo(1);
            assertThat(build(SDO_ROLE).getWeek().getSchemesNotSupplying()).isEqualTo(1);
        }

        @Test
        void anUnknownRoleFallsBackToTheSectionOfficerBar() {
            givenWeek(healthy(1, 1));

            assertThat(build("SOMETHING_ELSE").getWeek().getSchemesSupplying()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("scheme lists")
    class SchemeLists {

        @Test
        void listsSchemesWithNoSupplyAllWeek() {
            givenWeek(healthy(1, 0), healthy(2, 3), healthy(3, 0));

            assertThat(build(SO).getNoSupplySchemeIds()).containsExactly(1, 3);
        }

        @Test
        void listsSchemesSupplyingOneToThreeDays() {
            givenWeek(healthy(1, 0), healthy(2, 1), healthy(3, 3), healthy(4, 4));

            assertThat(build(SO).getLowSupplyDaysSchemeIds()).containsExactly(2, 3);
        }

        @Test
        void aCountAndTheListExplainingItAgree() {
            givenWeek(healthy(1, 0), healthy(2, 0), healthy(3, 5));

            WeeklyReportKpiDTO kpis = build(SO);

            assertThat(kpis.getNoSupplySchemeIds()).hasSize(kpis.getWeek().getSchemesNotSupplying());
        }
    }

    @Nested
    @DisplayName("low-LPCD band")
    class LowLpcd {

        @Test
        void includesASchemeAveragingAtOrBelowTheThreshold() {
            // 100 households × 5 × 7 days × 15 LPCD = 52,500 L is exactly at the bar, so it counts.
            givenWeek(new SchemeWeekSnapshot(1, 100, 5, 52_500), healthy(2, 5));

            WeeklyReportKpiDTO kpis = build(SO);

            assertThat(kpis.getLowLpcdSchemeIds()).containsExactly(1);
            assertThat(kpis.getWeek().getSchemesLowLpcd()).isEqualTo(1);
        }

        @Test
        void excludesASchemeThatSuppliedOnNoDay() {
            // It is already listed in full as a no-supply scheme. Repeating it here would make this
            // list read as "no supply" rather than "ran, but too weakly" — the actionable distinction.
            givenWeek(new SchemeWeekSnapshot(1, 100, 0, 0));

            WeeklyReportKpiDTO kpis = build(SO);

            assertThat(kpis.getLowLpcdSchemeIds()).isEmpty();
            assertThat(kpis.getNoSupplySchemeIds()).containsExactly(1);
        }

        @Test
        void excludesASchemeWithNoConnections() {
            // Its LPCD is undefined, not zero: there are no households for it to under-serve.
            givenWeek(new SchemeWeekSnapshot(1, 0, 5, 0));

            assertThat(build(SO).getLowLpcdSchemeIds()).isEmpty();
        }

        @Test
        void honoursAConfiguredThreshold() {
            service = new WeeklySituationReportService(reportRepository, tenantPopulationRepository, 25, 1, 4);
            givenWeek(healthy(1, 5)); // 20 LPCD — under 25, over 15

            assertThat(build(SO).getLowLpcdSchemeIds()).containsExactly(1);
        }
    }

    @Nested
    @DisplayName("average LPCD")
    class AverageLpcd {

        @Test
        void weightsBySchemeSizeRatherThanAveragingPerSchemeRates() {
            // A 10-household scheme at 100 LPCD and a 1000-household scheme at 10 LPCD average to 10.9
            // when weighted by households — not to 55, which averaging the two rates would give and
            // which would let one tiny scheme mask a whole division's shortfall.
            givenWeek(
                    new SchemeWeekSnapshot(1, 10, 7, 10 * 5 * 7 * 100),
                    new SchemeWeekSnapshot(2, 1000, 7, 1000 * 5 * 7 * 10));

            assertThat(build(SO).getWeek().getAvgLpcd()).isEqualTo(10.9);
        }

        @Test
        void dividesAcrossAllSevenDaysSoItIsComparableWithTheDailyReport() {
            givenWeek(new SchemeWeekSnapshot(1, 100, 7, 100 * 5 * 7 * 20));

            assertThat(build(SO).getWeek().getAvgLpcd()).isEqualTo(20.0);
        }

        @Test
        void usesTheFullSchemeSetUnlikeTheDailyReport() {
            // The weekly template carries no "supplying only" footnote: a scheme that never ran still
            // drags the average down, because its households still went without.
            givenWeek(
                    new SchemeWeekSnapshot(1, 100, 7, 100 * 5 * 7 * 20),
                    new SchemeWeekSnapshot(2, 100, 0, 0));

            assertThat(build(SO).getWeek().getAvgLpcd()).isEqualTo(10.0);
        }

        @Test
        void reportsZeroWhenNoSchemeHasConnections() {
            givenWeek(new SchemeWeekSnapshot(1, 0, 7, 5000));

            assertThat(build(SO).getWeek().getAvgLpcd()).isZero();
        }
    }

    @Nested
    @DisplayName("comparison week")
    class ComparisonWeek {

        @Test
        void computesBothWeeksSoThePdfCanRenderATrend() {
            givenWeek(healthy(1, 5), healthy(2, 5));
            when(reportRepository.listSchemeWeekSnapshots(TENANT, OFFICER, PREV_START, PREV_END, null))
                    .thenReturn(List.of(healthy(1, 5)));

            WeeklyReportKpiDTO kpis = build(SO);

            assertThat(kpis.getWeek().getSchemesSupplying()).isEqualTo(2);
            assertThat(kpis.getPreviousWeek().getSchemesSupplying()).isEqualTo(1);
            assertThat(kpis.getWeekStart()).isEqualTo("2026-06-01");
            assertThat(kpis.getPreviousWeekStart()).isEqualTo("2026-05-25");
        }
    }

    @Nested
    @DisplayName("Section Officer performance table")
    class OfficerBreakdown {

        @Test
        void narrowsEachOfficerRowToTheSchemesSharedWithTheSdo() {
            when(reportRepository.listSchemeWeekSnapshots(TENANT, 21L, WEEK_START, WEEK_END, SDO))
                    .thenReturn(List.of(healthy(1, 5), healthy(2, 0)));

            WeeklyReportKpiDTO kpis = service.buildReport(TENANT, SDO, SDO_ROLE,
                    WEEK_START, WEEK_END, PREV_START, PREV_END, List.of(21L));

            // The SDO's id is passed as the supervisor, so an SO's schemes outside this SDO's command
            // do not appear in this SDO's report.
            verify(reportRepository).listSchemeWeekSnapshots(TENANT, 21L, WEEK_START, WEEK_END, SDO);
            assertThat(kpis.getSectionOfficerSummaries()).singleElement()
                    .satisfies(row -> {
                        assertThat(row.getOfficerUserId()).isEqualTo(21L);
                        assertThat(row.getTotalSchemes()).isEqualTo(2);
                        assertThat(row.getSchemesSupplying()).isEqualTo(1);
                        assertThat(row.getSchemesNotSupplying()).isEqualTo(1);
                    });
        }

        @Test
        void judgesOfficerRowsOnTheSdoBarNotTheSectionOfficerBar() {
            // The row sits in the SDO's table, so it must answer the SDO's question (>= 4 days).
            when(reportRepository.listSchemeWeekSnapshots(TENANT, 21L, WEEK_START, WEEK_END, SDO))
                    .thenReturn(List.of(healthy(1, 2)));

            WeeklyReportKpiDTO kpis = service.buildReport(TENANT, SDO, SDO_ROLE,
                    WEEK_START, WEEK_END, PREV_START, PREV_END, List.of(21L));

            assertThat(kpis.getSectionOfficerSummaries()).singleElement()
                    .extracting(WeeklyReportKpiDTO.SectionOfficerWeekSummary::getSchemesSupplying)
                    .isEqualTo(0);
        }

        @Test
        void leavesTheTableEmptyForASectionOfficerReport() {
            givenWeek(healthy(1, 5));

            assertThat(build(SO).getSectionOfficerSummaries()).isEmpty();
        }

        @Test
        void theOfficersOwnTotalsAreNotNarrowed() {
            givenWeek(healthy(1, 5));

            service.buildReport(TENANT, OFFICER, SO, WEEK_START, WEEK_END, PREV_START, PREV_END, null);

            verify(reportRepository).listSchemeWeekSnapshots(
                    eq(TENANT), eq(OFFICER), eq(WEEK_START), eq(WEEK_END), isNull());
        }
    }

    @Nested
    @DisplayName("window start day")
    class WindowStartDay {

        // The reported week begins on the tenant's configured weekStartDay, so it is only
        // Monday-Sunday by default. Nothing here may depend on which weekday the window opens on.
        private static final LocalDate THU_START = LocalDate.of(2026, 6, 4);   // Thursday
        private static final LocalDate WED_END = LocalDate.of(2026, 6, 10);    // Wednesday

        @Test
        void queriesTheWindowItWasGivenWithoutSnappingToMonday() {
            service.buildReport(TENANT, OFFICER, SO, THU_START, WED_END,
                    THU_START.minusDays(7), WED_END.minusDays(7), null);

            verify(reportRepository).listSchemeWeekSnapshots(
                    eq(TENANT), eq(OFFICER), eq(THU_START), eq(WED_END), isNull());
        }

        @Test
        void producesTheSameKpisForAThursdayWeekAsForAMondayOne() {
            // Same scheme data, same seven-day span, different start day: the KPI maths must not move.
            when(reportRepository.listSchemeWeekSnapshots(TENANT, OFFICER, THU_START, WED_END, null))
                    .thenReturn(List.of(healthy(1, 5), healthy(2, 0)));
            givenWeek(healthy(1, 5), healthy(2, 0));

            WeeklyReportKpiDTO thursdayWeek = service.buildReport(TENANT, OFFICER, SO,
                    THU_START, WED_END, THU_START.minusDays(7), WED_END.minusDays(7), null);
            WeeklyReportKpiDTO mondayWeek = build(SO);

            assertThat(thursdayWeek.getWeek().getSchemesSupplying())
                    .isEqualTo(mondayWeek.getWeek().getSchemesSupplying());
            assertThat(thursdayWeek.getWeek().getAvgLpcd()).isEqualTo(mondayWeek.getWeek().getAvgLpcd());
        }

        @Test
        void echoesTheConfiguredWindowBackIntoTheKpis() {
            // message-service renders the PDF's reporting-period line from these, so they must be the
            // dates the job chose rather than anything re-derived here.
            WeeklyReportKpiDTO kpis = service.buildReport(TENANT, OFFICER, SO, THU_START, WED_END,
                    THU_START.minusDays(7), WED_END.minusDays(7), null);

            assertThat(kpis.getWeekStart()).isEqualTo(THU_START.toString());
            assertThat(kpis.getWeekEnd()).isEqualTo(WED_END.toString());
        }
    }
}
