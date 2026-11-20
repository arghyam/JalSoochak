package org.arghyam.jalsoochak.message.report.render;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.report.DefaultReportLayouts;
import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class LayoutPdfRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultReportLayouts LAYOUTS = new DefaultReportLayouts(MAPPER);

    private final LayoutPdfRenderer renderer = new LayoutPdfRenderer();

    private static final String SUMMARY = """
            {"type": "SUMMARY", "title": "Summary",
             "columns": [{"field": "LABEL", "label": "KPI"}, {"field": "VALUE", "label": "Today"}],
             "rows": [{"kpi": "TOTAL_SCHEMES", "label": "Total"}]}""";
    private static final String NO_SUPPLY_LIST = """
            {"type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "No supply",
             "columns": [{"field": "SERIAL_NO", "label": "#"}, {"field": "SCHEME_NAME", "label": "Scheme"}]}""";
    private static final String OFFICER_TABLE = """
            {"type": "OFFICER_PERFORMANCE", "title": "Officers",
             "columns": [{"field": "OFFICER_NAME", "label": "Name"}]}""";

    @Nested
    class Sections {

        @Test
        void numbersSectionsOverTheOnesActuallyDrawn() throws IOException {
            // The officer table has no rows in a daily report, so it is left out and "No supply" is 2.
            String text = text(layout("{\"sections\": [" + SUMMARY + "," + OFFICER_TABLE + "," + NO_SUPPLY_LIST + "]}"),
                    daily(List.of()));

            assertThat(text).contains("1. Summary", "2. No supply").doesNotContain("Officers");
        }

        @Test
        void leavesTitlesUnnumberedWhenTheLayoutSaysSo() throws IOException {
            String text = text(layout("{\"numberSections\": false, \"sections\": [" + SUMMARY + "]}"),
                    daily(List.of()));

            assertThat(text).contains("Summary").doesNotContain("1. Summary");
        }

        @Test
        void skipsAListTheReportDoesNotCarry() {
            ReportSection lowLpcd = section("""
                    {"type": "SCHEME_LIST", "list": "LOW_LPCD", "title": "Low LPCD",
                     "columns": [{"field": "SCHEME_NAME", "label": "Scheme"}]}""");

            assertThat(LayoutPdfRenderer.prepare(lowLpcd, 0, daily(List.of()))).isEmpty();
        }

        @Test
        void leavesOutTheSummaryRowsAndColumnsTheReportHasNoValueFor() {
            ReportSection summary = section("""
                    {"type": "SUMMARY", "title": "Summary",
                     "columns": [{"field": "LABEL", "label": "KPI"}, {"field": "VALUE", "label": "Today"},
                                 {"field": "TREND", "label": "Trend"}],
                     "rows": [{"kpi": "TOTAL_SCHEMES", "label": "Total"},
                              {"kpi": "SCHEMES_LOW_LPCD", "label": "Low LPCD"}]}""");

            LayoutPdfRenderer.Table table = LayoutPdfRenderer.prepare(summary, 0, daily(List.of())).orElseThrow().table();

            assertThat(table.header()).containsExactly("KPI", "Today");
            assertThat(table.rows()).containsExactly(new String[]{"Total", "148"});
        }

        @Test
        void skipsASectionThatFailsToBuildAndDrawsTheRest() throws IOException {
            DailyReportContent failing = spy(daily(List.of()));
            doThrow(new IllegalStateException("boom")).when(failing).summaryCell(any(), any(), any());

            String text = text(layout("{\"sections\": [" + SUMMARY + "," + NO_SUPPLY_LIST + "]}"), failing);

            assertThat(text).contains("1. No supply").doesNotContain("Summary");
        }

        @Test
        void resolvesTheTitleColumnLabelsAndFootnotes() {
            ReportSection summary = section("""
                    {"type": "SUMMARY", "title": "Up to {cutoff}",
                     "columns": [{"field": "LABEL", "label": "KPI"}, {"field": "VALUE", "label": "Till {cutoff}"}],
                     "rows": [{"kpi": "TOTAL_SCHEMES", "label": "Total at {cutoff}"}],
                     "footnotes": ["As of {cutoff}"]}""");

            LayoutPdfRenderer.Section prepared = LayoutPdfRenderer.prepare(summary, 0, daily(List.of())).orElseThrow();

            assertThat(prepared.title()).isEqualTo("Up to 16:00");
            assertThat(prepared.table().header()).containsExactly("KPI", "Till 16:00");
            assertThat(prepared.table().rows().get(0)[0]).isEqualTo("Total at 16:00");
            assertThat(prepared.footnotes()).containsExactly("As of 16:00");
        }
    }

    @Nested
    class Lists {

        @Test
        void numbersRowsFromOne() {
            List<ReportSchemeRow> rows = List.of(
                    ReportSchemeRow.builder().schemeId(7).schemeName("Rampur").build(),
                    ReportSchemeRow.builder().schemeId(9).schemeName("Kishanganj").build());

            LayoutPdfRenderer.Table table =
                    LayoutPdfRenderer.prepare(section(NO_SUPPLY_LIST), 0, daily(rows)).orElseThrow().table();

            assertThat(table.rows()).containsExactly(new String[]{"1", "Rampur"}, new String[]{"2", "Kishanganj"});
            assertThat(table.centred()).containsExactly(false, false);
        }

        @Test
        void saysTheEmptyTextInTheSchemeNameColumnOfAnEmptyList() {
            ReportSection list = section("""
                    {"type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "No supply", "emptyText": "All supplied",
                     "columns": [{"field": "SERIAL_NO", "label": "#"}, {"field": "SCHEME_NAME", "label": "Scheme"},
                                 {"field": "PUMP_OPERATOR_NAMES", "label": "Operator"}]}""");

            LayoutPdfRenderer.Table table = LayoutPdfRenderer.prepare(list, 0, daily(List.of())).orElseThrow().table();

            assertThat(table.rows()).containsExactly(new String[]{"", "All supplied", ""});
        }

        @Test
        void putsTheEmptyTextInTheFirstColumnWhenThereIsNoNameColumn() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(
                    new ColumnSpec<>(SchemeColumn.CENTRE_SCHEME_ID, "IMIS", null),
                    new ColumnSpec<>(SchemeColumn.VILLAGES, "Villages", null));

            assertThat(LayoutPdfRenderer.emptyRow(columns, SchemeColumn.SCHEME_NAME, "None"))
                    .containsExactly("None", "");
        }

        @Test
        void putsTheOfficerTablesEmptyTextInTheNameColumn() {
            List<ColumnSpec<OfficerColumn>> columns = List.of(
                    new ColumnSpec<>(OfficerColumn.SERIAL_NO, "#", null),
                    new ColumnSpec<>(OfficerColumn.OFFICER_NAME, "Name", null));

            assertThat(LayoutPdfRenderer.emptyRow(columns, OfficerColumn.OFFICER_NAME, "None"))
                    .containsExactly("", "None");
        }
    }

    @Nested
    class TrendColouring {

        @Test
        void invertsTheColourOfEveryNegativeKpiRowByItsResolvedLabel() {
            ReportLayout daily = LAYOUTS.builtIn(ReportKind.DAILY, "SECTION_OFFICER").orElseThrow();
            ReportLayout sdo = LAYOUTS.builtIn(ReportKind.WEEKLY, "SUB_DIVISIONAL_OFFICER").orElseThrow();

            assertThat(LayoutPdfRenderer.negativeLabels(daily, daily(List.of()))).containsExactlyInAnyOrder(
                    "Schemes Not having supplied Water (Till 16:00)",
                    "Number of HHs yet to get water supply",
                    "Number of Anomalous Submissions");
            assertThat(LayoutPdfRenderer.negativeLabels(sdo, weekly())).containsExactlyInAnyOrder(
                    "Schemes Not Supplying Water (0 days in the week)",
                    "Schemes With Supply <=15 LPCD (average)");
        }
    }

    @Nested
    class HeaderAndFooter {

        private final ReportLayout layout = layout("""
                {"header": [{"type": "TITLE", "text": "Report for {officerName}"},
                            {"type": "DASHBOARD_LINK", "prefix": "See ", "suffix": " now"}],
                 "sections": [%s],
                 "footer": [{"type": "NOTE", "text": "Generated {generatedDate}"},
                            {"type": "SUPPORT_CONTACT", "text": "Call {supportPhone}"}]}""".formatted(SUMMARY));

        @Test
        void drawsTheDashboardLinkAndSupportContactWhenSet() throws IOException {
            try (PDDocument doc = render(layout, daily(List.of()))) {
                assertThat(text(doc)).contains("Report for Binod", "See https://example.org/staff/ now",
                        "Generated 20 July 2026", "Call 1800-000-0000");
                assertThat(doc.getPage(0).getAnnotations()).singleElement().isInstanceOf(PDAnnotationLink.class);
            }
        }

        @Test
        void leavesOutTheDashboardLinkWhenTheUrlIsBlank() throws IOException {
            try (PDDocument doc = render(layout, dailyContent(List.of()).dashboardUrl(" ").build())) {
                assertThat(text(doc)).doesNotContain("See");
                assertThat(doc.getPage(0).getAnnotations()).isEmpty();
            }
        }

        @Test
        void leavesOutTheSupportContactButNotANoteWhenThePhoneIsBlank() throws IOException {
            String text = text(layout, dailyContent(List.of()).supportPhone("").build());

            assertThat(text).contains("Generated 20 July 2026").doesNotContain("Call");
        }
    }

    // ---- fixtures -------------------------------------------------------------

    private static DailyReportContent.DailyReportContentBuilder dailyContent(List<ReportSchemeRow> noSupply) {
        return DailyReportContent.builder()
                .kpis(DailyReportKpis.builder().reportDate("2026-07-19").totalSchemes(148).build())
                .officerName("Binod").generatedDate(LocalDate.of(2026, 7, 20))
                .formats(ReportFormats.defaults())
                .dashboardUrl("https://example.org/staff/").supportPhone("1800-000-0000")
                .noSupplyRows(noSupply);
    }

    private static DailyReportContent daily(List<ReportSchemeRow> noSupply) {
        return dailyContent(noSupply).build();
    }

    private static WeeklyReportContent weekly() {
        return WeeklyReportContent.builder()
                .kpis(WeeklyReportKpis.builder().weekStart("2026-07-13").weekEnd("2026-07-19").build())
                .generatedDate(LocalDate.of(2026, 7, 20)).formats(ReportFormats.defaults())
                .build();
    }

    private static ReportLayout layout(String json) {
        try {
            return MAPPER.readValue(json, ReportLayout.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ReportSection section(String json) {
        try {
            return MAPPER.readValue(json, ReportSection.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private PDDocument render(ReportLayout layout, ReportContent content) throws IOException {
        PDDocument doc = new PDDocument();
        renderer.render(doc, layout, content);
        return doc;
    }

    private String text(ReportLayout layout, ReportContent content) throws IOException {
        try (PDDocument doc = render(layout, content)) {
            return text(doc);
        }
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
    }
}
