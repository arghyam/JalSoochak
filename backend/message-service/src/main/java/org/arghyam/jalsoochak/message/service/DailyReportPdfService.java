package org.arghyam.jalsoochak.message.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.arghyam.jalsoochak.message.dto.DailyReportSectionOfficerRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the Daily Water Service Situation Report PDF (Apache PDFBox) for one officer.
 *
 * <p>Layout mirrors {@code JalSoochak Daily Water Service Situation Report.docx} for the
 * SECTION_OFFICER role: header, a bordered Summary table (Yesterday vs Previous Day + trend),
 * Priority Actions, Reasons for No Water Supply, and Anomalous Submissions. All tables have cell
 * borders; the whole document is drawn with an embedded DejaVu Sans font so the trend arrows
 * ({@code ▲}/{@code ▼}) and dash ({@code —}) render correctly.</p>
 */
@Service
@Slf4j
public class DailyReportPdfService {

    @Value("${daily-report.report.dir:${escalation.report.dir:/tmp/escalation-reports/}}")
    private String reportDir;

    @Value("${daily-report.dashboard-url:https://jalsoochak.jjmbrain.in/}")
    private String dashboardUrl;

    @Value("${daily-report.support-phone:}")
    private String supportPhone;

    private static final float MARGIN = 40f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float CELL_PAD = 6f;
    // Tighter horizontal padding for the dense SDO per–Section-Officer summary table. The default
    // 6pt padding leaves only ~30pt of text area in the narrow columns, which clips the trailing
    // glyph of the bold "Average" header ("Averag"+"e") and the 12th digit of a mobile number
    // ("91766101664"+"8"). 3pt gives both a comfortable (>2.5pt) fit on a single line.
    private static final float SO_TABLE_CELL_PAD = 3f;
    private static final float LINE_SPACING = 1.5f;

    // Hyperlink styling: link-blue text + underline, matching the standard "highlighted link" look.
    private static final float LINK_R = 0.10f;
    private static final float LINK_G = 0.35f;
    private static final float LINK_B = 0.85f;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    private static final DateTimeFormatter HEADER = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    private static final String NO_CHANGE = "—";      // — em dash
    private static final String UP = "▲ ";            // ▲
    private static final String DOWN = "▼ ";          // ▼

    /** anomaly_table.type stores the EscalationType NAME; map names → friendly labels. */
    private static final Map<String, String> ANOMALY_LABELS = Map.ofEntries(
            Map.entry("UNREADABLE_IMAGE", "Unreadable Image"),
            Map.entry("MANUAL_OVERRIDE", "Manual Override"),
            Map.entry("CONSECUTIVE_OVERRIDE_5_DAYS", "Consecutive Override (5 days)"),
            Map.entry("DUPLICATE_IMAGE_SUBMISSION", "Duplicate Image"),
            Map.entry("READING_LESS_THAN_PREVIOUS", "Reading Less Than Previous"),
            Map.entry("NO_WATER_SUPPLY", "No Water Supply"),
            Map.entry("LOW_WATER_SUPPLY", "Low Water Supply"),
            Map.entry("OVER_WATER_SUPPLY", "Over Water Supply"),
            Map.entry("NO_SUBMISSION", "No Submission"));

    /** Legacy fallback: some pre-migration rows store the numeric code string instead of the name. */
    private static final Map<String, String> CODE_TO_NAME = Map.ofEntries(
            Map.entry("1", "UNREADABLE_IMAGE"), Map.entry("2", "MANUAL_OVERRIDE"),
            Map.entry("3", "CONSECUTIVE_OVERRIDE_5_DAYS"), Map.entry("4", "DUPLICATE_IMAGE_SUBMISSION"),
            Map.entry("5", "READING_LESS_THAN_PREVIOUS"), Map.entry("6", "NO_WATER_SUPPLY"),
            Map.entry("7", "LOW_WATER_SUPPLY"), Map.entry("8", "OVER_WATER_SUPPLY"),
            Map.entry("9", "NO_SUBMISSION"));

