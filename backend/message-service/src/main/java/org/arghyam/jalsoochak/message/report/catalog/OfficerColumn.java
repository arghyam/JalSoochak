package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;

import java.util.function.DoubleFunction;

/** The columns of the SDO weekly report's Section Officer performance table. */
public enum OfficerColumn {
    /** The row's 1-based position in the table. */
    SERIAL_NO(5f, true, (row, serialNo, decimal) -> String.valueOf(serialNo)),
    OFFICER_NAME(19f, false, (row, serialNo, decimal) -> row.getName()),
    OFFICER_MOBILE(15f, false, (row, serialNo, decimal) -> row.getMobile()),
    TOTAL_SCHEMES(10f, true, (row, serialNo, decimal) -> String.valueOf(row.getTotalSchemes())),
    SCHEMES_SUPPLYING(15f, true, (row, serialNo, decimal) -> String.valueOf(row.getSchemesSupplying())),
    SCHEMES_NOT_SUPPLYING(13f, true, (row, serialNo, decimal) -> String.valueOf(row.getSchemesNotSupplying())),
    SCHEMES_LOW_LPCD(12f, true, (row, serialNo, decimal) -> String.valueOf(row.getSchemesLowLpcd())),
    AVG_LPCD(11f, true, (row, serialNo, decimal) -> decimal.apply(row.getAvgLpcd()));

    @FunctionalInterface
    private interface Cell {
        String text(WeeklyReportOfficerRow row, int serialNo, DoubleFunction<String> decimal);
    }

    private final float defaultWeight;
    private final boolean centred;
    private final Cell cell;

    OfficerColumn(float defaultWeight, boolean centred, Cell cell) {
        this.defaultWeight = defaultWeight;
        this.centred = centred;
        this.cell = cell;
    }

    /** The relative width used when a table's columns set none. */
    public float defaultWeight() {
        return defaultWeight;
    }

    /** The serial number and the figures are centred; names and mobiles read left-aligned. */
    public boolean centred() {
        return centred;
    }

    /**
     * The cell text for {@code row}, never {@code null}.
     *
     * @param decimal formats a decimal figure (Average LPCD) per the report's number format
     */
    public String cell(WeeklyReportOfficerRow row, int serialNo, DoubleFunction<String> decimal) {
        String text = cell.text(row, serialNo, decimal);
        return text == null ? "" : text;
    }
}
