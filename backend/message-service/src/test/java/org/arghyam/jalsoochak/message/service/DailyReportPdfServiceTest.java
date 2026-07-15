package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DailyReportPdfService} — verifies the SECTION_OFFICER report renders a valid,
 * non-empty PDF with the Summary/Priority/Reasons/Anomalies sections, two-line column headers,
 * trend arrows/dash, correct anomaly labels, and Priority Actions rows.
 */
class DailyReportPdfServiceTest {

    @TempDir
    Path tempDir;

    private DailyReportPdfService service;

    @BeforeEach
    void setUp() {
        service = new DailyReportPdfService();
        ReflectionTestUtils.setField(service, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(service, "dashboardUrl", "https://jalsoochak.jjmbrain.in/");
        ReflectionTestUtils.setField(service, "supportPhone", "919999999999");
    }

    private DailyReportKpis sampleKpis() {
        return DailyReportKpis.builder()
                .reportDate("2026-07-07")
                .previousDate("2026-07-06")
                .totalSchemes(148)
                .yesterday(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(142).schemesNotSupplying(6)
                        .avgLpcd(63).avgMld(18.4)
                        .regularSupplyPctWeek(92).readingSubmissionPct(97)
                        .anomalousCount(11).build())
                .previousDay(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(140).schemesNotSupplying(8)
                        .avgLpcd(61).avgMld(18.1)
                        .regularSupplyPctWeek(97).readingSubmissionPct(96)
                        .anomalousCount(15).build())
                .reasonsForNoSupply(List.of(
                        DailyReportKpis.ReasonCount.builder().reason("PUMP_FAILURE").count(4).build(),
                        DailyReportKpis.ReasonCount.builder().reason("Pipeline Break").count(3).build()))
                .anomaliesByType(List.of(
                        DailyReportKpis.TypeCount.builder().type("NO_SUBMISSION").count(3).build(),
                        DailyReportKpis.TypeCount.builder().type("5").count(1).build()))
                .build();
    }

    private List<DailyReportPriorityRow> samplePriority() {
        return List.of(
                DailyReportPriorityRow.builder()
                        .scheme("Rampur WSS").imisId("IMIS-108")
                        .jalMitraNames("Ramesh Kumar").jalMitraMobiles("919000000001")
                        .issue("Pump Failure").remarks("No water supply for past 7 days").build(),
                DailyReportPriorityRow.builder()
                        .scheme("Sitapur WSS").imisId("IMIS-214")
                        .jalMitraNames("Suresh Rao, Mahesh Rao").jalMitraMobiles("919000000002, 919000000003")
                        .issue("Electricity Supply Disconnected").remarks("No water supply for past 6 days").build());
    }

    @Test
    void generate_createsValidPdfWithExpectedFilename() throws Exception {
        String filename = service.generate(sampleKpis(), "Binod Nimoli", "SECTION_OFFICER", samplePriority());

        assertThat(filename)
                .startsWith("daily_report_SECTION_OFFICER_Binod_Nimoli_2026-07-07")
                .endsWith(".pdf");
        Path pdf = tempDir.resolve(filename);
        assertThat(pdf.toFile()).exists().isFile();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void generate_pdfContainsAllFourSectionsAndTwoLineHeaders() throws Exception {
        String text = renderText(sampleKpis(), samplePriority());

        assertThat(text).contains("Daily Water Service Situation Report");
        assertThat(text).contains("Officer: Binod Nimoli");
        assertThat(text).contains("1. Summary");
        assertThat(text).contains("2. Priority Actions");
        assertThat(text).contains("3. Reasons for No Water Supply");
        assertThat(text).contains("4. Anomalous Submissions");
        // Two-line column headers: label and its date appear as separate tokens.
        assertThat(text).contains("Yesterday");
        assertThat(text).contains("(07-Jul-2026)");
        assertThat(text).contains("Previous Day");
        assertThat(text).contains("(06-Jul-2026)");
    }

    @Test
    void generate_trendShowsDashForNoChangeAndArrowsForChanges() throws Exception {
        String text = renderText(sampleKpis(), samplePriority());

        // Total Schemes 148 vs 148 → no change → em dash
        assertThat(text).contains("—");
        // Supplying 142 vs 140 → up +2 ; Not-supplying 6 vs 8 → down -2
        assertThat(text).contains("▲");
        assertThat(text).contains("▼");
        assertThat(text).contains("+2");
        assertThat(text).contains("-2");
        // Average MLD 18.4 vs 18.1 → +0.3, rounded (no floating-point long-decimal leak).
        assertThat(text).contains("+0.3");
        assertThat(text).doesNotContain("0.29999");
    }

    @Test
    void generate_mapsAnomalyNameAndLegacyCodeToFriendlyLabel() throws Exception {
        String text = renderText(sampleKpis(), samplePriority());

        assertThat(text).contains("No Submission");             // "NO_SUBMISSION" name mapped
        assertThat(text).doesNotContain("Type NO_SUBMISSION");  // the old bug is gone
        assertThat(text).contains("Reading Less Than Previous"); // legacy numeric "5" mapped
    }

    @Test
    void generate_rendersPriorityActionRows() throws Exception {
        // Narrow Priority Actions cells wrap, so assert on single tokens (no internal spaces),
        // which survive line-wrapping in the extracted text.
        String text = renderText(sampleKpis(), samplePriority());

        assertThat(text).contains("Rampur");           // scheme name
        assertThat(text).contains("IMIS-108");         // IMIS id (single token)
        assertThat(text).contains("Ramesh");           // jal mitra name
        assertThat(text).contains("919000000001");     // jal mitra mobile (single token)
        assertThat(text).contains("Pump");             // issue
        assertThat(text).contains("Electricity");      // second row issue
        assertThat(text).contains("days");             // remarks duration
    }

    @Test
    void generate_handlesEmptyPriorityReasonAndAnomalyLists() throws Exception {
        DailyReportKpis kpis = sampleKpis();
        kpis.setReasonsForNoSupply(List.of());
        kpis.setAnomaliesByType(List.of());

        String text = renderText(kpis, List.of()).replaceAll("\\s+", " ");

        assertThat(text).contains("No priority actions");   // wide/narrow-cell empty states
        assertThat(text).contains("No outages reported");
        assertThat(text).contains("No anomalies");
    }

    private String renderText(DailyReportKpis kpis, List<DailyReportPriorityRow> priority) throws Exception {
        String filename = service.generate(kpis, "Binod Nimoli", "SECTION_OFFICER", priority);
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
