package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.BLANK_LINE;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.CONTENT_WIDTH;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.MARGIN;
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
                 ReportPdfCanvas c = new ReportPdfCanvas(doc, Set.of())) {
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
                 ReportPdfCanvas c = new ReportPdfCanvas(doc, Set.of())) {
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
                 ReportPdfCanvas c = new ReportPdfCanvas(doc, Set.of())) {
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
}
