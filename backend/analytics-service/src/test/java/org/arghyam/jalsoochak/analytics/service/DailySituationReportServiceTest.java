package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository.SchemeAnomaly;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository.SchemeDaySnapshot;
import org.arghyam.jalsoochak.analytics.repository.TenantPopulationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the daily report's arithmetic: the counts, the household split, the LPCD basis, and
 * the IST→UTC window handed to the anomaly queries.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailySituationReportServiceTest {

    @Mock
    private DailySituationReportRepository reportRepository;

    @Mock
    private TenantPopulationRepository tenantPopulationRepository;

    @InjectMocks
    private DailySituationReportService service;

    private static final int TENANT = 1;
    private static final long OFFICER = 500L;
    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);
    private static final LocalDateTime CUTOFF = DAY.atTime(16, 0);

    @BeforeEach
    void setUp() {
        when(tenantPopulationRepository.personsPerHousehold(anyInt())).thenReturn(5);
        when(reportRepository.listAnomaliesByScheme(anyInt(), anyLong(), any(), any())).thenReturn(List.of());
        when(reportRepository.countAnomalies(anyInt(), anyLong(), any(), any())).thenReturn(0);
        when(reportRepository.sumWaterSuppliedOnDay(anyInt(), anyLong(), any())).thenReturn(0L);
    }

    private void givenSchemes(SchemeDaySnapshot... schemes) {
        when(reportRepository.listSchemeDaySnapshots(TENANT, OFFICER, DAY)).thenReturn(List.of(schemes));
    }

    private DailyReportKpiDTO build() {
        return service.buildReport(TENANT, OFFICER, DAY, CUTOFF);
    }

    @Nested
    @DisplayName("scheme counts")
    class SchemeCounts {

        @Test
        void countsSupplyingAndNotSupplying() {
            givenSchemes(
                    new SchemeDaySnapshot(1, 100, true),
                    new SchemeDaySnapshot(2, 100, false),
                    new SchemeDaySnapshot(3, 100, false));

            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getTotalSchemes()).isEqualTo(3);
            assertThat(kpis.getSchemesSupplying()).isEqualTo(1);
            assertThat(kpis.getSchemesNotSupplying()).isEqualTo(2);
        }

        @Test
        void listsExactlyTheSchemesThatDidNotSupply() {
            givenSchemes(
                    new SchemeDaySnapshot(1, 100, true),
                    new SchemeDaySnapshot(2, 100, false),
                    new SchemeDaySnapshot(3, 100, false));

            // The count and the list must come from the same rows, or the report contradicts itself.
            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getNoSupplySchemeIds()).containsExactly(2, 3);
            assertThat(kpis.getNoSupplySchemeIds()).hasSize(kpis.getSchemesNotSupplying());
        }

        @Test
        void reportsZeroesForAnOfficerWithNoSchemes() {
            givenSchemes();

            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getTotalSchemes()).isZero();
            assertThat(kpis.getSchemesSupplying()).isZero();
            assertThat(kpis.getAvgLpcd()).isZero();
            assertThat(kpis.getHouseholdsWithSupplyPct()).isZero();
            assertThat(kpis.getNoSupplySchemeIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("household split")
    class Households {

        @Test
        void splitsHouseholdsByWhetherTheirSchemeSupplied() {
            givenSchemes(
                    new SchemeDaySnapshot(1, 300, true),
                    new SchemeDaySnapshot(2, 100, false));

            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getTotalHouseholds()).isEqualTo(400L);
            assertThat(kpis.getHouseholdsWithSupply()).isEqualTo(300L);
            assertThat(kpis.getHouseholdsWithoutSupply()).isEqualTo(100L);
            assertThat(kpis.getHouseholdsWithSupplyPct()).isEqualTo(75.0);
            assertThat(kpis.getHouseholdsWithoutSupplyPct()).isEqualTo(25.0);
        }

        @Test
        void roundsEachPercentageFromItsOwnCount() {
            // 1/3 and 2/3 both round up at one decimal place. Deriving the second as 100 - 33.3 would
            // print 66.7 and 33.3 summing to 100.0 only by luck; each is computed independently.
            givenSchemes(
                    new SchemeDaySnapshot(1, 100, true),
                    new SchemeDaySnapshot(2, 100, false),
                    new SchemeDaySnapshot(3, 100, false));

            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getHouseholdsWithSupplyPct()).isEqualTo(33.3);
            assertThat(kpis.getHouseholdsWithoutSupplyPct()).isEqualTo(66.7);
        }

        @Test
        void reportsZeroPercentWhenNoSchemeHasConnections() {
            givenSchemes(new SchemeDaySnapshot(1, 0, true));

            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getTotalHouseholds()).isZero();
            assertThat(kpis.getHouseholdsWithSupplyPct()).isZero();
            assertThat(kpis.getHouseholdsWithoutSupplyPct()).isZero();
        }
    }

    @Nested
    @DisplayName("average LPCD")
    class Lpcd {

        @Test
        void dividesByThePopulationOfSupplyingSchemesOnly() {
            // Per the template footnote. 300 households supplied × 5 people = 1500; 300000 L / 1500 =
            // 200 LPCD. Over the full 400 households (2000 people) it would read 150 — a different
            // question, and not the one the template asks.
            givenSchemes(
                    new SchemeDaySnapshot(1, 300, true),
                    new SchemeDaySnapshot(2, 100, false));
            when(reportRepository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY)).thenReturn(300_000L);

            assertThat(build().getAvgLpcd()).isEqualTo(200.0);
        }

        @Test
        void usesTheTenantHouseholdSize() {
            givenSchemes(new SchemeDaySnapshot(1, 100, true));
            when(tenantPopulationRepository.personsPerHousehold(TENANT)).thenReturn(4);
            when(reportRepository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY)).thenReturn(40_000L);

            assertThat(build().getAvgLpcd()).isEqualTo(100.0);
        }

        @Test
        void reportsZeroWhenNoSchemeSupplied() {
            // Denominator is zero, not the full population: no division, and no report-breaking error.
            givenSchemes(new SchemeDaySnapshot(1, 100, false));
            when(reportRepository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY)).thenReturn(0L);

            assertThat(build().getAvgLpcd()).isZero();
        }

        @Test
        void roundsToOneDecimalPlace() {
            givenSchemes(new SchemeDaySnapshot(1, 100, true));
            when(reportRepository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY)).thenReturn(25_678L);

            // 25678 / 500 = 51.356
            assertThat(build().getAvgLpcd()).isEqualTo(51.4);
        }
    }

    @Nested
    @DisplayName("anomaly window")
    class AnomalyWindow {

        @Test
        void convertsTheIstWindowToTheUtcInstantsAnomaliesAreStoredIn() {
            givenSchemes(new SchemeDaySnapshot(1, 100, true));

            build();

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(reportRepository).countAnomalies(eq(TENANT), eq(OFFICER), from.capture(), to.capture());

            // IST is UTC+5:30, so 00:00 IST is 18:30 UTC the previous day and the 16:00 cut-off is 10:30 UTC.
            assertThat(from.getValue()).isEqualTo(DAY.minusDays(1).atTime(18, 30));
            assertThat(to.getValue()).isEqualTo(DAY.atTime(10, 30));
        }

        @Test
        void coversTheWholeDayWhenNoCutoffIsGiven() {
            givenSchemes(new SchemeDaySnapshot(1, 100, true));

            service.buildReport(TENANT, OFFICER, DAY, null);

            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(reportRepository).countAnomalies(eq(TENANT), eq(OFFICER), any(), to.capture());

            assertThat(to.getValue()).isEqualTo(DAY.atTime(18, 30));
        }

        @Test
        void carriesThePerSchemeAnomalyRows() {
            givenSchemes(new SchemeDaySnapshot(1, 100, true));
            when(reportRepository.listAnomaliesByScheme(anyInt(), anyLong(), any(), any()))
                    .thenReturn(List.of(new SchemeAnomaly(1, "UNREADABLE_IMAGE"),
                            new SchemeAnomaly(1, "5")));
            when(reportRepository.countAnomalies(anyInt(), anyLong(), any(), any())).thenReturn(7);

            DailyReportKpiDTO kpis = build();

            assertThat(kpis.getAnomalousCount()).isEqualTo(7);
            assertThat(kpis.getSchemeAnomalies())
                    .extracting(DailyReportKpiDTO.SchemeAnomaly::getSchemeId,
                            DailyReportKpiDTO.SchemeAnomaly::getType)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "UNREADABLE_IMAGE"),
                            org.assertj.core.groups.Tuple.tuple(1, "5"));
        }
    }

    @Test
    void reportsTheWindowItActuallyApplied() {
        // The PDF renders its "Reporting Period" line from this, so it must be the cut-off used rather
        // than a hard-coded 16:00.
        givenSchemes(new SchemeDaySnapshot(1, 100, true));

        DailyReportKpiDTO kpis = service.buildReport(TENANT, OFFICER, DAY, DAY.atTime(17, 30));

        assertThat(kpis.getReportDate()).isEqualTo("2026-06-10");
        assertThat(kpis.getCutoffIst()).isEqualTo("2026-06-10T17:30");
    }
}