    /**
     * Generates the report PDF and returns the filename (not the full path).
     *
     * @param kpis              computed KPI payload from analytics-service
     * @param officerUserId     unique id of the officer this report is for; used as the filename
     *                          discriminator (→ MinIO object key) so two officers of the same role
     *                          and name never share an object key and cross-expose each other's PII
     * @param officerName       decrypted officer display name (resolved by the caller)
     * @param officerUserType   SECTION_OFFICER | SUB_DIVISIONAL_OFFICER (drives filename + layout)
     * @param priorityRows      fully-resolved Priority Actions rows (scheme/IMIS/operators/issue/remarks)
     * @param sectionOfficerRows SDO-only per-officer Summary breakdown rows (name/mobile + KPIs); drawn
     *                          as the first table of the Summary section for a SUB_DIVISIONAL_OFFICER.
     *                          Ignored (may be null/empty) for a SECTION_OFFICER report.
     */
    public String generate(DailyReportKpis kpis, long officerUserId, String officerName, String officerUserType,
                           List<DailyReportPriorityRow> priorityRows,
                           List<DailyReportSectionOfficerRow> sectionOfficerRows) throws IOException {
        ensureReportDirExists();

        LocalDate reportDate = LocalDate.parse(kpis.getReportDate(), ISO);
        LocalDate previousDate = LocalDate.parse(kpis.getPreviousDate(), ISO);

        String safeRole = (officerUserType != null ? officerUserType : "OFFICER").replaceAll("[^A-Za-z0-9_\\-]", "_");
        // officerUserId (guaranteed unique) is the filename discriminator — NOT the officer name,
        // which can collide across officers and would overwrite one another's object in MinIO.
        String filename = String.format("daily_report_%s_%d_%s.pdf", safeRole, officerUserId, reportDate);
        Path filePath = Paths.get(reportDir, filename);

        try (PDDocument doc = new PDDocument()) {
            PDFont font = loadFont(doc, "/fonts/DejaVuSans.ttf");
            PDFont bold = loadFont(doc, "/fonts/DejaVuSans-Bold.ttf");
            // try-with-resources so the open content stream is deterministically closed on every
            // path, including a mid-render IOException from drawTable/text.
            try (Ctx ctx = new Ctx(doc, font, bold)) {
                ctx.newPage();

                // ---- Header ----
                headerLine(ctx, bold, 16, "Daily Water Service Situation Report");
                headerLine(ctx, font, 11, "Officer: " + (officerName != null ? officerName : "Officer"));
                headerLine(ctx, font, 11, "Date: " + reportDate.plusDays(1).format(HEADER));
                headerLine(ctx, font, 11, "Reporting Period: 00:00 hrs - 23:59 hrs");
                headerLinkLine(ctx, font, 9,
                        "Generated by JalSoochak. Visit the JalSoochak Dashboard at ",
                        dashboardUrl, " for more insights");
                ctx.y -= 8;

                // ---- 1. Summary ----
                sectionTitle(ctx, "1. Summary");

                // SDO only: per-Section-Officer breakdown table, drawn FIRST in the Summary section.
                // Always rendered for an SDO report; sectionOfficerSummaryRows draws an empty-state row
                // when there are no section officers.
                if (isSdo(officerUserType)) {
                    float[] soCols = {78, 62, 42, 48, 48, 42, 42, 55, 55, CONTENT_WIDTH - 472};
                    String[] soHeader = {
                            "Section Officer Name", "Mobile No.", "Total Schemes",
                            "Schemes Supplying Water", "Schemes Not Supplying Water",
                            "Average LPCD", "Average MLD", "Regular Supply (%) for past 1 week",
                            "Reading Submission (%)", "Anomalous Submissions"};
                    drawTable(ctx, soCols, soHeader, sectionOfficerSummaryRows(sectionOfficerRows),
                            7f, 7f, SO_TABLE_CELL_PAD);
                    ctx.y -= 12;
                }

                float[] sumCols = {215, 110, 110, CONTENT_WIDTH - 435};
                String[] sumHeader = {
                        "KPI",
                        "Yesterday\n(" + reportDate.format(DISPLAY) + ")",
                        "Previous Day\n(" + previousDate.format(DISPLAY) + ")",
                        "Trend"};
                drawTable(ctx, sumCols, sumHeader, summaryRows(kpis), 10f, 10f);
                ctx.y -= 12;

                // ---- 2. Priority Actions ----
                sectionTitle(ctx, "2. Priority Actions");
                float[] paCols = {95, 70, 92, 82, 84, CONTENT_WIDTH - 423};
                String[] paHeader = {"Scheme", "IMIS_ID", "Jal Mitra Name", "Jal Mitra Mobile", "Issue", "Remarks"};
                drawTable(ctx, paCols, paHeader, priorityActionRows(priorityRows), 8f, 8.5f);
                ctx.y -= 12;

                // ---- 3. Reasons for No Water Supply ----
                sectionTitle(ctx, "3. Reasons for No Water Supply");
                float[] rCols = {CONTENT_WIDTH - 100, 100};
                drawTable(ctx, rCols, new String[]{"Reason", "Count"}, reasonRows(kpis), 10f, 10f);
                ctx.y -= 12;

                // ---- 4. Anomalous Submissions ----
                sectionTitle(ctx, "4. Anomalous Submissions");
                drawTable(ctx, rCols, new String[]{"Type", "Count"}, anomalyRows(kpis), 10f, 10f);
                ctx.y -= 12;

                // ---- Footer ----
                if (supportPhone != null && !supportPhone.isBlank()) {
                    ctx.ensureSpace(16);
                    ctx.y -= 12;
                    ctx.text(font, 8, MARGIN, ctx.y,
                            "* If you think you received this message by mistake, please reach out to " + supportPhone);
                }
            }
            doc.save(filePath.toFile());
        }

        log.info("[DailyReportPdf] Saved report to {}", filePath);
        return filename;
    }

