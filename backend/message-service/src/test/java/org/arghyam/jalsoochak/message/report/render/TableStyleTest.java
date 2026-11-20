package org.arghyam.jalsoochak.message.report.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.report.DefaultReportLayouts;
import org.arghyam.jalsoochak.message.report.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.CELL_PAD;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.CONTENT_WIDTH;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.DENSE_CELL_PAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TableStyleTest {

    private static final DefaultReportLayouts LAYOUTS = new DefaultReportLayouts(new ObjectMapper());
    private static final ReportLayout DAILY_SO = LAYOUTS.builtIn(ReportKind.DAILY, "SECTION_OFFICER").orElseThrow();
    private static final ReportLayout WEEKLY_SO = LAYOUTS.builtIn(ReportKind.WEEKLY, "SECTION_OFFICER").orElseThrow();
    private static final ReportLayout WEEKLY_SDO =
            LAYOUTS.builtIn(ReportKind.WEEKLY, "SUB_DIVISIONAL_OFFICER").orElseThrow();

    @Nested
    class Density {

        @Test
        void sizesTheSummaryByReport() {
            assertThat(TableStyle.summary(ReportKind.DAILY)).isEqualTo(new TableStyle(10f, 10f, CELL_PAD));
            assertThat(TableStyle.summary(ReportKind.WEEKLY)).isEqualTo(new TableStyle(9.5f, 9.5f, CELL_PAD));
        }

        @ParameterizedTest
        @CsvSource({"1, 9, 9.5, 6", "5, 9, 9.5, 6", "6, 8.5, 8.5, 3", "7, 8.5, 8.5, 3", "8, 8, 8, 3", "10, 8, 8, 3"})
        void shrinksTheTextAndPaddingAsColumnsAreAdded(int columns, float body, float header, float pad) {
            assertThat(TableStyle.list(columns)).isEqualTo(new TableStyle(body, header, pad));
        }

        @Test
        void usesTheCanvasPaddings() {
            assertThat(TableStyle.LIST.cellPad()).isEqualTo(CELL_PAD);
            assertThat(TableStyle.DENSE_LIST.cellPad()).isEqualTo(DENSE_CELL_PAD);
        }
    }

    @Nested
    class Alignment {

        @Test
        void centresEverySummaryColumnButTheLabel() {
            assertThat(TableStyle.summaryCentring(summaryColumns(DAILY_SO))).containsExactly(false, true);
            assertThat(TableStyle.summaryCentring(summaryColumns(WEEKLY_SO))).containsExactly(false, true, true, true);
        }

        @Test
        void centresTheOfficerTablesSerialNumberAndFigures() {
            assertThat(TableStyle.officerCentring(officerColumns(WEEKLY_SDO)))
                    .containsExactly(true, false, false, true, true, true, true, true);
        }
    }

    @Nested
    class Widths {

        @Test
        void giveTheBuiltInTablesExactlyTheWidthsTheyWereDrawnWithBefore() {
            // The literal ratios the hand-drawn reports used. Equality is exact, not within a tolerance:
            // any difference would move every line of the table in the PDF.
            assertThat(TableStyle.widths(summaryColumns(DAILY_SO), SummaryColumn::defaultWeight))
                    .containsExactly(ratios(0.62f, 0.38f));
            assertThat(TableStyle.widths(schemeColumns(DAILY_SO, 1), SchemeColumn::defaultWeight))
                    .containsExactly(ratios(0.21f, 0.33f, 0.26f, 0.20f));
            assertThat(TableStyle.widths(schemeColumns(DAILY_SO, 2), SchemeColumn::defaultWeight))
                    .containsExactly(ratios(0.20f, 0.20f, 0.23f, 0.20f, 0.17f));
            assertThat(TableStyle.widths(summaryColumns(WEEKLY_SO), SummaryColumn::defaultWeight))
                    .containsExactly(ratios(0.40f, 0.22f, 0.22f, 0.16f));
            for (int section = 1; section <= 3; section++) {
                assertThat(TableStyle.widths(schemeColumns(WEEKLY_SO, section), SchemeColumn::defaultWeight))
                        .containsExactly(ratios(0.08f, 0.40f, 0.28f, 0.24f));
            }
            assertThat(TableStyle.widths(officerColumns(WEEKLY_SDO), OfficerColumn::defaultWeight))
                    .containsExactly(ratios(0.05f, 0.19f, 0.15f, 0.10f, 0.15f, 0.13f, 0.12f, 0.11f));
            for (int section = 2; section <= 3; section++) {
                assertThat(TableStyle.widths(schemeColumns(WEEKLY_SDO, section), SchemeColumn::defaultWeight))
                        .containsExactly(ratios(0.05f, 0.15f, 0.14f, 0.18f, 0.14f, 0.16f, 0.18f));
            }
        }

        @Test
        void useTheCatalogueDefaultsWhenNoColumnSetsAWidth() {
            List<ColumnSpec<SchemeColumn>> columns = List.of(
                    new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "Scheme", null),
                    new ColumnSpec<>(SchemeColumn.PUMP_OPERATOR_NAMES, "Operator", null));

            assertThat(TableStyle.widths(columns, SchemeColumn::defaultWeight))
                    .containsExactly(TableStyle.normalise(new float[]{
                            SchemeColumn.SCHEME_NAME.defaultWeight(), SchemeColumn.PUMP_OPERATOR_NAMES.defaultWeight()}));
        }

        @Test
        void useTheCatalogueDefaultsWhenAnyColumnLacksAUsableWidth() {
            float[] defaults = TableStyle.normalise(new float[]{
                    SchemeColumn.SCHEME_NAME.defaultWeight(), SchemeColumn.VILLAGES.defaultWeight()});

            assertThat(TableStyle.widths(List.of(
                    new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "Scheme", 70f),
                    new ColumnSpec<>(SchemeColumn.VILLAGES, "Villages", null)), SchemeColumn::defaultWeight))
                    .containsExactly(defaults);
            assertThat(TableStyle.widths(List.of(
                    new ColumnSpec<>(SchemeColumn.SCHEME_NAME, "Scheme", 70f),
                    new ColumnSpec<>(SchemeColumn.VILLAGES, "Villages", 0f)), SchemeColumn::defaultWeight))
                    .containsExactly(defaults);
        }

        @Test
        void shareTheFullContentWidthWhateverTheWeightsSumTo() {
            float[] widths = TableStyle.normalise(new float[]{3f, 1f});

            assertThat(widths[0] + widths[1]).isCloseTo(CONTENT_WIDTH, within(0.01f));
            assertThat(widths[0]).isCloseTo(CONTENT_WIDTH * 0.75f, within(0.01f));
        }
    }

    private static float[] ratios(float... ratios) {
        float[] widths = new float[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            widths[i] = CONTENT_WIDTH * ratios[i];
        }
        return widths;
    }

    private static List<ColumnSpec<SummaryColumn>> summaryColumns(ReportLayout layout) {
        return ((ReportSection.SummarySection) layout.sections().get(0)).columns();
    }

    private static List<ColumnSpec<SchemeColumn>> schemeColumns(ReportLayout layout, int section) {
        return ((ReportSection.SchemeListSection) layout.sections().get(section)).columns();
    }

    private static List<ColumnSpec<OfficerColumn>> officerColumns(ReportLayout layout) {
        return ((ReportSection.OfficerPerformanceSection) layout.sections().get(1)).columns();
    }
}
