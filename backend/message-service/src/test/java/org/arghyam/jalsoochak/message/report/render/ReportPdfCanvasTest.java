package org.arghyam.jalsoochak.message.report.render;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.BLANK_LINE;
import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.CELL_PAD;
import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.CONTENT_WIDTH;
import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.MARGIN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared drawing surface {@link ReportPdfCanvas}: the section spacing both water
 * reports inherit from it. Asserted on the cursor rather than on extracted text, because vertical
 * whitespace is exactly what a text extractor throws away.
 */
class ReportPdfCanvasTest {

    @Nested
    @DisplayName("section spacing")
    class SectionSpacing {

        @Test
        void separatesConsecutiveSectionsByOneBlankLine() throws Exception {
            try (PDDocument doc = new PDDocument();
                 ReportPdfCanvas c = new ReportPdfCanvas(doc)) {
                c.newPage();

                float beforeFirst = c.y();
                c.sectionTitle("1. Summary");
                float afterFirst = c.y();
                c.sectionTitle("2. Scheme with No Water Supply");
                float afterSecond = c.y();

                float firstTitleDrop = beforeFirst - afterFirst;
                float secondTitleDrop = afterFirst - afterSecond;

                assertThat(secondTitleDrop).isEqualTo(firstTitleDrop + BLANK_LINE);
            }
        }

        @Test
        void doesNotIndentTheFirstSectionAwayFromTheHeaderBlock() throws Exception {
            // The header block already closes with a gap of its own, so a gap above section 1 as well
            // would leave the page top looking unbalanced against every following section.
            try (PDDocument doc = new PDDocument();
                 ReportPdfCanvas c = new ReportPdfCanvas(doc)) {
                c.newPage();

                float top = c.y();
                c.sectionTitle("1. Summary");

                assertThat(top - c.y()).isLessThan(BLANK_LINE + 32f);
            }
        }

        @Test
        void dropsTheGapForASectionPushedOntoAFreshPage() throws Exception {
            // Carried down, the gap would push the title away from a page top it is already flush with.
            try (PDDocument doc = new PDDocument();
                 ReportPdfCanvas c = new ReportPdfCanvas(doc)) {
                c.newPage();
                c.sectionTitle("1. Summary");
                float firstTitleDrop = ReportPdfCanvas.PAGE_HEIGHT - MARGIN - c.y();

                // Fill the page so the next section title cannot fit on it.
                List<String[]> oneRow = List.<String[]>of(new String[]{"row"});
                while (c.y() > MARGIN + 60f) {
                    c.drawTable(new float[]{CONTENT_WIDTH}, new String[]{"Parameters"}, oneRow, 10f, 10f);
                }

                c.sectionTitle("2. Scheme with No Water Supply");

                assertThat(doc.getNumberOfPages()).isEqualTo(2);
                assertThat(ReportPdfCanvas.PAGE_HEIGHT - MARGIN - c.y()).isEqualTo(firstTitleDrop);
            }
        }
    }

    @Nested
    @DisplayName("trend colouring")
    class TrendColouring {

        private static final String GOOD = "0.13 0.55 0.13";
        private static final String BAD = "0.80 0.11 0.11";

        @Test
        void invertsTheRowsItIsToldToEvenWhenTheirLabelsAreTheSame() throws Exception {
            // Rows are told apart by position, not by label: two KPIs whose labels print the same must
            // still each get their own colouring.
            List<String[]> rows = List.of(new String[]{"Schemes", ReportPdfCanvas.UP + "2"},
                    new String[]{"Schemes", ReportPdfCanvas.UP + "2"});

            try (PDDocument doc = new PDDocument()) {
                try (ReportPdfCanvas c = new ReportPdfCanvas(doc)) {
                    c.newPage();
                    c.drawTable(new float[]{CONTENT_WIDTH / 2, CONTENT_WIDTH / 2}, new String[]{"KPI", "Trend"},
                            rows, 10f, 10f, CELL_PAD, null, new boolean[]{true, false});
                }

                assertThat(fillColours(doc)).containsExactly(BAD, GOOD);
            }
        }

        @Test
        void invertsNoRowWhenNotTold() throws Exception {
            List<String[]> rows = List.<String[]>of(new String[]{"Schemes", ReportPdfCanvas.UP + "2"});

            try (PDDocument doc = new PDDocument()) {
                try (ReportPdfCanvas c = new ReportPdfCanvas(doc)) {
                    c.newPage();
                    c.drawTable(new float[]{CONTENT_WIDTH / 2, CONTENT_WIDTH / 2}, new String[]{"KPI", "Trend"},
                            rows, 10f, 10f);
                }

                assertThat(fillColours(doc)).containsExactly(GOOD);
            }
        }

        /** Every non-black fill colour the first page sets, in drawing order, as {@code "r g b"}. */
        private static List<String> fillColours(PDDocument doc) throws IOException {
            List<Object> tokens = new PDFStreamParser(doc.getPage(0)).parse();
            List<String> colours = new ArrayList<>();
            for (int i = 3; i < tokens.size(); i++) {
                if (tokens.get(i) instanceof Operator op && op.getName().equals("rg")) {
                    String colour = String.format(Locale.ROOT, "%.2f %.2f %.2f",
                            ((COSNumber) tokens.get(i - 3)).floatValue(),
                            ((COSNumber) tokens.get(i - 2)).floatValue(),
                            ((COSNumber) tokens.get(i - 1)).floatValue());
                    if (!colour.equals("0.00 0.00 0.00")) {
                        colours.add(colour);
                    }
                }
            }
            return colours;
        }
    }
}
