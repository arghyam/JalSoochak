package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
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
 * Unit tests for {@link WeeklyReportPdfService}: the two role layouts, the trend column, and the
 * scheme lists each variant carries.
 */
class WeeklyReportPdfServiceTest {

    @TempDir
    Path tempDir;

    private WeeklyReportPdfService service;

    private static final String SO = "SECTION_OFFICER";
    private static final String SDO = "SUB_DIVISIONAL_OFFICER";

    @BeforeEach
    void setUp() {
        service = new WeeklyReportPdfService();
        ReflectionTestUtils.setField(service, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(service, "dashboardUrl", "https://jalsoochak.jjmbrain.in/staff/");
        ReflectionTestUtils.setField(service, "supportPhone", "919999999999");
    }

    private WeeklyReportKpis kpis(int supplyingThisWeek, int supplyingLastWeek) {
        return WeeklyReportKpis.builder()
                .weekStart("2026-07-13")
                .weekEnd("2026-07-19")
                .previousWeekStart("2026-07-06")
                .previousWeekEnd("2026-07-12")
                .week(WeeklyReportKpis.WeekKpis.builder()
                        .totalSchemes(148).schemesSupplying(supplyingThisWeek).schemesNotSupplying(6)
                        .schemesLowLpcd(4).avgLpcd(63).build())
                .previousWeek(WeeklyReportKpis.WeekKpis.builder()
                        .totalSchemes(148).schemesSupplying(supplyingLastWeek).schemesNotSupplying(8)
                        .schemesLowLpcd(7).avgLpcd(61).build())
                .noSupplySchemeIds(List.of(1))
                .lowSupplyDaysSchemeIds(List.of(2))
                .lowLpcdSchemeIds(List.of(3))
                .sectionOfficerSummaries(List.of())
                .build();
    }

    private static List<ReportSchemeRow> schemeRows(String name) {
        return List.of(ReportSchemeRow.builder().schemeId(1).schemeName(name)
                .jalMitraNames("Ramesh").jalMitraMobiles("919000000001")
                .sectionOfficerNames("Alice").sectionOfficerMobiles("919868595001")
                .villageNames("Rampur, Kishanganj").build());
    }

    private String render(String role, WeeklyReportKpis kpis,
                          List<ReportSchemeRow> noSupply, List<ReportSchemeRow> lowDays,
                          List<ReportSchemeRow> lowLpcd, List<WeeklyReportOfficerRow> officers)
            throws IOException {
        Path pdf = service.generate(kpis, 5521L, "Bharat Sharma", role,
                noSupply, lowDays, lowLpcd, officers);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Nested
    @DisplayName("filename")
    class Filename {

        @Test
        void carriesTheFullWeekRange() throws Exception {
            Path pdf = service.generate(kpis(142, 140), 21343L, "Binod", SO,
                    List.of(), List.of(), List.of(), List.of());

            assertThat(pdf.getFileName().toString())
                    .isEqualTo("weekly_water_report_SECTION_OFFICER_21343_2026-07-13_to_2026-07-19.pdf");
        }

        @Test
        void distinguishesTheRoles() throws Exception {
            Path so = service.generate(kpis(142, 140), 1L, "X", SO,
                    List.of(), List.of(), List.of(), List.of());
            Path sdo = service.generate(kpis(142, 140), 1L, "X", SDO,
                    List.of(), List.of(), List.of(), List.of());

            assertThat(so.getFileName().toString()).contains("SECTION_OFFICER");
            assertThat(sdo.getFileName().toString()).contains("SUB_DIVISIONAL_OFFICER");
        }

        @Test
        void returnsThePathThePdfWasActuallyWrittenTo() throws Exception {
            Path pdf = service.generate(kpis(142, 140), 21343L, "Binod", SO,
                    List.of(), List.of(), List.of(), List.of());

            // The caller uploads this path verbatim rather than rebuilding it from a directory
            // property of its own, which resolved to a different directory whenever
            // weekly-report.report.dir or daily-report.report.dir was set.
            assertThat(pdf).exists().hasParent(tempDir);
        }
    }

    @Nested
    @DisplayName("Section Officer layout")
    class SectionOfficerLayout {

        @Test
        void rendersTheFourSectionsInOrder() throws Exception {
            String text = render(SO, kpis(142, 140), schemeRows("Rampur"), schemeRows("Bhagwanpur"),
                    schemeRows("Kishanganj"), List.of());

            assertThat(text).contains("1. Summary");
            assertThat(text).contains("2. List of Schemes With no Supply");
            assertThat(text).contains("3. List of Schemes With 1 to 3 days of supply");
            assertThat(text).contains("4. List of Schemes with <=15 LPCD supply over the Week");
            assertThat(text.indexOf("2. List of Schemes With no Supply"))
                    .isLessThan(text.indexOf("3. List of Schemes With 1 to 3 days of supply"));
        }

        @Test
        void usesTheOneDaySupplyWording() throws Exception {
            String text = render(SO, kpis(142, 140), List.of(), List.of(), List.of(), List.of());

            assertThat(text).contains("for minimum 1 day in the week");
        }

        @Test
        void namesTheReportingWeek() throws Exception {
            String text = render(SO, kpis(142, 140), List.of(), List.of(), List.of(), List.of());

            assertThat(text).contains("Monday 13-Jul-2026 to Sunday 19-Jul-2026");
        }

        @Test
        void doesNotCarryTheSdoOnlyColumns() throws Exception {
            // An SO already knows whose schemes these are, so the officer and village columns would be
            // noise on their report — and would squeeze the columns that matter.
            String text = render(SO, kpis(142, 140), schemeRows("Rampur"), List.of(), List.of(), List.of());

            assertThat(text).doesNotContain("SO Phone Number");
            assertThat(text).doesNotContain("Village Name");
        }
    }

    @Nested
    @DisplayName("SDO layout")
    class SdoLayout {

        private List<WeeklyReportOfficerRow> officerRows() {
            return List.of(
                    WeeklyReportOfficerRow.builder().officerUserId(601L).name("Alice").mobile("919868595001")
                            .totalSchemes(154).schemesSupplying(148).schemesNotSupplying(6)
                            .schemesLowLpcd(3).avgLpcd(67).build(),
                    WeeklyReportOfficerRow.builder().officerUserId(602L).name("Bob").mobile("919868595002")
                            .totalSchemes(90).schemesSupplying(80).schemesNotSupplying(10)
                            .schemesLowLpcd(5).avgLpcd(55).build());
        }

        @Test
        void rendersThePerOfficerPerformanceTable() throws Exception {
            String text = render(SDO, kpis(142, 140), schemeRows("Rampur"), List.of(),
                    schemeRows("Kishanganj"), officerRows());

            assertThat(text).contains("2. Section Officers Performance");
            assertThat(text).contains("Alice");
            assertThat(text).contains("Bob");
            assertThat(text).contains("919868595001");
            assertThat(text).contains("154");
        }

        @Test
        void saysEachOfficerRowCoversOnlySharedSchemes() throws Exception {
            // Without this note the column not summing to the SDO's own total reads as an error.
            String text = render(SDO, kpis(142, 140), List.of(), List.of(), List.of(), officerRows());

            assertThat(text).contains("cover only the schemes they share with you");
        }

        @Test
        void usesTheFourDaySupplyWordingAndItsOwnKpiSet() throws Exception {
            String text = render(SDO, kpis(142, 140), List.of(), List.of(), List.of(), officerRows());

            assertThat(text).contains(">=4 days in the week");
            assertThat(text).contains("Total Handed-Over Schemes");
            assertThat(text).contains("Schemes With Supply <=15 LPCD (average)");
        }

        @Test
        void carriesTheOfficerAndVillageOnEverySchemeRow() throws Exception {
            // An SDO acts through their officers, so a scheme row has to say whose scheme it is.
            String text = render(SDO, kpis(142, 140), schemeRows("Rampur"), List.of(),
                    schemeRows("Kishanganj"), officerRows());

            assertThat(text).contains("SO Phone Number");
            assertThat(text).contains("Village Name");
            assertThat(text).contains("Rampur, Kishanganj");
        }

        @Test
        void hasNoOneToThreeDaySection() throws Exception {
            String text = render(SDO, kpis(142, 140), List.of(), List.of(), List.of(), officerRows());

            assertThat(text).doesNotContain("1 to 3 days of supply");
            assertThat(text).contains("3. List of Schemes with No Supply (zero days)");
        }
    }

    @Nested
    @DisplayName("trend column")
    class Trend {

        @Test
        void showsAnIncreaseWithAnUpArrowAndTheDelta() throws Exception {
            String text = render(SO, kpis(142, 140), List.of(), List.of(), List.of(), List.of());

            assertThat(text).contains("▲ +2");
        }

        @Test
        void showsADecreaseWithADownArrow() throws Exception {
            String text = render(SO, kpis(138, 140), List.of(), List.of(), List.of(), List.of());

            assertThat(text).contains("▼ -2");
        }

        @Test
        void showsADashWhenNothingChanged() throws Exception {
            // Total Schemes is 148 in both weeks in the fixture.
            String text = render(SO, kpis(142, 140), List.of(), List.of(), List.of(), List.of());

            // Asserted against the whole row, not the dash alone: the header line
            // "(Section Officer) — Auto generated by JalSoochak" carries an em dash on every report,
            // so a bare contains("—") passed however the trend cell was rendered.
            assertThat(text).contains("Total Schemes 148 148 —");
        }
    }

    @Test
    void rendersAnEmptyReportWithoutFailing() throws Exception {
        WeeklyReportKpis sparse = WeeklyReportKpis.builder()
                .weekStart("2026-07-13").weekEnd("2026-07-19")
                .previousWeekStart("2026-07-06").previousWeekEnd("2026-07-12")
                .build();

        String text = render(SO, sparse, List.of(), List.of(), List.of(), List.of());

        assertThat(text).contains("1. Summary");
        assertThat(text).contains("None");
    }
}
