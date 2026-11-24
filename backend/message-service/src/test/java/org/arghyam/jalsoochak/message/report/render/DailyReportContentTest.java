package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;
import org.arghyam.jalsoochak.message.report.layout.DateFormats;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyReportContentTest {

    private static final LocalDate GENERATED = LocalDate.of(2026, 7, 20);

    private static DailyReportKpis kpis(String cutoffIst) {
        return DailyReportKpis.builder()
                .reportDate("2026-07-19").cutoffIst(cutoffIst)
                .totalSchemes(148).schemesSupplying(142).schemesNotSupplying(6)
                .householdsWithSupply(14200).householdsWithSupplyPct(95.9)
                .householdsWithoutSupply(600).householdsWithoutSupplyPct(4.1)
                .totalHouseholds(14800).avgLpcd(63.4).anomalousCount(2)
                .build();
    }

    private static DailyReportContent.DailyReportContentBuilder content(DailyReportKpis kpis) {
        return DailyReportContent.builder()
                .kpis(kpis).officerName("Binod Nimoli").generatedDate(GENERATED)
                .formats(ReportFormats.defaults())
                .dashboardUrl("https://example.org/staff/").supportPhone("1800-000-0000");
    }

    @Test
    void isADailyReport() {
        assertThat(content(kpis(null)).build().kind()).isEqualTo(ReportKind.DAILY);
    }

    @Nested
    class Placeholders {

        @Test
        void fillsTheDailyPlaceholders() {
            DailyReportContent content = content(kpis("2026-07-19T17:30:00")).build();

            assertThat(content.placeholder(Placeholder.OFFICER_NAME)).isEqualTo("Binod Nimoli");
            assertThat(content.placeholder(Placeholder.REPORT_DATE)).isEqualTo("19 July 2026");
            assertThat(content.placeholder(Placeholder.GENERATED_DATE)).isEqualTo("20 July 2026");
            assertThat(content.placeholder(Placeholder.CUTOFF)).isEqualTo("17:30");
            assertThat(content.placeholder(Placeholder.SUPPORT_PHONE)).isEqualTo("1800-000-0000");
        }

        @Test
        void callsAnUnnamedOfficerOfficer() {
            assertThat(content(kpis(null)).officerName(null).build().placeholder(Placeholder.OFFICER_NAME))
                    .isEqualTo("Officer");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "not-a-time"})
        void fallsBackTo1600WhenTheCutoffIsMissingOrUnreadable(String cutoffIst) {
            assertThat(content(kpis(cutoffIst)).build().placeholder(Placeholder.CUTOFF)).isEqualTo("16:00");
        }

        @Test
        void printsTheFallbackCutoffInTheConfiguredTimeFormat() {
            DailyReportContent content = content(kpis(null))
                    .formats(ReportFormats.of(new DateFormats(null, null, "hh:mm a"), null)).build();

            assertThat(content.placeholder(Placeholder.CUTOFF)).isEqualTo("04:00 PM");
        }

        @Test
        void leavesTheWeeklyPlaceholdersAndAMissingPhoneBlank() {
            DailyReportContent content = content(kpis(null)).supportPhone(null).build();

            assertThat(content.placeholder(Placeholder.WEEK_START)).isEmpty();
            assertThat(content.placeholder(Placeholder.MIN_SUPPLY_DAYS)).isEmpty();
            assertThat(content.placeholder(Placeholder.SUPPORT_PHONE)).isEmpty();
        }

        @Test
        void resolvesText() {
            assertThat(content(kpis(null)).build().resolve("Today (Up to {cutoff})")).isEqualTo("Today (Up to 16:00)");
        }
    }

    @Nested
    class Summary {

        private final DailyReportContent content = content(kpis(null)).build();

        @Test
        void printsCountsWholeAndDecimalsPerTheNumberFormat() {
            assertThat(content.summaryCell(SummaryKpi.TOTAL_SCHEMES, null, SummaryColumn.VALUE)).isEqualTo("148");
            assertThat(content.summaryCell(SummaryKpi.TOTAL_HOUSEHOLDS, null, SummaryColumn.VALUE)).isEqualTo("14800");
            assertThat(content.summaryCell(SummaryKpi.AVG_LPCD, null, SummaryColumn.VALUE)).isEqualTo("63.4");
        }

        @Test
        void printsAHouseholdKpiAsTheDisplayAsks() {
            assertThat(content.summaryCell(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY, null, SummaryColumn.VALUE))
                    .isEqualTo("14200 (95.9%)");
            assertThat(content.summaryCell(SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY, KpiDisplay.COUNT_WITH_PERCENT,
                    SummaryColumn.VALUE)).isEqualTo("600 (4.1%)");
            assertThat(content.summaryCell(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY, KpiDisplay.COUNT, SummaryColumn.VALUE))
                    .isEqualTo("14200");
            assertThat(content.summaryCell(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY, KpiDisplay.PERCENT, SummaryColumn.VALUE))
                    .isEqualTo("95.9%");
        }

        @Test
        void hasNothingForAComparisonColumn() {
            assertThat(content.summaryCell(SummaryKpi.TOTAL_SCHEMES, null, SummaryColumn.TREND)).isEmpty();
        }
    }

    @Nested
    class Rows {

        @Test
        void carriesTheNoSupplyAndAnomalyListsOnly() {
            List<ReportSchemeRow> noSupply = List.of(ReportSchemeRow.builder().schemeId(1).build());
            List<ReportSchemeRow> anomalies = List.of(ReportSchemeRow.builder().schemeId(2).build());
            DailyReportContent content = content(kpis(null)).noSupplyRows(noSupply).anomalyRows(anomalies).build();

            assertThat(content.schemeRows(SchemeList.NO_SUPPLY)).contains(noSupply);
            assertThat(content.schemeRows(SchemeList.ANOMALIES)).contains(anomalies);
            assertThat(content.schemeRows(SchemeList.LOW_SUPPLY_DAYS)).isEmpty();
            assertThat(content.schemeRows(SchemeList.LOW_LPCD)).isEmpty();
            assertThat(content.officerRows()).isEmpty();
        }

        @Test
        void treatsAMissingListAsEmpty() {
            assertThat(content(kpis(null)).build().schemeRows(SchemeList.NO_SUPPLY)).contains(List.of());
        }
    }
}
