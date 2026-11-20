package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyReportContentTest {

    private static final LocalDate GENERATED = LocalDate.of(2026, 7, 20);

    private static WeeklyReportKpis.WeekKpis week(int supplying, int notSupplying, double avgLpcd) {
        return WeeklyReportKpis.WeekKpis.builder().totalSchemes(148).schemesSupplying(supplying)
                .schemesNotSupplying(notSupplying).schemesLowLpcd(4).avgLpcd(avgLpcd).build();
    }

    /** Thursday 16 to Wednesday 22 July 2026, and the week before it. */
    private static WeeklyReportKpis.WeeklyReportKpisBuilder kpis() {
        return WeeklyReportKpis.builder()
                .weekStart("2026-07-16").weekEnd("2026-07-22")
                .previousWeekStart("2026-07-09").previousWeekEnd("2026-07-15")
                .week(week(142, 6, 63.4)).previousWeek(week(140, 8, 61.2));
    }

    private static WeeklyReportContent.WeeklyReportContentBuilder content(WeeklyReportKpis kpis) {
        return WeeklyReportContent.builder()
                .kpis(kpis).officerName("Kavita Rao").generatedDate(GENERATED)
                .formats(ReportFormats.defaults()).supportPhone("1800-000-0000");
    }

    @Test
    void isAWeeklyReport() {
        assertThat(content(kpis().build()).build().kind()).isEqualTo(ReportKind.WEEKLY);
    }

    @Nested
    class Placeholders {

        @Test
        void fillsTheWeeklyPlaceholders() {
            WeeklyReportContent content = content(kpis().build()).build();

            assertThat(content.placeholder(Placeholder.OFFICER_NAME)).isEqualTo("Kavita Rao");
            assertThat(content.placeholder(Placeholder.GENERATED_DATE)).isEqualTo("20 July 2026");
            assertThat(content.placeholder(Placeholder.WEEK_START)).isEqualTo("16-Jul-2026");
            assertThat(content.placeholder(Placeholder.WEEK_END)).isEqualTo("22-Jul-2026");
            assertThat(content.placeholder(Placeholder.PREVIOUS_WEEK_START)).isEqualTo("09-Jul-2026");
            assertThat(content.placeholder(Placeholder.PREVIOUS_WEEK_END)).isEqualTo("15-Jul-2026");
            assertThat(content.placeholder(Placeholder.SUPPORT_PHONE)).isEqualTo("1800-000-0000");
        }

        @Test
        void namesTheDaysTheWeekActuallyStartsAndEndsOn() {
            WeeklyReportContent content = content(kpis().build()).build();

            assertThat(content.placeholder(Placeholder.WEEK_START_DAY_NAME)).isEqualTo("Thursday");
            assertThat(content.placeholder(Placeholder.WEEK_END_DAY_NAME)).isEqualTo("Wednesday");
        }

        @Test
        void callsAnUnnamedOfficerOfficer() {
            assertThat(content(kpis().build()).officerName(null).build().placeholder(Placeholder.OFFICER_NAME))
                    .isEqualTo("Officer");
        }

        @Test
        void leavesAMissingPreviousWeekBlank() {
            WeeklyReportContent content = content(kpis().previousWeekStart(null).previousWeekEnd(" ").build()).build();

            assertThat(content.placeholder(Placeholder.PREVIOUS_WEEK_START)).isEmpty();
            assertThat(content.placeholder(Placeholder.PREVIOUS_WEEK_END)).isEmpty();
        }

        @Test
        void leavesTheDailyPlaceholdersAndTheThresholdsItHasNoValueForBlank() {
            WeeklyReportContent content = content(kpis().build()).build();

            assertThat(content.placeholder(Placeholder.CUTOFF)).isEmpty();
            assertThat(content.placeholder(Placeholder.REPORT_DATE)).isEmpty();
            assertThat(content.placeholder(Placeholder.LOW_LPCD_THRESHOLD)).isEmpty();
            assertThat(content.placeholder(Placeholder.MIN_SUPPLY_DAYS)).isEmpty();
        }
    }

    @Nested
    class Summary {

        private final WeeklyReportContent content = content(kpis().build()).build();

        @Test
        void comparesTheWeekWithTheOneBefore() {
            assertThat(content.summaryCell(SummaryKpi.SCHEMES_SUPPLYING, null, SummaryColumn.CURRENT)).isEqualTo("142");
            assertThat(content.summaryCell(SummaryKpi.SCHEMES_SUPPLYING, null, SummaryColumn.PREVIOUS)).isEqualTo("140");
            assertThat(content.summaryCell(SummaryKpi.SCHEMES_SUPPLYING, null, SummaryColumn.TREND)).isEqualTo("▲ +2");
            assertThat(content.summaryCell(SummaryKpi.SCHEMES_NOT_SUPPLYING, null, SummaryColumn.TREND))
                    .isEqualTo("▼ -2");
            assertThat(content.summaryCell(SummaryKpi.TOTAL_SCHEMES, null, SummaryColumn.TREND)).isEqualTo("—");
        }

        @Test
        void printsTheAverageLpcdAndItsChangeToOnePlace() {
            assertThat(content.summaryCell(SummaryKpi.AVG_LPCD, null, SummaryColumn.CURRENT)).isEqualTo("63.4");
            assertThat(content.summaryCell(SummaryKpi.AVG_LPCD, null, SummaryColumn.PREVIOUS)).isEqualTo("61.2");
            assertThat(content.summaryCell(SummaryKpi.AVG_LPCD, null, SummaryColumn.TREND)).isEqualTo("▲ +2.2");
        }

        @Test
        void readsAMissingWeekAsZeros() {
            WeeklyReportContent sparse = content(kpis().week(null).previousWeek(null).build()).build();

            assertThat(sparse.summaryCell(SummaryKpi.AVG_LPCD, null, SummaryColumn.CURRENT)).isEqualTo("0");
            assertThat(sparse.summaryCell(SummaryKpi.TOTAL_SCHEMES, null, SummaryColumn.TREND)).isEqualTo("—");
        }

        @Test
        void hasNothingForTheDailyValueColumn() {
            assertThat(content.summaryCell(SummaryKpi.TOTAL_SCHEMES, null, SummaryColumn.VALUE)).isEmpty();
        }
    }

    @Nested
    class Rows {

        @Test
        void carriesTheThreeWeeklyListsAndTheOfficerRows() {
            List<ReportSchemeRow> noSupply = List.of(ReportSchemeRow.builder().schemeId(1).build());
            List<ReportSchemeRow> lowDays = List.of(ReportSchemeRow.builder().schemeId(2).build());
            List<ReportSchemeRow> lowLpcd = List.of(ReportSchemeRow.builder().schemeId(3).build());
            List<WeeklyReportOfficerRow> officers = List.of(WeeklyReportOfficerRow.builder().name("Alice").build());
            WeeklyReportContent content = content(kpis().build()).noSupplyRows(noSupply).lowSupplyDaysRows(lowDays)
                    .lowLpcdRows(lowLpcd).officerRows(officers).build();

            assertThat(content.schemeRows(SchemeList.NO_SUPPLY)).contains(noSupply);
            assertThat(content.schemeRows(SchemeList.LOW_SUPPLY_DAYS)).contains(lowDays);
            assertThat(content.schemeRows(SchemeList.LOW_LPCD)).contains(lowLpcd);
            assertThat(content.schemeRows(SchemeList.ANOMALIES)).isEmpty();
            assertThat(content.officerRows()).contains(officers);
        }

        @Test
        void treatsMissingRowsAsEmpty() {
            WeeklyReportContent content = content(kpis().build()).build();

            assertThat(content.schemeRows(SchemeList.LOW_LPCD)).contains(List.of());
            assertThat(content.officerRows()).contains(List.of());
        }
    }
}
