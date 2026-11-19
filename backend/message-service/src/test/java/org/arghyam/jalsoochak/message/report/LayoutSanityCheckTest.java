package org.arghyam.jalsoochak.message.report;

import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.FooterElement;
import org.arghyam.jalsoochak.message.report.layout.HeaderElement;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.arghyam.jalsoochak.message.report.layout.Spacer;
import org.arghyam.jalsoochak.message.report.layout.SummaryRowSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.arghyam.jalsoochak.message.report.ReportKind.DAILY;
import static org.arghyam.jalsoochak.message.report.ReportKind.WEEKLY;
import static org.arghyam.jalsoochak.message.report.ReportRole.SECTION_OFFICER;
import static org.arghyam.jalsoochak.message.report.ReportRole.SUB_DIVISIONAL_OFFICER;
import static org.assertj.core.api.Assertions.assertThat;

class LayoutSanityCheckTest {

    private static final ReportSection.SchemeListSection NO_SUPPLY = list(SchemeList.NO_SUPPLY,
            List.of(new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "Scheme", null)));

    @Test
    void acceptsAMinimalLayout() {
        assertThat(problems(DAILY, layout(NO_SUPPLY))).isEmpty();
    }

    @Test
    void requiresALayout() {
        assertThat(LayoutSanityCheck.problems(DAILY, SECTION_OFFICER, null)).containsExactly("layout: is required");
    }

    @Test
    void requiresASection() {
        assertThat(problems(DAILY, layout())).containsExactly("sections: at least one section is required");
    }

    @Test
    void requiresASectionTitleAndNonBlankFootnotes() {
        ReportSection.SchemeListSection untitled = new ReportSection.SchemeListSection(SchemeList.NO_SUPPLY, " ",
                NO_SUPPLY.columns(), null, List.of("ok", ""));

        assertThat(problems(DAILY, layout(untitled))).containsExactly(
                "sections[0].title: is required",
                "sections[0].footnotes[1]: is required");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        ReportLayout layout = new ReportLayout(null,
                List.of(new HeaderElement.Title("")),
                List.of(list(SchemeList.LOW_LPCD, NO_SUPPLY.columns())),
                List.of(new FooterElement.Note(null)));

        assertThat(problems(DAILY, layout)).containsExactly(
                "header[0].text: is required",
                "sections[0].list: 'LOW_LPCD' is not available in DAILY reports",
                "footer[0].text: is required");
    }

    @Nested
    class Summary {

        @Test
        void acceptsADailySummary() {
            assertThat(problems(DAILY, layout(dailySummary(row(SummaryKpi.TOTAL_SCHEMES, "Total"))))).isEmpty();
        }

        @Test
        void allowsOnlyOneSummary() {
            ReportSection summary = dailySummary(row(SummaryKpi.TOTAL_SCHEMES, "Total"));

            assertThat(problems(DAILY, layout(summary, summary)))
                    .containsExactly("sections[1]: only one SUMMARY section is allowed");
        }

        @Test
        void startsWithTheLabelColumn() {
            ReportSection summary = summary(
                    List.of(column(SummaryColumn.VALUE), column(SummaryColumn.LABEL)),
                    List.of(row(SummaryKpi.TOTAL_SCHEMES, "Total")));

            assertThat(problems(DAILY, layout(summary))).containsExactly("sections[0].columns[0].field: must be LABEL");
        }

        @Test
        void usesOnlyColumnsOfItsReport() {
            ReportSection summary = summary(
                    List.of(column(SummaryColumn.LABEL), column(SummaryColumn.TREND)),
                    List.of(row(SummaryKpi.TOTAL_SCHEMES, "Total")));

            assertThat(problems(DAILY, layout(summary)))
                    .containsExactly("sections[0].columns[1].field: 'TREND' is not available in DAILY reports");
        }

        @Test
        void usesOnlyKpisOfItsReport() {
            assertThat(problems(DAILY, layout(dailySummary(row(SummaryKpi.SCHEMES_LOW_LPCD, "Low")))))
                    .containsExactly("sections[0].rows[0].kpi: 'SCHEMES_LOW_LPCD' is not available in DAILY reports");
        }

        @Test
        void requiresARow() {
            assertThat(problems(DAILY, layout(dailySummary())))
                    .containsExactly("sections[0].rows: at least one row is required");
        }

        @Test
        void requiresEachRowsKpiAndLabel() {
            assertThat(problems(DAILY, layout(dailySummary(new SummaryRowSpec(null, " ", null))))).containsExactly(
                    "sections[0].rows[0].kpi: is required",
                    "sections[0].rows[0].label: is required");
        }

        @Test
        void showsEachKpiOnce() {
            ReportSection summary = dailySummary(
                    row(SummaryKpi.TOTAL_SCHEMES, "Total"), row(SummaryKpi.TOTAL_SCHEMES, "Total again"));

            assertThat(problems(DAILY, layout(summary)))
                    .containsExactly("sections[0].rows[1].kpi: 'TOTAL_SCHEMES' is already shown");
        }

        @Test
        void usesEachLabelOnce() {
            ReportSection summary = dailySummary(
                    row(SummaryKpi.TOTAL_SCHEMES, "Same"), row(SummaryKpi.SCHEMES_SUPPLYING, "Same"));

            assertThat(problems(DAILY, layout(summary)))
                    .containsExactly("sections[0].rows[1].label: 'Same' is already used in this summary");
        }

        @Test
        void setsDisplayOnlyOnAKpiWithAPercentage() {
            ReportSection summary = dailySummary(
                    new SummaryRowSpec(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY, "With", KpiDisplay.PERCENT),
                    new SummaryRowSpec(SummaryKpi.TOTAL_HOUSEHOLDS, "Total", KpiDisplay.COUNT));

            assertThat(problems(DAILY, layout(summary)))
                    .containsExactly("sections[0].rows[1].display: only applies to a KPI with a percentage");
        }
    }

    @Nested
    class SchemeLists {

        @Test
        void usesOnlyListsOfItsReport() {
            assertThat(problems(WEEKLY, layout(list(SchemeList.ANOMALIES, NO_SUPPLY.columns()))))
                    .containsExactly("sections[0].list: 'ANOMALIES' is not available in WEEKLY reports");
        }

        @Test
        void requiresAList() {
            assertThat(problems(DAILY, layout(list(null, NO_SUPPLY.columns()))))
                    .containsExactly("sections[0].list: is required");
        }

        @Test
        void showsEachListOnce() {
            assertThat(problems(DAILY, layout(NO_SUPPLY, NO_SUPPLY)))
                    .containsExactly("sections[1].list: 'NO_SUPPLY' is already shown");
        }

        @Test
        void showsTheAnomalyTypeOnlyInTheAnomaliesList() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(new ColumnSpec<>(SchemeColumn.ANOMALY_TYPE, "Type", null));

            assertThat(problems(DAILY, layout(list(SchemeList.ANOMALIES, columns)))).isEmpty();
            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, columns))))
                    .containsExactly("sections[0].columns[0].field: 'ANOMALY_TYPE' is not available in the NO_SUPPLY list");
        }

        @Test
        void checksPlaceholdersInTheEmptyText() {
            ReportSection section = new ReportSection.SchemeListSection(SchemeList.NO_SUPPLY, "T",
                    NO_SUPPLY.columns(), "None by {cutoff}, {weekEnd}", null);

            assertThat(problems(DAILY, layout(section)))
                    .containsExactly("sections[0].emptyText: placeholder {weekEnd} is not available in DAILY reports");
        }
    }

    @Nested
    class Columns {

        @Test
        void requiresAColumn() {
            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, List.of()))))
                    .containsExactly("sections[0].columns: at least one column is required");
        }

        @Test
        void requiresEachColumnsFieldAndLabel() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(new ColumnSpec<>(null, "", null));

            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, columns)))).containsExactly(
                    "sections[0].columns[0].field: is required",
                    "sections[0].columns[0].label: is required");
        }

        @Test
        void showsEachFieldOnce() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(
                    new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "A", null),
                    new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "B", null));

            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, columns))))
                    .containsExactly("sections[0].columns[1].field: 'SCHEME_NAME' is already shown");
        }

        @Test
        void setsWidthsOnEveryColumnOrNone() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(
                    new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "A", 60f),
                    new ColumnSpec<>(SchemeColumn.VILLAGES, "B", null));

            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, columns))))
                    .containsExactly("sections[0].columns: width must be set on every column or on none");
        }

        @ParameterizedTest
        @ValueSource(floats = {0f, -1f, 100.5f, Float.NaN, Float.POSITIVE_INFINITY})
        void keepsEachWidthAboveZeroAndAtMostOneHundred(float width) {
            List<ColumnSpec<SchemeColumn>> columns = List.of(new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "A", width));

            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, columns))))
                    .containsExactly("sections[0].columns[0].width: must be greater than 0 and at most 100");
        }
    }

    @Nested
    class OfficerPerformance {

        private final ReportSection officers = new ReportSection.OfficerPerformanceSection("Officers",
                List.of(new ColumnSpec<>(OfficerColumn.OFFICER_NAME, "Name", null)), null, null);

        @Test
        void isAllowedInTheWeeklySdoReport() {
            assertThat(LayoutSanityCheck.problems(WEEKLY, SUB_DIVISIONAL_OFFICER, layout(officers))).isEmpty();
        }

        @Test
        void isRefusedEverywhereElse() {
            String problem = "sections[0]: OFFICER_PERFORMANCE is only available in WEEKLY reports "
                    + "for SUB_DIVISIONAL_OFFICER";

            assertThat(LayoutSanityCheck.problems(WEEKLY, SECTION_OFFICER, layout(officers))).containsExactly(problem);
            assertThat(LayoutSanityCheck.problems(DAILY, SUB_DIVISIONAL_OFFICER, layout(officers)))
                    .containsExactly(problem);
            assertThat(LayoutSanityCheck.problems(WEEKLY, null, layout(officers))).containsExactly(problem);
        }

        @Test
        void allowsOnlyOneOfficerTable() {
            assertThat(LayoutSanityCheck.problems(WEEKLY, SUB_DIVISIONAL_OFFICER, layout(officers, officers)))
                    .containsExactly("sections[1]: only one OFFICER_PERFORMANCE section is allowed");
        }
    }

    @Nested
    class HeaderAndFooter {

        @Test
        void requiresEachFieldsLabelAndValue() {
            ReportLayout layout = withHeader(new HeaderElement.Field(null, " "));

            assertThat(problems(DAILY, layout)).containsExactly(
                    "header[0].label: is required",
                    "header[0].value: is required");
        }

        @Test
        void allowsABlankDashboardLinkPrefixAndSpacers() {
            ReportLayout layout = new ReportLayout(null,
                    List.of(new Spacer(), new HeaderElement.DashboardLink(null, null)),
                    List.of(NO_SUPPLY), List.of(new Spacer()));

            assertThat(problems(DAILY, layout)).isEmpty();
        }

        @Test
        void requiresTheSupportPhoneInASupportContact() {
            ReportLayout layout = withFooter(new FooterElement.SupportContact("Call us"));

            assertThat(problems(DAILY, layout)).containsExactly("footer[0].text: must contain {supportPhone}");
        }

        @Test
        void requiresASupportContactsText() {
            assertThat(problems(DAILY, withFooter(new FooterElement.SupportContact(null))))
                    .containsExactly("footer[0].text: is required");
        }
    }

    @Nested
    class Placeholders {

        @Test
        void acceptsThePlaceholdersOfItsReport() {
            assertThat(problems(DAILY, withHeader(new HeaderElement.Field("Date: ", "{reportDate} till {cutoff}"))))
                    .isEmpty();
            assertThat(problems(WEEKLY, withHeader(new HeaderElement.Subtitle("Week of {weekStart}")))).isEmpty();
        }

        @Test
        void refusesAnUnknownPlaceholder() {
            assertThat(problems(DAILY, withHeader(new HeaderElement.Title("Report for {officer}"))))
                    .containsExactly("header[0].text: unknown placeholder {officer}");
        }

        @Test
        void refusesAPlaceholderOfTheOtherReport() {
            assertThat(problems(WEEKLY, withHeader(new HeaderElement.Title("Till {cutoff}"))))
                    .containsExactly("header[0].text: placeholder {cutoff} is not available in WEEKLY reports");
        }

        @Test
        void refusesTheSupportPhoneOutsideASupportContact() {
            assertThat(problems(DAILY, withFooter(new FooterElement.Note("Call {supportPhone}"))))
                    .containsExactly("footer[0].text: placeholder {supportPhone} is only allowed in a "
                            + "SUPPORT_CONTACT footer");
        }

        @Test
        void refusesAMalformedPlaceholder() {
            assertThat(problems(DAILY, withHeader(new HeaderElement.DashboardLink("See {officerName", ""))))
                    .containsExactly("header[0].prefix: has an unmatched brace or a malformed placeholder");
        }

        @Test
        void checksColumnLabelsToo() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "{nope}", null));

            assertThat(problems(DAILY, layout(list(SchemeList.NO_SUPPLY, columns))))
                    .containsExactly("sections[0].columns[0].label: unknown placeholder {nope}");
        }
    }

    private static List<String> problems(ReportKind kind, ReportLayout layout) {
        return LayoutSanityCheck.problems(kind, SECTION_OFFICER, layout);
    }

    private static ReportLayout layout(ReportSection... sections) {
        return new ReportLayout(null, List.of(), List.of(sections), List.of());
    }

    private static ReportLayout withHeader(HeaderElement element) {
        return new ReportLayout(null, List.of(element), List.of(NO_SUPPLY), List.of());
    }

    private static ReportLayout withFooter(FooterElement element) {
        return new ReportLayout(null, List.of(), List.of(NO_SUPPLY), List.of(element));
    }

    private static ReportSection.SchemeListSection list(SchemeList list, List<ColumnSpec<SchemeColumn>> columns) {
        return new ReportSection.SchemeListSection(list, "Schemes", columns, null, null);
    }

    private static ReportSection.SummarySection dailySummary(SummaryRowSpec... rows) {
        return summary(List.of(column(SummaryColumn.LABEL), column(SummaryColumn.VALUE)), List.of(rows));
    }

    private static ReportSection.SummarySection summary(List<ColumnSpec<SummaryColumn>> columns,
                                                        List<SummaryRowSpec> rows) {
        return new ReportSection.SummarySection("Summary", columns, rows, null);
    }

    private static ColumnSpec<SummaryColumn> column(SummaryColumn field) {
        return new ColumnSpec<>(field, field.name(), null);
    }

    private static SummaryRowSpec row(SummaryKpi kpi, String label) {
        return new SummaryRowSpec(kpi, label, null);
    }
}