    // ---- row builders ---------------------------------------------------

    private List<String[]> summaryRows(DailyReportKpis k) {
        DailyReportKpis.DayKpis y = k.getYesterday();
        DailyReportKpis.DayKpis p = k.getPreviousDay();
        List<String[]> rows = new ArrayList<>();
        rows.add(intRow("Total Schemes", k.getTotalSchemes(), k.getTotalSchemes()));
        rows.add(intRow("Schemes Supplying Water", y.getSchemesSupplying(), p.getSchemesSupplying()));
        rows.add(intRow("Schemes Not Supplying Water", y.getSchemesNotSupplying(), p.getSchemesNotSupplying()));
        rows.add(dblRow("Average LPCD", y.getAvgLpcd(), p.getAvgLpcd(), ""));
        rows.add(dblRow("Average MLD", y.getAvgMld(), p.getAvgMld(), ""));
        rows.add(dblRow("Regular Supply (%) past 1 week", y.getRegularSupplyPctWeek(), p.getRegularSupplyPctWeek(), "%"));
        rows.add(dblRow("Reading Submission (%)", y.getReadingSubmissionPct(), p.getReadingSubmissionPct(), "%"));
        rows.add(intRow("Anomalous Submissions", y.getAnomalousCount(), p.getAnomalousCount()));
        return rows;
    }

    private String[] intRow(String label, int yVal, int pVal) {
        return new String[]{label, String.valueOf(yVal), String.valueOf(pVal), trendInt(yVal - pVal)};
    }

    private String[] dblRow(String label, double yVal, double pVal, String suffix) {
        return new String[]{label, fmt(yVal) + suffix, fmt(pVal) + suffix, trendDouble(yVal - pVal, suffix)};
    }

    private static boolean isSdo(String officerUserType) {
        return "SUB_DIVISIONAL_OFFICER".equalsIgnoreCase(officerUserType != null ? officerUserType.trim() : null);
    }

