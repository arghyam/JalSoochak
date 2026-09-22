package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DailyReportPdfService}: the summary figures as they appear on the page, the
 * two scheme sections, the filename, and the header's reporting-period line.
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
    }

    private DailyReportKpis sampleKpis() {
        return DailyReportKpis.builder()
                .reportDate("2026-07-19")
                .cutoffIst("2026-07-19T16:00:00")
                .totalSchemes(148)
                .schemesSupplying(142)
                .schemesNotSupplying(6)
                .householdsWithSupply(14200)
                .householdsWithSupplyPct(95.9)
                .householdsWithoutSupply(600)
                .householdsWithoutSupplyPct(4.1)
                .totalHouseholds(14800)
                .avgLpcd(63.0)
                .anomalousCount(11)
                .noSupplySchemeIds(List.of(1, 2))
                .schemeAnomalies(List.of())
                .build();
    }

    private List<ReportSchemeRow> sampleNoSupply() {
        return List.of(
                ReportSchemeRow.builder().schemeId(1).schemeName("Rampur").imisId("98767")
                        .jalMitraNames("Ramesh").jalMitraMobiles("919000000001").build(),
                ReportSchemeRow.builder().schemeId(2).schemeName("Bhagwanpur").imisId("98769")
                        .jalMitraNames("Suresh").jalMitraMobiles("919000000002").build());
    }

    private List<ReportSchemeRow> sampleAnomalies() {
        return List.of(
                ReportSchemeRow.builder().schemeId(1).schemeName("Rampur").imisId("98767")
                        .anomalyType("Reading Less Than Previous")
                        .jalMitraNames("Ramesh").jalMitraMobiles("919000000001").build(),
                ReportSchemeRow.builder().schemeId(3).schemeName("Kishanganj").imisId("98781")
                        .anomalyType("Duplicate Image")
                        .jalMitraNames("Mahesh").jalMitraMobiles("919000000003").build());
    }

    /**
     * Renders and extracts the text with runs of whitespace collapsed, so an assertion on a value is
     * not defeated by the cell it happens to wrap inside.
     */
    private String render(DailyReportKpis kpis, List<ReportSchemeRow> noSupply, List<ReportSchemeRow> anomalies)
            throws IOException {
        return renderRaw(kpis, noSupply, anomalies).replaceAll("\\s+", " ");
    }

    /**
     * As {@link #render} but keeping the line breaks, so an assertion can tell a label that fits on one
     * line from one the cell wrapped — which collapsing the whitespace hides.
     */
    private String renderRaw(DailyReportKpis kpis, List<ReportSchemeRow> noSupply,
                             List<ReportSchemeRow> anomalies) throws IOException {
        Path pdf = service.generate(kpis, 21343L, "Binod Nimoli", "SECTION_OFFICER", noSupply, anomalies);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Nested
    @DisplayName("filename")
    class Filename {

        @Test
        void namesTheFileByRoleOfficerIdAndDate() throws Exception {
            Path pdf = service.generate(sampleKpis(), 21343L, "Binod Nimoli", "SECTION_OFFICER",
                    List.of(), List.of());

            assertThat(pdf.getFileName().toString())
                    .isEqualTo("daily_water_report_SECTION_OFFICER_21343_2026-07-19.pdf");
        }

        @Test
        void usesTheOfficerIdNotTheNameSoTwoOfficersCannotCollide() throws Exception {
            // A shared bucket plus a name-based filename would let one officer's report overwrite
            // another's — handing the second officer the first one's scheme list and Jal Mitra phones.
            Path first = service.generate(sampleKpis(), 1L, "R Kumar", "SECTION_OFFICER", List.of(), List.of());
            Path second = service.generate(sampleKpis(), 2L, "R Kumar", "SECTION_OFFICER", List.of(), List.of());

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void returnsThePathThePdfWasActuallyWrittenTo() throws Exception {
            Path pdf = service.generate(sampleKpis(), 21343L, "Binod Nimoli", "SECTION_OFFICER",
                    List.of(), List.of());

            // The caller uploads this path verbatim. It used to rebuild it from escalation.report.dir,
            // which is a different property from the daily-report.report.dir written to here.
            assertThat(pdf).exists().hasParent(tempDir);
        }
    }

    @Nested
    @DisplayName("cell wrapping")
    class CellWrapping {

        /** Far wider than any column in either layout, at any of the font sizes they use. */
        private static final String OVERSIZED = "Bhagwanpurkalanjhansikhurdmajragarhiparganatehsilblockvillagescheme";

        @Test
        void breaksAnOversizedWordThatStartsACell() throws Exception {
            String text = render(sampleKpis(),
                    List.of(ReportSchemeRow.builder().schemeId(1).schemeName(OVERSIZED).imisId("98767")
                            .jalMitraNames("Ramesh").jalMitraMobiles("919000000001").build()),
                    List.of());

            assertThat(text).doesNotContain(OVERSIZED);
        }

        @Test
        void breaksAnOversizedWordThatFollowsOtherTextInTheSameCell() throws Exception {
            // The wrap check used to fire only for a word that began a line, so an over-wide token
            // arriving after a short one was pushed onto a fresh line and left un-split — printing
            // straight through the table border and over the neighbouring column.
            String text = render(sampleKpis(),
                    List.of(ReportSchemeRow.builder().schemeId(1).schemeName("Rampur " + OVERSIZED)
                            .imisId("98767").jalMitraNames("Ramesh").jalMitraMobiles("919000000001").build()),
                    List.of());

            assertThat(text).doesNotContain(OVERSIZED);
            assertThat(text).contains("Rampur");
        }
    }

    @Nested
    @DisplayName("header")
    class Header {

        @Test
        void namesTheSectionOfficerAndTheDay() throws Exception {
            String text = render(sampleKpis(), sampleNoSupply(), sampleAnomalies());

            assertThat(text).contains("Daily Water Service Situation Report");
            assertThat(text).contains("Section-Officer: Binod Nimoli");
            assertThat(text).contains("19 July 2026");
        }

        @Test
        void statesTheWindowItActuallyCovers() throws Exception {
            String text = render(sampleKpis(), List.of(), List.of());

            assertThat(text).contains("00:00 hrs – 16:00 hrs");
        }

        @Test
        void followsTheConfiguredCutoffRatherThanAssuming1600() throws Exception {
            // A tenant that moves its cron must not be told the report covers hours it does not.
            DailyReportKpis kpis = sampleKpis();
            kpis.setCutoffIst("2026-07-19T17:30:00");

            String text = render(kpis, List.of(), List.of());

            assertThat(text).contains("00:00 hrs – 17:30 hrs");
            assertThat(text).contains("Up to 17:30");
        }

        @Test
        void fallsBackToTheTemplateTimeWhenTheCutoffIsMissing() throws Exception {
            DailyReportKpis kpis = sampleKpis();
            kpis.setCutoffIst(null);

            assertThat(render(kpis, List.of(), List.of())).contains("00:00 hrs – 16:00 hrs");
        }

        @Test
        void embedsTheDashboardLinkAsARealAnnotation() throws Exception {
            // WhatsApp's PDF viewer does not auto-detect bare URLs, so without the annotation the link
            // is dead on exactly the path every officer uses.
            Path pdf = service.generate(sampleKpis(), 21343L, "Binod Nimoli", "SECTION_OFFICER",
                    List.of(), List.of());

            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                List<PDAnnotation> annotations = doc.getPage(0).getAnnotations();
                assertThat(annotations).anySatisfy(a -> {
                    assertThat(a).isInstanceOf(PDAnnotationLink.class);
                    PDAnnotationLink link = (PDAnnotationLink) a;
                    assertThat(((PDActionURI) link.getAction()).getURI())
                            .isEqualTo("https://jalsoochak.jjmbrain.in/staff/");
                });
            }
        }
    }

    @Nested
    @DisplayName("summary section")
    class Summary {

        @Test
        void rendersEverySummaryRow() throws Exception {
            String text = render(sampleKpis(), sampleNoSupply(), sampleAnomalies());

            assertThat(text).contains("1. Summary");
            assertThat(text).contains("Total Schemes (Handed Over Schemes only)");
            assertThat(text).contains("148");
            assertThat(text).contains("Schemes Supplying Water");
            assertThat(text).contains("142");
            assertThat(text).contains("Average LPCD");
            assertThat(text).contains("63");
            assertThat(text).contains("Number of Anomalous Submissions");
            assertThat(text).contains("11");
        }

        @Test
        void showsHouseholdCountsWithTheirPercentage() throws Exception {
            String text = render(sampleKpis(), List.of(), List.of());

            assertThat(text).contains("14200 (95.9%)");
            assertThat(text).contains("600 (4.1%)");
        }

        @Test
        void footnotesThatTheLpcdCoversOnlySupplyingSchemes() throws Exception {
            // Load-bearing: this LPCD divides by the supplying schemes' population, so it reads higher
            // than an LPCD over the whole command area and has to say why.
            String text = render(sampleKpis(), List.of(), List.of());

            assertThat(text).contains("of schemes that have supplied water");
        }

        @Test
        void rendersAnOfficerWithNoSchemesWithoutFailing() throws Exception {
            DailyReportKpis empty = DailyReportKpis.builder()
                    .reportDate("2026-07-19")
                    .cutoffIst("2026-07-19T16:00:00")
                    .noSupplySchemeIds(List.of())
                    .schemeAnomalies(List.of())
                    .build();

            String text = render(empty, List.of(), List.of());

            assertThat(text).contains("1. Summary");
            assertThat(text).contains("0");
        }
    }

    @Nested
    @DisplayName("scheme sections")
    class SchemeSections {

        @Test
        void listsSchemesWithNoWaterSupply() throws Exception {
            String text = render(sampleKpis(), sampleNoSupply(), sampleAnomalies());

            assertThat(text).contains("2. Scheme with No Water Supply");
            assertThat(text).contains("98767");
            assertThat(text).contains("Rampur");
            assertThat(text).contains("Ramesh");
            assertThat(text).contains("919000000001");
        }

        @Test
        void headsTheImisColumnWithAReadableLabelOnOneLine() throws Exception {
            // Read by officers, not by the database: the column carries the scheme's IMIS id, so it is
            // labelled as prose rather than as the `scheme_imis_id` column it is sourced from. Asserted
            // on the un-collapsed text, so the column staying wide enough to hold it unwrapped is part
            // of the claim — a wrapped heading costs a row of height on every page the table spills to.
            String text = renderRaw(sampleKpis(), sampleNoSupply(), sampleAnomalies());

            assertThat(countOccurrences(text, "Scheme IMIS Id")).isEqualTo(2); // both scheme sections
            assertThat(text).doesNotContain("Scheme_imis_id");
        }

        @Test
        void listsAnomalousSubmissionsWithTheirType() throws Exception {
            String text = render(sampleKpis(), sampleNoSupply(), sampleAnomalies());

            assertThat(text).contains("3. Schemes with Anomalous Submissions");
            assertThat(text).contains("Reading Less Than Previous");
            assertThat(text).contains("Duplicate Image");
            assertThat(text).contains("Kishanganj");
        }

        @Test
        void saysNoneRatherThanLeavingAnEmptySectionLookingBroken() throws Exception {
            String text = render(sampleKpis(), List.of(), List.of());

            assertThat(text).contains("2. Scheme with No Water Supply");
            assertThat(text).contains("None");
        }

        @Test
        void paginatesALongSchemeListAndRepeatsTheHeader() throws Exception {
            // At 16:00 most of an officer's schemes may legitimately not have supplied yet, so this is
            // an ordinary afternoon rather than an edge case — and a continuation page without its
            // header is a column of unlabelled numbers.
            List<ReportSchemeRow> many = new java.util.ArrayList<>();
            for (int i = 1; i <= 120; i++) {
                many.add(ReportSchemeRow.builder().schemeId(i).schemeName("Scheme " + i)
                        .imisId("IMIS" + i).jalMitraNames("Operator " + i)
                        .jalMitraMobiles("9190000" + String.format("%05d", i)).build());
            }

            Path pdf = service.generate(sampleKpis(), 21343L, "Binod Nimoli", "SECTION_OFFICER",
                    many, List.of());

            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                assertThat(doc.getNumberOfPages()).isGreaterThan(1);
                String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
                assertThat(text).contains("Scheme 1");
                assertThat(text).contains("Scheme 120");
                // The header row is re-drawn on each page it spills onto.
                assertThat(countOccurrences(text, "Scheme IMIS Id")).isGreaterThan(1);
            }
        }

        @Test
        void rendersASchemeWithNoMappedJalMitra() throws Exception {
            // A scheme with no operator is itself the actionable finding, so the row must survive.
            String text = render(sampleKpis(),
                    List.of(ReportSchemeRow.builder().schemeId(9).schemeName("Orphan").imisId("99999").build()),
                    List.of());

            assertThat(text).contains("Orphan");
            assertThat(text).contains("99999");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
