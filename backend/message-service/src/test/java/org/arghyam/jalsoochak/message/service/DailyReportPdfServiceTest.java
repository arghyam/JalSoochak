package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.arghyam.jalsoochak.message.dto.DailyReportSectionOfficerRow;
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

    private List<DailyReportSectionOfficerRow> sampleSectionOfficers() {
        return List.of(
                DailyReportSectionOfficerRow.builder()
                        .officerName("Alice").officerMobile("919868595001")
                        .totalSchemes(154).schemesSupplying(148).schemesNotSupplying(6)
                        .avgLpcd(67).avgMld(678).regularSupplyPctWeek(32).readingSubmissionPct(78)
                        .anomalousCount(8).build(),
                DailyReportSectionOfficerRow.builder()
                        .officerName("Bob").officerMobile("919868595002")
                        .totalSchemes(90).schemesSupplying(80).schemesNotSupplying(10)
                        .avgLpcd(55).avgMld(400).regularSupplyPctWeek(60).readingSubmissionPct(88)
                        .anomalousCount(2).build());
    }

    @Test
    void generate_createsValidPdfWithExpectedFilename() throws Exception {
        String filename = service.generate(sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", samplePriority(), List.of());

        assertThat(filename)
                .startsWith("daily_report_SECTION_OFFICER_500_2026-07-07")
                .endsWith(".pdf");
        Path pdf = tempDir.resolve(filename);
        assertThat(pdf.toFile()).exists().isFile();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void generate_twoOfficersWithSameNameAndRole_produceDistinctFilenames() throws Exception {
        // Regression for the PII cross-exposure blocker: the filename → MinIO object key must be
        // unique per officer. Two Section Officers with the identical display name and report date
        // must NOT collapse to the same object key (which would overwrite and misdeliver one's PDF).
        String fileA = service.generate(sampleKpis(), 601L, "Sunil Kumar", "SECTION_OFFICER", samplePriority(), List.of());
        String fileB = service.generate(sampleKpis(), 602L, "Sunil Kumar", "SECTION_OFFICER", samplePriority(), List.of());

        assertThat(fileA).isNotEqualTo(fileB);
        assertThat(fileA).contains("_601_");
        assertThat(fileB).contains("_602_");
        assertThat(tempDir.resolve(fileA).toFile()).exists();
        assertThat(tempDir.resolve(fileB).toFile()).exists();
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

    @Test
    void generate_sdoReport_rendersSectionOfficerBreakdownTableFirstInSummary() throws Exception {
        String filename = service.generate(
                sampleKpis(), 700L, "SDO Kumar", "SUB_DIVISIONAL_OFFICER", samplePriority(), sampleSectionOfficers());
        assertThat(filename).startsWith("daily_report_SUB_DIVISIONAL_OFFICER_700_2026-07-07");

        String text;
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        // Extra breakdown table + its officers appear, alongside the normal SO sections.
        assertThat(text).contains("Section Officer");   // breakdown header token
        assertThat(text).contains("Alice");
        assertThat(text).contains("Bob");
        assertThat(text).contains("1. Summary");
        assertThat(text).contains("2. Priority Actions");
        // The breakdown table is drawn before the KPI summary table: the first officer name
        // precedes the "Yesterday" summary-table header in the extracted text.
        assertThat(text.indexOf("Alice")).isLessThan(text.indexOf("Yesterday"));
    }

    @Test
    void generate_sdoReport_mobileNumberFitsOnOneLineInSummaryTable() throws Exception {
        // Regression: the dense SDO per-Section-Officer summary table used the default 6pt cell
        // padding, which clipped the 12th digit of a mobile number to a second line
        // ("91986859500"+"1"). With the tighter padding the full 12-digit number stays intact, so
        // PDFTextStripper extracts it as one contiguous token.
        String filename = service.generate(
                sampleKpis(), 700L, "SDO Kumar", "SUB_DIVISIONAL_OFFICER", samplePriority(), sampleSectionOfficers());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("919868595001");   // Alice — 12 digits, unbroken
            assertThat(text).contains("919868595002");   // Bob   — 12 digits, unbroken
        }
    }

    @Test
    void generate_dashboardUrlIsEmbeddedAsClickableLinkAnnotation() throws Exception {
        // The dashboard URL must be a real PDF link annotation (not just plain text) so it is
        // clickable in every viewer — including WhatsApp/mobile viewers that don't auto-detect
        // bare URLs (browsers and the MinIO preview do, which is why it "worked" only there before).
        String filename = service.generate(
                sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", samplePriority(), List.of());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            List<PDAnnotation> annotations = doc.getPage(0).getAnnotations();
            boolean hasDashboardLink = annotations.stream()
                    .filter(PDAnnotationLink.class::isInstance)
                    .map(a -> ((PDAnnotationLink) a).getAction())
                    .filter(PDActionURI.class::isInstance)
                    .map(a -> ((PDActionURI) a).getURI())
                    .anyMatch("https://jalsoochak.jjmbrain.in/"::equals);
            assertThat(hasDashboardLink)
                    .as("first page must contain a clickable URI link annotation for the dashboard URL")
                    .isTrue();
        }
    }

    @Test
    void generate_soReport_doesNotRenderSectionOfficerBreakdown() throws Exception {
        // Even if summaries were somehow supplied, a SECTION_OFFICER layout must not draw the table.
        String filename = service.generate(
                sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", samplePriority(), sampleSectionOfficers());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).doesNotContain("Alice");
        }
    }

    private String renderText(DailyReportKpis kpis, List<DailyReportPriorityRow> priority) throws Exception {
        String filename = service.generate(kpis, 500L, "Binod Nimoli", "SECTION_OFFICER", priority, List.of());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