    private List<String[]> sectionOfficerSummaryRows(List<DailyReportSectionOfficerRow> rows) {
        List<String[]> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            out.add(new String[]{"No section officers for this SDO", "", "", "", "", "", "", "", "", ""});
            return out;
        }
        for (DailyReportSectionOfficerRow r : rows) {
            out.add(new String[]{
                    nvl(r.getOfficerName()), nvl(r.getOfficerMobile()),
                    String.valueOf(r.getTotalSchemes()),
                    String.valueOf(r.getSchemesSupplying()),
                    String.valueOf(r.getSchemesNotSupplying()),
                    fmt(r.getAvgLpcd()), fmt(r.getAvgMld()),
                    fmt(r.getRegularSupplyPctWeek()) + "%", fmt(r.getReadingSubmissionPct()) + "%",
                    String.valueOf(r.getAnomalousCount())});
        }
        return out;
    }

    private List<String[]> priorityActionRows(List<DailyReportPriorityRow> rows) {
        List<String[]> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            out.add(new String[]{"No priority actions for this day", "", "", "", "", ""});
            return out;
        }
        for (DailyReportPriorityRow r : rows) {
            out.add(new String[]{
                    nvl(r.getScheme()), nvl(r.getImisId()), nvl(r.getJalMitraNames()),
                    nvl(r.getJalMitraMobiles()), nvl(r.getIssue()), nvl(r.getRemarks())});
        }
        return out;
    }

    private List<String[]> reasonRows(DailyReportKpis k) {
        List<String[]> out = new ArrayList<>();
        List<DailyReportKpis.ReasonCount> reasons = k.getReasonsForNoSupply();
        if (reasons == null || reasons.isEmpty()) {
            out.add(new String[]{"No outages reported for this day", "0"});
            return out;
        }
        for (DailyReportKpis.ReasonCount r : reasons) {
            out.add(new String[]{prettifyReason(r.getReason()), String.valueOf(r.getCount())});
        }
        return out;
    }

    private List<String[]> anomalyRows(DailyReportKpis k) {
        List<String[]> out = new ArrayList<>();
        List<DailyReportKpis.TypeCount> anomalies = k.getAnomaliesByType();
        if (anomalies == null || anomalies.isEmpty()) {
            out.add(new String[]{"No anomalies for this day", "0"});
            return out;
        }
        for (DailyReportKpis.TypeCount t : anomalies) {
            out.add(new String[]{anomalyLabel(t.getType()), String.valueOf(t.getCount())});
        }
        return out;
    }

    // ---- trend / formatting --------------------------------------------

    private String trendInt(int delta) {
        if (delta == 0) return NO_CHANGE;
        return (delta > 0 ? UP + "+" + delta : DOWN + "-" + Math.abs(delta));
    }

    private String trendDouble(double delta, String suffix) {
        // Round to 1 dp so floating-point subtraction (e.g. 18.4 - 18.1) doesn't leak long decimals.
        double rounded = Math.round(delta * 10.0) / 10.0;
        if (Math.abs(rounded) < 0.05) return NO_CHANGE;
        String v = fmt(Math.abs(rounded)) + suffix;
        return (rounded > 0 ? UP + "+" : DOWN + "-") + v;
    }

    private String fmt(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private String anomalyLabel(String type) {
        if (type == null) return "Unknown";
        if (ANOMALY_LABELS.containsKey(type)) return ANOMALY_LABELS.get(type);
        String name = CODE_TO_NAME.get(type.trim());            // legacy numeric code → name
        if (name != null && ANOMALY_LABELS.containsKey(name)) return ANOMALY_LABELS.get(name);
        return prettifyReason(type);
    }

    private String prettifyReason(String reason) {
        if (reason == null || reason.isBlank()) return "Unknown";
        String r = reason.trim();
        if (r.matches("\\w+") && (r.contains("_") || r.equals(r.toUpperCase()) || r.equals(r.toLowerCase()))) {
            String[] parts = r.toLowerCase().split("_");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
            }
            return sb.toString().trim();
        }
        return r;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    // ---- table + text rendering ----------------------------------------

    private void sectionTitle(Ctx ctx, String title) throws IOException {
        ctx.ensureSpace(40);
        ctx.y -= 18;
        ctx.text(ctx.bold, 13, MARGIN, ctx.y, title);
        ctx.y -= 14;
    }

    private void headerLine(Ctx ctx, PDFont font, float size, String text) throws IOException {
        ctx.y -= (size + 4);
        ctx.text(font, size, MARGIN, ctx.y, text);
    }

    /**
     * Renders a header line of the form {@code prefix + <url> + suffix} where the URL is a real,
     * clickable hyperlink: drawn in link-blue with an underline AND backed by a
     * {@link PDAnnotationLink}. Embedding the annotation (rather than relying on the plain URL text)
     * is what makes the link clickable in <em>every</em> viewer. Browsers / the MinIO preview
     * auto-detect bare URLs, but WhatsApp and most mobile PDF viewers do not — so without the
     * annotation the link works after a MinIO download yet is dead after a WhatsApp download.
     */
    private void headerLinkLine(Ctx ctx, PDFont font, float size, String prefix, String url, String suffix)
            throws IOException {
        ctx.y -= (size + 4);
        float baseline = ctx.y;
        float x = MARGIN;
        ctx.text(font, size, x, baseline, prefix);
        x += textWidth(font, size, prefix);
        float urlWidth = textWidth(font, size, url);
        ctx.linkText(font, size, x, baseline, url, urlWidth);
        x += urlWidth;
        ctx.text(font, size, x, baseline, suffix);
    }

    /** Draws a bordered table with a bold header row; wraps cells and paginates, re-drawing the header. */
    private void drawTable(Ctx ctx, float[] colW, String[] header, List<String[]> rows,
                           float fontSize, float headerFontSize) throws IOException {
        drawTable(ctx, colW, header, rows, fontSize, headerFontSize, CELL_PAD);
    }

    /** As {@link #drawTable} but with an explicit horizontal cell padding (used by the dense SDO table). */
    private void drawTable(Ctx ctx, float[] colW, String[] header, List<String[]> rows,
                           float fontSize, float headerFontSize, float cellPad) throws IOException {
        float headerH = rowHeight(ctx.bold, headerFontSize, colW, header, cellPad);
        ctx.ensureSpace(headerH + rowHeight(ctx.font, fontSize, colW, rows.isEmpty() ? header : rows.get(0), cellPad));
        drawRow(ctx, colW, header, ctx.bold, headerFontSize, cellPad);
        for (String[] r : rows) {
            float h = rowHeight(ctx.font, fontSize, colW, r, cellPad);
            if (ctx.y - h < MARGIN) {
                ctx.newPage();
                drawRow(ctx, colW, header, ctx.bold, headerFontSize, cellPad);
            }
            drawRow(ctx, colW, r, ctx.font, fontSize, cellPad);
        }
    }

    private float rowHeight(PDFont font, float fontSize, float[] colW, String[] cells, float cellPad)
            throws IOException {
        float lh = fontSize * LINE_SPACING;
        int maxLines = 1;
        for (int c = 0; c < colW.length; c++) {
            int lines = wrapCell(font, fontSize, cellAt(cells, c), colW[c] - 2 * cellPad).size();
            maxLines = Math.max(maxLines, lines);
        }
        return maxLines * lh + 2 * cellPad;
    }

    private void drawRow(Ctx ctx, float[] colW, String[] cells, PDFont font, float fontSize, float cellPad)
            throws IOException {
        float lh = fontSize * LINE_SPACING;
        List<List<String>> wrapped = new ArrayList<>();
        int maxLines = 1;
        for (int c = 0; c < colW.length; c++) {
            List<String> lines = wrapCell(font, fontSize, cellAt(cells, c), colW[c] - 2 * cellPad);
            wrapped.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }
        float rowH = maxLines * lh + 2 * cellPad;
        float top = ctx.y;
        float cx = MARGIN;
        for (int c = 0; c < colW.length; c++) {
            float baseline = top - cellPad - fontSize;
            for (String line : wrapped.get(c)) {
                ctx.text(font, fontSize, cx + cellPad, baseline, line);
                baseline -= lh;
            }
            ctx.rect(cx, top - rowH, colW[c], rowH);
            cx += colW[c];
        }
        ctx.y = top - rowH;
    }

    private String cellAt(String[] cells, int c) {
        return (c < cells.length && cells[c] != null) ? cells[c] : "";
    }

    /** Wraps text to the given width (points), splitting on explicit newlines first, then words. */
    private List<String> wrapCell(PDFont font, float fontSize, String text, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(font, fontSize, paragraph, maxWidth, out);
        }
        return out;
    }

    private void wrapParagraph(PDFont font, float fontSize, String paragraph, float maxWidth, List<String> out)
            throws IOException {
        if (paragraph.isEmpty()) {
            out.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (textWidth(font, fontSize, candidate) <= maxWidth || line.length() == 0) {
                // If a single word is itself too wide, hard-split it.
                if (line.length() == 0 && textWidth(font, fontSize, word) > maxWidth) {
                    hardSplit(font, fontSize, word, maxWidth, out);
                } else {
                    line = new StringBuilder(candidate);
                }
            } else {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
    }

    private void hardSplit(PDFont font, float fontSize, String word, float maxWidth, List<String> out)
            throws IOException {
        StringBuilder chunk = new StringBuilder();
        for (char ch : word.toCharArray()) {
            if (textWidth(font, fontSize, chunk.toString() + ch) > maxWidth && chunk.length() > 0) {
                out.add(chunk.toString());
                chunk = new StringBuilder();
            }
            chunk.append(ch);
        }
        if (chunk.length() > 0) {
            out.add(chunk.toString());
        }
    }

    private float textWidth(PDFont font, float fontSize, String text) throws IOException {
        return font.getStringWidth(sanitize(font, text)) / 1000f * fontSize;
    }

    /** Replaces any character the embedded font can't encode with '?' so showText never throws. */
    private static String sanitize(PDFont font, String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
                return;
            }
            String ch = new String(Character.toChars(cp));
            try {
                font.getStringWidth(ch);
                sb.append(ch);
            } catch (Exception e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private PDFont loadFont(PDDocument doc, String resourcePath) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Embedded font not found on classpath: " + resourcePath);
            }
            return PDType0Font.load(doc, in);
        }
    }

    private void ensureReportDirExists() throws IOException {
        Path dir = Paths.get(reportDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("[DailyReportPdf] Created report directory: {}", dir);
        }
    }

    /** Per-render state (page/stream/cursor). Instance-per-call — safe under concurrent Kafka handling. */
    private static final class Ctx implements AutoCloseable {
        private final PDDocument doc;
        private final PDFont font;
        private final PDFont bold;
        private PDPageContentStream cs;
        private PDPage page;
        private float y;

        Ctx(PDDocument doc, PDFont font, PDFont bold) {
            this.doc = doc;
            this.font = font;
            this.bold = bold;
        }

        void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage();
            }
        }

        void text(PDFont f, float size, float x, float baselineY, String s) throws IOException {
            cs.beginText();
            cs.setFont(f, size);
            cs.newLineAtOffset(x, baselineY);
            cs.showText(sanitize(f, s));
            cs.endText();
        }

        /**
         * Draws {@code uri} as an underlined, link-blue hyperlink starting at {@code (x, baselineY)}
         * and attaches a clickable {@link PDAnnotationLink} (URI action) over the same box, so the
         * link is clickable in every PDF viewer regardless of bare-URL auto-detection. Resets the
         * fill/stroke colour back to black afterwards so later drawing is unaffected.
         */
        void linkText(PDFont f, float size, float x, float baselineY, String uri, float width) throws IOException {
            cs.beginText();
            cs.setFont(f, size);
            cs.setNonStrokingColor(LINK_R, LINK_G, LINK_B);
            cs.newLineAtOffset(x, baselineY);
            cs.showText(sanitize(f, uri));
            cs.endText();
            cs.setNonStrokingColor(0f, 0f, 0f);

            float underlineY = baselineY - 1.5f;
            cs.setStrokingColor(LINK_R, LINK_G, LINK_B);
            cs.setLineWidth(0.6f);
            cs.moveTo(x, underlineY);
            cs.lineTo(x + width, underlineY);
            cs.stroke();
            cs.setStrokingColor(0f, 0f, 0f);

            PDActionURI action = new PDActionURI();
            action.setURI(uri);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setAction(action);
            // No visible annotation border — the blue underline drawn above is the only decoration.
            PDBorderStyleDictionary noBorder = new PDBorderStyleDictionary();
            noBorder.setWidth(0);
            link.setBorderStyle(noBorder);
            // Hotspot covers the glyph box (descender to ascender) of the URL text.
            link.setRectangle(new PDRectangle(x, baselineY - 2f, width, size + 2f));
            page.getAnnotations().add(link);
        }

        void rect(float x, float yBottom, float w, float h) throws IOException {
            cs.setLineWidth(0.5f);
            cs.addRect(x, yBottom, w, h);
            cs.stroke();
        }

        @Override
        public void close() throws IOException {
            if (cs != null) {
                cs.close();
            }
        }
    }
}
