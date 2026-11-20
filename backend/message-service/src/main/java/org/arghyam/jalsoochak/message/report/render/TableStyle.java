package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;

import java.util.List;
import java.util.function.Function;

import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.CELL_PAD;
import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.CONTENT_WIDTH;
import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.DENSE_CELL_PAD;

/**
 * The fixed presentation rules for a table: font sizes and padding by table kind and column count,
 * which columns are centred, and column widths.
 *
 * <p>Not configurable. They reproduce every table the reports drew before layouts existed, and let a
 * tenant add or remove columns without tuning fonts: the more columns, the smaller the text and the
 * tighter the padding.</p>
 */
record TableStyle(float bodyFontSize, float headerFontSize, float cellPad) {

    static final TableStyle DAILY_SUMMARY = new TableStyle(10f, 10f, CELL_PAD);
    static final TableStyle WEEKLY_SUMMARY = new TableStyle(9.5f, 9.5f, CELL_PAD);
    /** Up to 5 columns. */
    static final TableStyle LIST = new TableStyle(9f, 9.5f, CELL_PAD);
    /** 6 or 7 columns. */
    static final TableStyle DENSE_LIST = new TableStyle(8.5f, 8.5f, DENSE_CELL_PAD);
    /** 8 columns or more. */
    static final TableStyle DENSEST_LIST = new TableStyle(8f, 8f, DENSE_CELL_PAD);

    static TableStyle summary(ReportKind kind) {
        return kind == ReportKind.DAILY ? DAILY_SUMMARY : WEEKLY_SUMMARY;
    }

    /** A scheme list's or the officer table's style, by how many columns it has. */
    static TableStyle list(int columnCount) {
        if (columnCount <= 5) {
            return LIST;
        }
        return columnCount <= 7 ? DENSE_LIST : DENSEST_LIST;
    }

    /** Every summary column but the label is centred. */
    static boolean[] summaryCentring(List<ColumnSpec<SummaryColumn>> columns) {
        boolean[] centred = new boolean[columns.size()];
        for (int i = 0; i < centred.length; i++) {
            centred[i] = columns.get(i).field() != SummaryColumn.LABEL;
        }
        return centred;
    }

    /** The serial number and the figures are centred; names and mobiles read left-aligned. */
    static boolean[] officerCentring(List<ColumnSpec<OfficerColumn>> columns) {
        boolean[] centred = new boolean[columns.size()];
        for (int i = 0; i < centred.length; i++) {
            centred[i] = columns.get(i).field().centred();
        }
        return centred;
    }

    /**
     * The columns' widths in points across the page: the layout's weights when every column sets a
     * usable one, otherwise each column's catalogue default.
     */
    static <F extends Enum<F>> float[] widths(List<ColumnSpec<F>> columns, Function<F, Float> defaultWeight) {
        boolean ownWeights = columns.stream().allMatch(column -> isUsableWeight(column.width()));
        float[] weights = new float[columns.size()];
        for (int i = 0; i < weights.length; i++) {
            ColumnSpec<F> column = columns.get(i);
            weights[i] = ownWeights ? column.width() : defaultWeight.apply(column.field());
        }
        return normalise(weights);
    }

    /**
     * Shares {@code CONTENT_WIDTH} out by weight. Computed as {@code CONTENT_WIDTH * (w / sum)} in
     * {@code float}, so weights summing to 100 give exactly the widths of the literal ratios
     * ({@code CONTENT_WIDTH * 0.21f}) the reports were drawn with before.
     */
    static float[] normalise(float[] weights) {
        float sum = 0f;
        for (float weight : weights) {
            sum += weight;
        }
        float[] widths = new float[weights.length];
        for (int i = 0; i < weights.length; i++) {
            widths[i] = CONTENT_WIDTH * (weights[i] / sum);
        }
        return widths;
    }

    private static boolean isUsableWeight(Float weight) {
        return weight != null && Float.isFinite(weight) && weight > 0f;
    }
}
