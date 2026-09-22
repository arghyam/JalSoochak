package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The drawing surface both water-report PDFs are built on: an A4 page with a cursor, bordered
 * paginating tables, wrapped cells, hyperlinks, and trend colouring.
 *
 * <p>Shared rather than duplicated because the two reports must look like one product — a reader who
 * gets a daily report on Tuesday and a weekly on Monday should not be able to tell they were drawn by
 * different code. It also concentrates the PDFBox subtleties (fill colour set before
 * {@code beginText}, link annotations rather than bare URLs, unencodable-glyph sanitising) in one
 * place instead of two.</p>
 *
 * <p>One instance per render — it owns an open content stream and a cursor, so it is not shareable
 * across concurrent Kafka handlers. Close it, or use try-with-resources, to flush the last page.</p>
 */
public final class ReportPdfCanvas implements AutoCloseable {

    public static final float MARGIN = 40f;
    public static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    public static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    public static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    public static final float CELL_PAD = 6f;
    /**
     * Tighter horizontal padding for dense many-column tables. The default 6pt leaves only ~30pt of
     * text area in a narrow column, which clips the trailing glyph of a bold header and the last
     * digit of a mobile number; 3pt gives both a comfortable single-line fit.
     */
    public static final float DENSE_CELL_PAD = 3f;
    public static final float LINE_SPACING = 1.5f;
    /** Vertical drop of one empty 11pt header line, used for the gaps around the header block. */
    public static final float BLANK_LINE = 15f;

    // Hyperlink styling: link-blue text + underline, matching the standard "highlighted link" look.
    private static final float LINK_R = 0.10f;
    private static final float LINK_G = 0.35f;
    private static final float LINK_B = 0.85f;

    // Trend colouring is by *direction of improvement*, not by arrow direction: a move the right way
    // for that KPI is green, the wrong way red. For most KPIs "up" is the right way; for the negative
    // KPIs it is "down". The em-dash "no change" indicator keeps the default black.
    private static final float TREND_GOOD_R = 0.13f;
    private static final float TREND_GOOD_G = 0.55f;
    private static final float TREND_GOOD_B = 0.13f; // forest green
    private static final float TREND_BAD_R = 0.80f;
    private static final float TREND_BAD_G = 0.11f;
    private static final float TREND_BAD_B = 0.11f;  // red

    public static final String NO_CHANGE = "—";      // em dash
    public static final String UP = "▲ ";
    public static final String DOWN = "▼ ";

    private final PDDocument doc;
    private final PDFont font;
    private final PDFont bold;

    /** KPI row labels where an increase is a deterioration, so the arrow colouring inverts. */
    private final Set<String> negativeKpiLabels;

    private PDPageContentStream cs;
    private PDPage page;
    private float y;

    /** Whether a section title has already been drawn — the gap goes *between* sections, not above the first. */
    private boolean sectionDrawn;

    /**
     * @param negativeKpiLabels first-column labels whose trend colouring is inverted (an up arrow is
     *                          drawn red). Pass an empty set for a report with no trend column.
     */
    public ReportPdfCanvas(PDDocument doc, Set<String> negativeKpiLabels) throws IOException {
        this.doc = doc;
        this.negativeKpiLabels = negativeKpiLabels;
        // DejaVu carries the trend arrows and the em dash, which the PDFBox standard-14 fonts do not.
        this.font = loadFont(doc, "/fonts/DejaVuSans.ttf");
        this.bold = loadFont(doc, "/fonts/DejaVuSans-Bold.ttf");
    }

    public PDFont font() {
        return font;
    }

    public PDFont bold() {
        return bold;
    }

    public float y() {
        return y;
    }

    public void moveDown(float points) {
        y -= points;
    }

    // ---- page / primitives ---------------------------------------------------

    public void newPage() throws IOException {
        if (cs != null) {
            cs.close();
        }
        page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        y = PAGE_HEIGHT - MARGIN;
    }

    public void ensureSpace(float needed) throws IOException {
        if (y - needed < MARGIN) {
            newPage();
        }
    }

