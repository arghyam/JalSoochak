package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DailyReportPdfService} — verifies the SECTION_OFFICER report renders a valid,
 * non-empty PDF containing the expected sections, KPI values, and ASCII-safe trend markers.
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
                        DailyReportKpis.TypeCount.builder().type("5").count(3).build(),
                        DailyReportKpis.TypeCount.builder().type("4").count(1).build()))
                .build();
    }

    @Test
    void generate_createsValidPdfWithExpectedFilename() throws Exception {
        String filename = service.generate(sampleKpis(), "Binod Nimoli", "SECTION_OFFICER");

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
    void generate_pdfContainsHeaderSectionsAndKpiValues() throws Exception {
        String filename = service.generate(sampleKpis(), "Binod Nimoli", "SECTION_OFFICER");
        String text = extractText(tempDir.resolve(filename));

        assertThat(text).contains("Daily Water Service Situation Report");
        assertThat(text).contains("Officer: Binod Nimoli");
        assertThat(text).contains("1. Summary");
        assertThat(text).contains("3. Reasons for No Water Supply");
        assertThat(text).contains("4. Anomalous Submissions");
        // KPI values
        assertThat(text).contains("148");
        assertThat(text).contains("142");
        assertThat(text).contains("18.4");
        // Trend markers are ASCII signed deltas
        assertThat(text).contains("+2");   // supplying 142 vs 140
        assertThat(text).contains("-2");   // not-supplying 6 vs 8
    }

    @Test
    void generate_mapsAnomalyCodesAndPrettifiesReasonKeys() throws Exception {
        String filename = service.generate(sampleKpis(), "SO", "SECTION_OFFICER");
        String text = extractText(tempDir.resolve(filename));

        assertThat(text).contains("Reading Less Than Previous"); // type 5
        assertThat(text).contains("Duplicate Image");            // type 4
        assertThat(text).contains("Pump Failure");               // PUMP_FAILURE prettified
        assertThat(text).contains("Pipeline Break");
    }

    @Test
    void generate_handlesEmptyReasonAndAnomalyLists() throws Exception {
        DailyReportKpis kpis = sampleKpis();
        kpis.setReasonsForNoSupply(List.of());
        kpis.setAnomaliesByType(List.of());

        String filename = service.generate(kpis, "SO", "SECTION_OFFICER");
        String text = extractText(tempDir.resolve(filename));

        assertThat(text).contains("No outages reported");
        assertThat(text).contains("No anomalies");
    }

    private String extractText(Path pdfPath) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
