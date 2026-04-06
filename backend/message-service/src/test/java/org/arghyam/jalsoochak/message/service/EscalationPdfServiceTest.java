package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.dto.OperatorEscalationDetail;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EscalationPdfService} PDF generation.
 *
 * <p>Verifies file creation, filename format, PDF content, and multi-page
 * behaviour without any Spring context – all dependencies are instantiated
 * directly.</p>
 */
class EscalationPdfServiceTest {

    @TempDir
    Path tempDir;

    private EscalationPdfService service;

    @BeforeEach
    void setUp() {
        service = new EscalationPdfService();
        ReflectionTestUtils.setField(service, "reportDir", tempDir.toString() + "/");
    }

    @Test
    void generate_createsPdfFileInReportDirectory() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op One", "S-01", 3, "2024-01-01"));

        String filename = service.generate(operators, 1, "SO Officer", "SECTION_OFFICER", "test-corr-id");

        assertThat(filename).endsWith(".pdf");
        assertThat(tempDir.resolve(filename).toFile()).exists().isFile();
    }

    @Test
    void generate_filenameContainsLevelAndOfficerName() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 4, "2024-01-02"));

        String filename = service.generate(operators, 2, "District Officer", "DISTRICT_OFFICER", "test-corr-id");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertThat(filename).startsWith("escalation_L2_District_Officer_").endsWith(".pdf");
        assertThat(filename).contains(today);
    }

    @Test
    void generate_sanitizesSpecialCharactersInOfficerName() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 5, "2024-01-03"));

        String filename = service.generate(operators, 1, "O'Brien & Co.", "SECTION_OFFICER", "test-corr-id");

        // Special chars in officer name are replaced with underscores;
        // only the .pdf extension may contain a dot
        String nameSection = filename.substring(0, filename.lastIndexOf('.'));
        assertThat(nameSection).doesNotContain("'").doesNotContain("&").doesNotContain(".");
    }

    @Test
    void generate_pdfContainsOperatorName() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Ramesh Kumar", "S-99", 7, "2024-01-05"));

        String filename = service.generate(operators, 2, "DO Sharma", "DISTRICT_OFFICER", "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        assertThat(pdfText).contains("Ramesh Kumar");
    }

    @Test
    void generate_pdfContainsSchemeName() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "VILLAGE-SCHEME-XY", 3, "2024-01-06"));

        String filename = service.generate(operators, 1, "SO Name", "SECTION_OFFICER", "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        assertThat(pdfText).contains("VILLAGE-SCHEME-XY");
    }

    @Test
    void generate_pdfContainsConsecutiveDaysMissed() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 12, "2024-01-07"));

        String filename = service.generate(operators, 2, "DO Z", "DISTRICT_OFFICER", "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        assertThat(pdfText).contains("12");
    }

    @Test
    void generate_pdfContainsTitleWithOfficerRoleAndName() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 3, "2024-01-08"));

        String filename = service.generate(operators, 1, "SO Verma", "SECTION_OFFICER", "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        assertThat(pdfText).contains("SECTION_OFFICER");
        assertThat(pdfText).contains("SO Verma");
    }

    @Test
    void generate_pdfTitleFallsBackToLevelN_whenOfficerUserTypeIsBlank() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 3, "2024-01-08"));

        String filename = service.generate(operators, 1, "SO Verma", "", "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        assertThat(pdfText).contains("Level 1");
        assertThat(pdfText).contains("SO Verma");
    }

    @Test
    void generate_pdfTitleFallsBackToLevelN_whenOfficerUserTypeIsNull() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 3, "2024-01-08"));

        String filename = service.generate(operators, 2, "DO Sharma", null, "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        assertThat(pdfText).contains("Level 2");
        assertThat(pdfText).contains("DO Sharma");
    }

    @Test
    void generate_showsNeverForNullLastRecordedDate() throws Exception {
        OperatorEscalationDetail op = OperatorEscalationDetail.builder()
                .name("Op").phoneNumber("911111111111")
                .schemeName("S").schemeId("1").soName("SO")
                .consecutiveDaysMissed(null) // null → never uploaded, should show "Never"
                .lastRecordedBfmDate(null) // null → should show "Never"
                .build();

        String filename = service.generate(List.of(op), 2, "DO", "DISTRICT_OFFICER", "test-corr-id");
        String pdfText = extractText(tempDir.resolve(filename));

        // Count occurrences of "Never" — one for consecutiveDaysMissed null, one for lastRecordedBfmDate null
        long occurrences = 0;
        int idx = 0;
        while ((idx = pdfText.indexOf("Never", idx)) != -1) {
            occurrences++;
            idx += "Never".length();
        }
        assertThat(occurrences).isEqualTo(2);
    }

    @Test
    void generate_handlesMultipleOperators_producingValidPdf() throws Exception {
        List<OperatorEscalationDetail> operators = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            operators.add(buildOperator("Operator " + i, "Scheme-" + i, i + 3, "2024-01-0" + i));
        }

        String filename = service.generate(operators, 1, "SO Multi", "SECTION_OFFICER", "test-corr-id");

        File pdfFile = tempDir.resolve(filename).toFile();
        assertThat(pdfFile).exists();

        // Must produce a valid PDF readable by PDFBox
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void generate_createsReportDirectory_whenItDoesNotExist() throws Exception {
        Path subDir = tempDir.resolve("nested/reports/");
        ReflectionTestUtils.setField(service, "reportDir", subDir.toString() + "/");

        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op", "S", 3, "2024-01-09"));

        String filename = service.generate(operators, 1, "SO", "SECTION_OFFICER", "test-corr-id");

        assertThat(subDir.resolve(filename).toFile()).exists();
    }

    @Test
    void generate_usesCorrelationId_forFilename() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op One", "S-01", 3, "2024-01-01"));

        String filename = service.generate(operators, 1, "SO Officer", "SECTION_OFFICER", "test-corr-id");

        assertThat(filename).contains("test-corr-id");
    }

    @Test
    void generate_usesRandomSuffix_whenCorrelationIdIsBlank() throws Exception {
        List<OperatorEscalationDetail> operators = List.of(buildOperator("Op One", "S-01", 3, "2024-01-01"));

        String filename1 = service.generate(operators, 1, "SO Officer", "SECTION_OFFICER", "");
        String filename2 = service.generate(operators, 1, "SO Officer", "SECTION_OFFICER", "  ");

        assertThat(filename1).isNotEqualTo(filename2);
    }

    // ────────────────────────────── helpers ────────────────────────────────────

    private OperatorEscalationDetail buildOperator(String name, String scheme, int daysMissed, String lastDate) {
        return OperatorEscalationDetail.builder()
                .name(name)
                .phoneNumber("919000000001")
                .schemeName(scheme)
                .schemeId("1")
                .soName("SO Name")
                .consecutiveDaysMissed(daysMissed)
                .lastRecordedBfmDate(lastDate)
                .build();
    }

    private String extractText(Path pdfPath) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
