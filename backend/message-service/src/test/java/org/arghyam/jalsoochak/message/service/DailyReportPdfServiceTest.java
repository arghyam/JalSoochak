package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.arghyam.jalsoochak.message.dto.DailyReportSectionOfficerRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DailyReportPdfService} — verifies both role layouts render a valid, non-empty
 * PDF with the expected header, section numbering, date-headed value columns, polarity-aware trend
 * arrows, correct anomaly labels, and (when enabled) Priority Actions rows.
 */
class DailyReportPdfServiceTest {

    @TempDir
    Path tempDir;

    private DailyReportPdfService service;

    @BeforeEach
    void setUp() {
        service = new DailyReportPdfService();
        ReflectionTestUtils.setField(service, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(service, "dashboardUrl", "https://jalsoochak.jjmbrain.in/staff/");
        ReflectionTestUtils.setField(service, "supportPhone", "919999999999");
        // Default state: Priority Actions + Reasons for No Water Supply hidden.
        ReflectionTestUtils.setField(service, "outageDetailSectionsEnabled", false);
    }

    /** Restores the Priority Actions + Reasons for No Water Supply sections for a single test. */
    private void enableOutageSections() {
        ReflectionTestUtils.setField(service, "outageDetailSectionsEnabled", true);
    }

    private DailyReportKpis sampleKpis() {
        return DailyReportKpis.builder()
                .reportDate("2026-07-07")
                .previousDate("2026-07-06")
                .totalSchemes(148)
                .yesterday(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(142).schemesNotSupplying(6)
                        .avgLpcd(63).avgKld(18.4)
                        .regularSupplyPctWeek(92).readingSubmissionPct(97)
                        .anomalousCount(11).build())
                .previousDay(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(140).schemesNotSupplying(8)
                        .avgLpcd(61).avgKld(18.1)
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
                        .avgLpcd(67).avgKld(18432.5).regularSupplyPctWeek(32).readingSubmissionPct(78)
                        .anomalousCount(8).build(),
                DailyReportSectionOfficerRow.builder()
                        .officerName("Bob").officerMobile("919868595002")
                        .totalSchemes(90).schemesSupplying(80).schemesNotSupplying(10)
                        .avgLpcd(55).avgKld(9640.2).regularSupplyPctWeek(60).readingSubmissionPct(88)
                        .anomalousCount(2).build());
    }

    @Test
    void generate_numericCellsAreCentreAligned() throws Exception {
        // Column geometry mirrors DailyReportPdfService (MARGIN + sumCols {215,110,110,rest}).
        final float margin = 40f;
        final float cellPad = 6f;                         // CELL_PAD
        final float contentWidth = PDRectangle.A4.getWidth() - 2 * margin;
        final float yesterdayLeft = margin + 215f;        // start of the covered-day value column
        final float yesterdayRight = yesterdayLeft + 110f;
        final float trendLeft = margin + 215f + 110f + 110f;
        final float trendWidth = contentWidth - 435f;

        String filename = service.generate(
                sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", samplePriority(), List.of());
        List<Glyph> glyphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition p : positions) {
                        glyphs.add(new Glyph(p.getUnicode(), p.getXDirAdj(), p.getYDirAdj()));
                    }
                    super.writeString(text, positions);
                }
            };
            stripper.getText(doc);
        }

        // (1) The "no change" em dash is unique to the Trend column — assert it is centred there,
        // i.e. well to the right of where a left-aligned glyph (columnLeft + padding) would sit.
        Glyph dash = glyphs.stream().filter(g -> "—".equals(g.ch())).findFirst().orElse(null);
        assertThat(dash).as("em-dash rendered in the Trend column").isNotNull();
        assertThat(dash.x())
                .as("em dash is centred, not left-aligned")
                .isGreaterThan(trendLeft + cellPad + 15f)
                .isLessThan(trendLeft + trendWidth);

        // (2) The covered-day value column is centred → each row's value starts at a different x
        // (a left-aligned column would start every value at the same columnLeft + padding).
        TreeMap<Integer, Float> rowStartX = new TreeMap<>();
        for (Glyph g : glyphs) {
            if (g.ch() != null && g.ch().length() == 1 && Character.isDigit(g.ch().charAt(0))
                    && g.x() >= yesterdayLeft && g.x() < yesterdayRight) {
                rowStartX.merge(Math.round(g.y()), g.x(), Math::min);
            }
        }
        // Topmost rows (sorted by y) are the summary table's — before the Priority Actions table.
        long distinctStarts = rowStartX.values().stream().limit(8).distinct().count();
        assertThat(distinctStarts)
                .as("centred value column → per-row start-x varies (would be constant if left-aligned)")
                .isGreaterThan(1L);
    }

    /** A single rendered glyph and its top-left-origin position, captured for alignment assertions. */
    private record Glyph(String ch, float x, float y) {
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
    void generate_soReport_hidesOutageSectionsByDefaultAndHeadsValueColumnsWithDates() throws Exception {
        String text = renderText(sampleKpis(), samplePriority());

        assertThat(text).contains("Daily Water Service Situation Report");
        assertThat(text).contains("Officer: Binod Nimoli");
        // Role line rendered under the officer name (SECTION_OFFICER → "Section Officer").
        assertThat(text).contains("Role: Section Officer");
        // Outage sections are off by default, so Anomalous Submissions is section 2 for an SO.
        assertThat(text).contains("1. Summary");
        assertThat(text).contains("2. Anomalous Submissions");
        assertThat(text).doesNotContain("Priority Actions");
        assertThat(text).doesNotContain("Reasons for No Water Supply");
        // The two value columns are headed by their dates alone — no "Yesterday"/"Previous Day" label.
        assertThat(text).contains("07-Jul-2026");
        assertThat(text).contains("06-Jul-2026");
        assertThat(text).doesNotContain("Yesterday");
        assertThat(text).doesNotContain("Previous Day");
    }

    @Test
    void generate_soReport_withOutageSectionsEnabled_rendersAllFourSectionsInOrder() throws Exception {
        enableOutageSections();

        String text = renderText(sampleKpis(), samplePriority());

        assertThat(text).contains("1. Summary");
        assertThat(text).contains("2. Priority Actions");
        assertThat(text).contains("3. Reasons for No Water Supply");
        assertThat(text).contains("4. Anomalous Submissions");
    }

    @Test
    void generate_sdoReport_withOutageSectionsEnabled_shiftsNumberingByOne() throws Exception {
        enableOutageSections();

        String text = renderSdoText();

        // The SDO report has the extra Key Performance Indicators section, so everything shifts by one.
        assertThat(text).contains("1. Summary");
        assertThat(text).contains("2. Key Performance Indicators");
        assertThat(text).contains("3. Priority Actions");
        assertThat(text).contains("4. Reasons for No Water Supply");
        assertThat(text).contains("5. Anomalous Submissions");
        // The Summary footnote tracks the shifted Anomalous Submissions section number.
        assertThat(text.replaceAll("\\s+", " "))
                .contains("Please refer to section 5 Anomalous Submissions");
    }

    @Test
    void generate_headerShowsGenerationDateAndDatedReportingPeriod() throws Exception {
        String text = renderText(sampleKpis(), samplePriority()).replaceAll("\\s+", " ");

        // reportDate is the covered day (07 Jul); the report is generated the following day.
        assertThat(text).contains("Report Generation Date: 08 July 2026");
        // The Reporting Period now names the day it covers, not just the clock window.
        assertThat(text).contains("Reporting Period: 07 July 2026, 00:00 hrs - 23:59 hrs");
    }

    @Test
    void generate_titleIsHorizontallyCentredAndFieldLabelsAreBold() throws Exception {
        String filename = service.generate(
                sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", samplePriority(), List.of());
        List<StyledGlyph> glyphs = styledGlyphs(tempDir.resolve(filename));

        // (1) The title's first glyph sits well right of the left margin — a left-aligned title would
        // start at MARGIN (40pt) exactly.
        StyledGlyph titleStart = glyphs.stream()
                .filter(g -> "D".equals(g.ch()) && g.size() > 14f)
                .findFirst().orElse(null);
        assertThat(titleStart).as("title glyph captured").isNotNull();
        assertThat(titleStart.x())
                .as("title is centred, not left-aligned at the margin")
                .isGreaterThan(100f);

        // (2) Field labels are bold and their values are not. "Binod" is unique to the Officer value;
        // "Role" is unique to a label.
        assertThat(fontOfFirstGlyphAfter(glyphs, "Role")).contains("Bold");
        assertThat(fontOfFirstGlyphAfter(glyphs, "Binod")).doesNotContain("Bold");
    }

    /** The font name of the glyph starting the first occurrence of {@code token} in reading order. */
    private String fontOfFirstGlyphAfter(List<StyledGlyph> glyphs, String token) {
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < glyphs.size(); i++) {
            seen.append(glyphs.get(i).ch());
            if (seen.toString().endsWith(token)) {
                return glyphs.get(i - token.length() + 1).font();
            }
        }
        throw new AssertionError("token not found in rendered text: " + token);
    }

    private List<StyledGlyph> styledGlyphs(Path pdf) throws Exception {
        List<StyledGlyph> glyphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition p : positions) {
                        glyphs.add(new StyledGlyph(p.getUnicode(), p.getXDirAdj(),
                                p.getFont().getName(), p.getFontSizeInPt()));
                    }
                    super.writeString(text, positions);
                }
            };
            stripper.getText(doc);
        }
        return glyphs;
    }

    /** A rendered glyph with the styling needed to assert centring and bold labels. */
    private record StyledGlyph(String ch, float x, String font, float size) {
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
        // Average KLD 18.4 vs 18.1 → +0.3, rounded (no floating-point long-decimal leak).
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
        enableOutageSections();
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
        enableOutageSections();
        DailyReportKpis kpis = sampleKpis();
        kpis.setReasonsForNoSupply(List.of());
        kpis.setAnomaliesByType(List.of());

        String text = renderText(kpis, List.of()).replaceAll("\\s+", " ");

        assertThat(text).contains("No priority actions");   // wide/narrow-cell empty states
        assertThat(text).contains("No outages reported");
        assertThat(text).contains("No anomalies");
    }

    @Test
    void generate_sdoReport_splitsSummaryAndKpiTableIntoSeparateSections() throws Exception {
        String filename = service.generate(
                sampleKpis(), 700L, "SDO Kumar", "SUB_DIVISIONAL_OFFICER", samplePriority(), sampleSectionOfficers());
        assertThat(filename).startsWith("daily_report_SUB_DIVISIONAL_OFFICER_700_2026-07-07");

        String text;
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        // Role line for the SDO (SUB_DIVISIONAL_OFFICER → "Sub Divisional Officer").
        assertThat(text).contains("Role: Sub Divisional Officer");
        // Section 1 is the per-Section-Officer breakdown; the KPI table is its own section 2.
        assertThat(text).contains("Section Officer");   // breakdown header token
        assertThat(text).contains("Alice");
        assertThat(text).contains("Bob");
        assertThat(text).contains("1. Summary");
        assertThat(text).contains("2. Key Performance Indicators");
        assertThat(text).contains("3. Anomalous Submissions");
        // The breakdown table is drawn before the KPI table: the first officer name precedes both the
        // Key Performance Indicators heading and that table's "KPI" column header.
        assertThat(text.indexOf("Alice")).isLessThan(text.indexOf("2. Key Performance Indicators"));
        assertThat(text.indexOf("2. Key Performance Indicators")).isLessThan(text.indexOf("KPI"));
    }

    @Test
    void generate_sdoReport_marksAnomalousColumnWithFootnoteReference() throws Exception {
        String filename = service.generate(
                sampleKpis(), 700L, "SDO Kumar", "SUB_DIVISIONAL_OFFICER", samplePriority(), sampleSectionOfficers());

        // The footnote is a single full-width 8pt line, so it survives text extraction intact and
        // names the section that breaks the anomalies down by type.
        String text;
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text.replaceAll("\\s+", " "))
                .contains("* Please refer to section 3 Anomalous Submissions for more information.");

        // The asterisk it refers to is on the breakdown table's Anomalous Submissions column header.
        // That header wraps across several lines in its narrow column, so assert on the glyph instead
        // of the extracted text: 7pt is the breakdown table's font size and is used nowhere else (the
        // footnote and the support-phone footer are both 8pt).
        boolean asteriskInTableHeader = styledGlyphs(tempDir.resolve(filename)).stream()
                .anyMatch(g -> "*".equals(g.ch()) && Math.abs(g.size() - 7f) < 0.01f);
        assertThat(asteriskInTableHeader)
                .as("Anomalous Submissions column header carries the footnote asterisk")
                .isTrue();
    }

    @Test
    void generate_soReport_hasNoFootnoteReference() throws Exception {
        // The footnote belongs to the SDO breakdown table, which a SECTION_OFFICER report never draws.
        String text = renderText(sampleKpis(), samplePriority()).replaceAll("\\s+", " ");

        assertThat(text).doesNotContain("Please refer to section");
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
    void generate_sdoReport_kldValueFitsOnOneLineInSummaryTable() throws Exception {
        // The KLD column carries values a thousand times larger than the MLD figures it replaced
        // (18.4 MLD → 18400 KLD), so a five-digit-plus-decimal value has to stay inside the narrow
        // breakdown column. A wrapped cell would split the number across two lines ("18432"+".5")
        // and read as a different figure, so assert PDFTextStripper extracts it as one token.
        String filename = service.generate(
                sampleKpis(), 700L, "SDO Kumar", "SUB_DIVISIONAL_OFFICER", samplePriority(), sampleSectionOfficers());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            String text = new PDFTextStripper().getText(doc);
            // Alice's 7-character value and Bob's, each as one unbroken token.
            assertThat(text).contains("18432.5", "9640.2");
        }
    }

    @Test
    void generate_kpiTableLabelsKldNotMld() throws Exception {
        String text = renderText(sampleKpis(), samplePriority()).replaceAll("\\s+", " ");

        assertThat(text).contains("Average KLD").doesNotContain("Average MLD");
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
                    .anyMatch("https://jalsoochak.jjmbrain.in/staff/"::equals);
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

    @Test
    void generate_trendArrowsAreColoured_greenForImprovement_redForDeterioration() throws Exception {
        // Positive KPIs: Supplying 142 vs 140 → up arrow, green. Negative KPIs: Not-supplying 6 vs 8
        // and Anomalous 11 vs 15 → down arrows, also green because falling is the improvement there.
        // Average KLD 18.4 vs 18.1 → up, green. Regular Supply 92 vs 97 → down on a positive KPI, red.
        // Rendered to a raster and pixel-sampled: everything else in the report is black text, white
        // background, or a blue link, so strongly green / red pixels can only be the trend arrows.
        String filename = service.generate(
                sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", samplePriority(), List.of());
        ArrowPixels pixels = arrowPixels(tempDir.resolve(filename));

        assertThat(pixels.green()).as("green (improving) arrows present").isPositive();
        assertThat(pixels.red()).as("red (deteriorating) arrows present").isPositive();
    }

    @Test
    void generate_negativeKpiRising_isColouredRed() throws Exception {
        // Only "Schemes Not Supplying Water" moves, and it rises — a deterioration despite the UP
        // arrow, so the sole arrow in the document must be red with no green anywhere.
        String filename = service.generate(
                singleMoverKpis(6, 4, 11, 11), 500L, "Binod Nimoli", "SECTION_OFFICER", List.of(), List.of());
        ArrowPixels pixels = arrowPixels(tempDir.resolve(filename));

        assertThat(pixels.red()).as("rising negative KPI is red").isPositive();
        assertThat(pixels.green()).as("no improving arrow in the document").isZero();
    }

    @Test
    void generate_negativeKpiFalling_isColouredGreen() throws Exception {
        // Only "Anomalous Submissions" moves, and it falls — an improvement despite the DOWN arrow,
        // so the sole arrow must be green with no red anywhere.
        String filename = service.generate(
                singleMoverKpis(6, 6, 11, 15), 500L, "Binod Nimoli", "SECTION_OFFICER", List.of(), List.of());
        ArrowPixels pixels = arrowPixels(tempDir.resolve(filename));

        assertThat(pixels.green()).as("falling negative KPI is green").isPositive();
        assertThat(pixels.red()).as("no deteriorating arrow in the document").isZero();
    }

    /**
     * KPIs where every positive-polarity metric is identical across the two days — so the only trend
     * arrows the report can draw come from the two negative KPIs, whose values are given here.
     */
    private DailyReportKpis singleMoverKpis(int notSupplyingYesterday, int notSupplyingPrevious,
                                            int anomalousYesterday, int anomalousPrevious) {
        return DailyReportKpis.builder()
                .reportDate("2026-07-07")
                .previousDate("2026-07-06")
                .totalSchemes(148)
                .yesterday(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(142).schemesNotSupplying(notSupplyingYesterday)
                        .avgLpcd(63).avgKld(18.4)
                        .regularSupplyPctWeek(92).readingSubmissionPct(97)
                        .anomalousCount(anomalousYesterday).build())
                .previousDay(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(142).schemesNotSupplying(notSupplyingPrevious)
                        .avgLpcd(63).avgKld(18.4)
                        .regularSupplyPctWeek(92).readingSubmissionPct(97)
                        .anomalousCount(anomalousPrevious).build())
                .reasonsForNoSupply(List.of())
                .anomaliesByType(List.of())
                .build();
    }

    /** Strongly-green and strongly-red pixel counts of a rendered page — i.e. the trend arrows. */
    private record ArrowPixels(int green, int red) {
    }

    private ArrowPixels arrowPixels(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 150);
            int greenPixels = 0;
            int redPixels = 0;
            for (int py = 0; py < img.getHeight(); py++) {
                for (int px = 0; px < img.getWidth(); px++) {
                    int rgb = img.getRGB(px, py);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (g > r + 40 && g > b + 40) {
                        greenPixels++;
                    }
                    if (r > g + 40 && r > b + 40) {
                        redPixels++;
                    }
                }
            }
            return new ArrowPixels(greenPixels, redPixels);
        }
    }

    private String renderText(DailyReportKpis kpis, List<DailyReportPriorityRow> priority) throws Exception {
        String filename = service.generate(kpis, 500L, "Binod Nimoli", "SECTION_OFFICER", priority, List.of());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String renderSdoText() throws Exception {
        String filename = service.generate(
                sampleKpis(), 700L, "SDO Kumar", "SUB_DIVISIONAL_OFFICER", samplePriority(), sampleSectionOfficers());
        try (PDDocument doc = Loader.loadPDF(tempDir.resolve(filename).toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
