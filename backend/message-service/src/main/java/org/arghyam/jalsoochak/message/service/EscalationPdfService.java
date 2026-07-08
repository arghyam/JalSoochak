package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.dto.OperatorEscalationDetail;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Generates an escalation PDF report for a given officer using Apache PDFBox.
 * The PDF is saved to the configured report directory and the filename is returned
 * so that it can be uploaded to MinIO.
 */
@Service
@Slf4j
public class EscalationPdfService {

    @Value("${escalation.report.dir:/tmp/escalation-reports/}")
    private String reportDir;

    private static final float MARGIN = 50f;
    private static final float LINE_HEIGHT = 15f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    /**
     * Generates the escalation PDF and saves it to the report directory.
     *
     * @param officerUserType the officer's configured role/designation (e.g. "JE", "SECTION_OFFICER")
     * @param correlationId   stable key used as the filename suffix so retries overwrite the same
     *                        file rather than accumulating new ones; falls back to a random UUID
     *                        if blank
     * @return the filename (not the full path) of the saved PDF
     */
    public String generate(List<OperatorEscalationDetail> operators, int level, String officerName,
                           String officerUserType, String correlationId) throws IOException {
        ensureReportDirExists();

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String safeOfficerName = (officerName != null ? officerName : "Unknown")
                .replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String sanitizedCorrelationId = correlationId != null
                ? correlationId.replaceAll("[^a-zA-Z0-9_\\-]", "") : "";
        String stableKey = sanitizedCorrelationId.isBlank()
                ? UUID.randomUUID().toString() : sanitizedCorrelationId;
        String filename = String.format("escalation_L%d_%s_%s-%s.pdf",
                level, safeOfficerName, dateStr, stableKey);
        Path filePath = Paths.get(reportDir, filename);

        String roleLabel = (officerUserType != null && !officerUserType.isBlank())
                ? officerUserType : ("Level " + level);

        try (PDDocument doc = new PDDocument()) {
            PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regularFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            // Track current page and Y position
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = PAGE_HEIGHT - MARGIN;

            // Title on its own line, then the date/role/office-name metadata on a full-width line
            // below it. Both wrap so a long office name is never clipped at the page edge.
            y = writeWrappedLine(cs, boldFont, 14, "Jalmitra Escalations", MARGIN, y, CONTENT_WIDTH);
            y = writeWrappedLine(cs, boldFont, 11,
                    String.format("%s — %s Officer: %s", dateStr, roleLabel, officerName),
                    MARGIN, y, CONTENT_WIDTH);
            y -= LINE_HEIGHT;

            for (int i = 0; i < operators.size(); i++) {
                OperatorEscalationDetail op = operators.get(i);

                // Check if we need a new page (7 lines per operator + 2 spacing)
                if (y < MARGIN + 9 * LINE_HEIGHT) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = PAGE_HEIGHT - MARGIN;
                }

                y = writeLine(cs, boldFont, 11, (i + 1) + ".", MARGIN, y);
                y = writeLabelValue(cs, boldFont, regularFont, "   Name:", op.getName(), y);
                y = writeLabelValue(cs, boldFont, regularFont, "   Phone Number:", op.getPhoneNumber(), y);
                y = writeLabelValue(cs, boldFont, regularFont, "   Scheme Name:", op.getSchemeName(), y);
                y = writeLabelValue(cs, boldFont, regularFont, "   Scheme ID:", op.getSchemeId(), y);
                y = writeLabelValue(cs, boldFont, regularFont, "   SO Name:", op.getSoName(), y);
                String daysMissedText = op.getConsecutiveDaysMissed() != null
                        ? String.valueOf(op.getConsecutiveDaysMissed()) : "Never";
                y = writeLabelValue(cs, boldFont, regularFont, "   Consecutive Days Missed:",
                        daysMissedText, y);
                String bfmDate = (op.getLastRecordedBfmDate() == null || op.getLastRecordedBfmDate().isBlank())
                        ? "Never" : op.getLastRecordedBfmDate();
                y = writeLabelValue(cs, boldFont, regularFont, "   Last Recorded BFM Date:", bfmDate, y);
                y -= LINE_HEIGHT;
            }

            cs.close();
            doc.save(filePath.toFile());
        }

        log.info("[EscalationPdf] Saved report to {}", filePath);
        return filename;
    }

    private float writeLine(PDPageContentStream cs, PDType1Font font, float size,
                             String text, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text != null ? text : "");
        cs.endText();
        return y - LINE_HEIGHT;
    }

    /**
     * Writes {@code text} at {@code x}, wrapping onto additional lines so that no line exceeds
     * {@code maxWidth}. PDFBox does not wrap automatically, so without this a long value (e.g. a
     * lengthy office name in the title) would run off the right edge of the page and be clipped.
     *
     * @return the y position below the last line written
     */
    private float writeWrappedLine(PDPageContentStream cs, PDType1Font font, float size,
                                    String text, float x, float y, float maxWidth) throws IOException {
        for (String line : wrapText(font, size, text != null ? text : "", maxWidth)) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(line);
            cs.endText();
            y -= LINE_HEIGHT;
        }
        return y;
    }

    /**
     * Greedily splits {@code text} into lines that each fit within {@code maxWidth} for the given
     * font/size. Words are kept intact where possible; a single word wider than {@code maxWidth}
     * (e.g. no spaces) is hard-broken character by character so it still fits.
     */
    private List<String> wrapText(PDType1Font font, float fontSize, String text, float maxWidth)
            throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (stringWidth(font, fontSize, candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (stringWidth(font, fontSize, word) <= maxWidth) {
                current.append(word);
            } else {
                // Word alone is wider than the line — hard-break it across lines.
                StringBuilder piece = new StringBuilder();
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    if (piece.length() > 0
                            && stringWidth(font, fontSize, piece.toString() + c) > maxWidth) {
                        lines.add(piece.toString());
                        piece.setLength(0);
                    }
                    piece.append(c);
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    /** Width of {@code text} in points for the given font and size. */
    private float stringWidth(PDType1Font font, float fontSize, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    private float writeLabelValue(PDPageContentStream cs,
                                   PDType1Font boldFont, PDType1Font regularFont,
                                   String label, String value, float y) throws IOException {
        float labelWidth = 180f;
        // Label
        cs.beginText();
        cs.setFont(boldFont, 10);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText(label);
        cs.endText();
        // Value
        cs.beginText();
        cs.setFont(regularFont, 10);
        cs.newLineAtOffset(MARGIN + labelWidth, y);
        cs.showText(value != null ? value : "");
        cs.endText();
        return y - LINE_HEIGHT;
    }

    private void ensureReportDirExists() throws IOException {
        Path dir = Paths.get(reportDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("[EscalationPdf] Created report directory: {}", dir);
        }
    }
}