    public void text(PDFont f, float size, float x, float baselineY, String s) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, baselineY);
        cs.showText(sanitize(f, s));
        cs.endText();
    }

    /**
     * As {@link #text} but draws the glyphs in the given RGB fill colour, restoring black after.
     *
     * <p>The colour is set <em>before</em> {@code beginText()}: a fill colour set inside a text object
     * is not reliably honoured by all renderers — PDFBox's own text stripper reports the glyph as
     * default black — so the {@code rg} operator must precede the text object.</p>
     */
    public void colorText(PDFont f, float size, float x, float baselineY, String s, float r, float g, float b)
            throws IOException {
        cs.setNonStrokingColor(r, g, b);
        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, baselineY);
        cs.showText(sanitize(f, s));
        cs.endText();
        cs.setNonStrokingColor(0f, 0f, 0f);
    }

    /**
     * Draws {@code uri} as an underlined, link-blue hyperlink and attaches a clickable
     * {@link PDAnnotationLink} over the same box.
     *
     * <p>The annotation is what makes the link work in <em>every</em> viewer. Browsers and the MinIO
     * preview auto-detect bare URLs, but WhatsApp and most mobile PDF viewers do not — so without it
     * the link works after a MinIO download and is dead after a WhatsApp one, which is exactly the
     * path every officer uses.</p>
     */
    public void linkText(PDFont f, float size, float x, float baselineY, String uri, float width)
            throws IOException {
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

    public void rect(float x, float yBottom, float w, float h) throws IOException {
        cs.setLineWidth(0.5f);
        cs.addRect(x, yBottom, w, h);
        cs.stroke();
    }

    // ---- text blocks ---------------------------------------------------------

    public void sectionTitle(String title) throws IOException {
        // One blank line between consecutive sections, so a title reads as the start of a new block
        // rather than a caption on the table it sits under. Not above the first section: the header
        // block already ends with a gap of its own. Applied before ensureSpace, so a title pushed onto
        // a fresh page drops the gap instead of carrying it down from the previous page's bottom.
        if (sectionDrawn) {
            moveDown(BLANK_LINE);
        }
        sectionDrawn = true;
        ensureSpace(40);
        y -= 18;
        text(bold, 13, MARGIN, y, title);
        y -= 14;
    }

    /** Header line centred across the content width — used for the report title. */
    public void centeredLine(PDFont f, float size, String s) throws IOException {
        y -= (size + 4);
        float x = MARGIN + (CONTENT_WIDTH - textWidth(f, size, s)) / 2f;
        text(f, size, x, y, s);
    }

    /**
     * Header line of the form {@code <bold label><value>} — both halves on one baseline, the value
     * starting where the label ends.
     */
    public void labelValueLine(float size, String label, String value) throws IOException {
        y -= (size + 4);
        text(bold, size, MARGIN, y, label);
        text(font, size, MARGIN + textWidth(bold, size, label), y, value);
    }

    /** Small note drawn directly under a table, e.g. an asterisk reference. */
    public void footnote(String s) throws IOException {
        ensureSpace(14);
        y -= 10;
        text(font, 8, MARGIN, y, s);
    }

    /** Renders {@code prefix + <clickable url> + suffix} on one line. */
    public void headerLinkLine(float size, String prefix, String url, String suffix) throws IOException {
        y -= (size + 4);
        float baseline = y;
        float x = MARGIN;
        text(font, size, x, baseline, prefix);
        x += textWidth(font, size, prefix);
        float urlWidth = textWidth(font, size, url);
        linkText(font, size, x, baseline, url, urlWidth);
        x += urlWidth;
        text(font, size, x, baseline, suffix);
    }

    // ---- tables --------------------------------------------------------------

    /** Bordered table with a bold header row; wraps cells and paginates, re-drawing the header. */
    public void drawTable(float[] colW, String[] header, List<String[]> rows,
                          float fontSize, float headerFontSize) throws IOException {
        drawTable(colW, header, rows, fontSize, headerFontSize, CELL_PAD, null);
    }

    /** As {@link #drawTable} but with per-column centre alignment ({@code center[c]} centres column c). */
    public void drawTable(float[] colW, String[] header, List<String[]> rows,
                          float fontSize, float headerFontSize, boolean[] center) throws IOException {
        drawTable(colW, header, rows, fontSize, headerFontSize, CELL_PAD, center);
    }

    /**
     * As {@link #drawTable} but with an explicit horizontal cell padding (for dense tables) and
     * optional per-column centre alignment ({@code center} may be {@code null} for all-left).
     *
     * <p>The header row is re-drawn at the top of every page it spills onto — a scheme list running
     * to a third page is unreadable without it.</p>
     */
    public void drawTable(float[] colW, String[] header, List<String[]> rows,
                          float fontSize, float headerFontSize, float cellPad, boolean[] center)
            throws IOException {
        float headerH = rowHeight(bold, headerFontSize, colW, header, cellPad);
        ensureSpace(headerH + rowHeight(font, fontSize, colW, rows.isEmpty() ? header : rows.get(0), cellPad));
        drawRow(colW, header, bold, headerFontSize, cellPad, center);
        for (String[] r : rows) {
            float h = rowHeight(font, fontSize, colW, r, cellPad);
            if (y - h < MARGIN) {
                newPage();
                drawRow(colW, header, bold, headerFontSize, cellPad, center);
            }
            drawRow(colW, r, font, fontSize, cellPad, center);
        }
    }

    private float rowHeight(PDFont f, float fontSize, float[] colW, String[] cells, float cellPad)
            throws IOException {
        float lh = fontSize * LINE_SPACING;
        int maxLines = 1;
        for (int c = 0; c < colW.length; c++) {
            int lines = wrapCell(f, fontSize, cellAt(cells, c), colW[c] - 2 * cellPad).size();
            maxLines = Math.max(maxLines, lines);
        }
        return maxLines * lh + 2 * cellPad;
    }

    private void drawRow(float[] colW, String[] cells, PDFont f, float fontSize, float cellPad,
                         boolean[] center) throws IOException {
        float lh = fontSize * LINE_SPACING;
        List<List<String>> wrapped = new ArrayList<>();
        int maxLines = 1;
        for (int c = 0; c < colW.length; c++) {
            List<String> lines = wrapCell(f, fontSize, cellAt(cells, c), colW[c] - 2 * cellPad);
            wrapped.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }
        float rowH = maxLines * lh + 2 * cellPad;
        float top = y;
        float cx = MARGIN;
        for (int c = 0; c < colW.length; c++) {
            boolean centered = center != null && c < center.length && center[c];
            float baseline = top - cellPad - fontSize;
            for (String line : wrapped.get(c)) {
                float tx = centered
                        ? cx + (colW[c] - textWidth(f, fontSize, line)) / 2f
                        : cx + cellPad;
                float[] trendColor = trendColor(line, cellAt(cells, 0));
                if (trendColor != null) {
                    colorText(f, fontSize, tx, baseline, line, trendColor[0], trendColor[1], trendColor[2]);
                } else {
                    text(f, fontSize, tx, baseline, line);
                }
                baseline -= lh;
            }
            rect(cx, top - rowH, colW[c], rowH);
            cx += colW[c];
        }
        y = top - rowH;
    }

    /**
     * The fill colour for a trend cell, or {@code null} to draw it in the default black.
     *
     * <p>Green means the officer is better off than last period, red worse — which for a KPI in
     * {@code negativeKpiLabels} (schemes NOT supplying, anomalies) is the opposite arrow direction.
     * Colouring by arrow direction instead would paint a fall in outages red.</p>
     */
    private float[] trendColor(String line, String kpiLabel) {
        boolean up = line.startsWith(UP.trim());
        boolean down = line.startsWith(DOWN.trim());
        if (!up && !down) {
            return null;
        }
        boolean inverted = negativeKpiLabels.contains(kpiLabel);
        boolean good = inverted != up;
        return good
                ? new float[]{TREND_GOOD_R, TREND_GOOD_G, TREND_GOOD_B}
                : new float[]{TREND_BAD_R, TREND_BAD_G, TREND_BAD_B};
    }

    private static String cellAt(String[] cells, int c) {
        return (c < cells.length && cells[c] != null) ? cells[c] : "";
    }

    // ---- text measurement / wrapping ----------------------------------------

    /** Wraps text to the given width (points), splitting on explicit newlines first, then words. */
    private List<String> wrapCell(PDFont f, float fontSize, String s, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            out.add("");
            return out;
        }
        for (String paragraph : s.split("\n", -1)) {
            wrapParagraph(f, fontSize, paragraph, maxWidth, out);
        }
        return out;
    }

    private void wrapParagraph(PDFont f, float fontSize, String paragraph, float maxWidth, List<String> out)
            throws IOException {
        if (paragraph.isEmpty()) {
            out.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            // A single word wider than the cell has to be broken mid-word or it overflows the border.
            // Checked before the wrap decision, because the over-wide word may well arrive mid-line (a
            // long URL or a run-together village name after a short one): pushing it onto a fresh line
            // and carrying on would leave it un-split and spilling over the table border, which is how
            // it used to escape — the old check only fired when the word happened to start a line.
            if (textWidth(f, fontSize, word) > maxWidth) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder();
                }
                hardSplit(f, fontSize, word, maxWidth, out);
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (textWidth(f, fontSize, candidate) <= maxWidth || line.length() == 0) {
                line = new StringBuilder(candidate);
            } else {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
    }

    private void hardSplit(PDFont f, float fontSize, String word, float maxWidth, List<String> out)
            throws IOException {
        StringBuilder chunk = new StringBuilder();
        for (char ch : word.toCharArray()) {
            if (textWidth(f, fontSize, chunk.toString() + ch) > maxWidth && chunk.length() > 0) {
                out.add(chunk.toString());
                chunk = new StringBuilder();
            }
            chunk.append(ch);
        }
        if (chunk.length() > 0) {
            out.add(chunk.toString());
        }
    }

    public float textWidth(PDFont f, float fontSize, String s) throws IOException {
        return f.getStringWidth(sanitize(f, s)) / 1000f * fontSize;
    }

    /** Replaces any character the embedded font cannot encode with '?' so showText never throws. */
    static String sanitize(PDFont f, String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
                return;
            }
            String ch = new String(Character.toChars(cp));
            try {
                f.getStringWidth(ch);
                sb.append(ch);
            } catch (Exception e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private static PDFont loadFont(PDDocument doc, String resourcePath) throws IOException {
        try (InputStream in = ReportPdfCanvas.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Embedded font not found on classpath: " + resourcePath);
            }
            return PDType0Font.load(doc, in);
        }
    }

    @Override
    public void close() throws IOException {
        if (cs != null) {
            cs.close();
        }
    }
}
