package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.report.ReportKind;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * The columns a summary table can have. The daily summary is one value per KPI; the weekly one
 * compares the reported week with the one before.
 */
public enum SummaryColumn {
    /** The row label. Every summary starts with it. */
    LABEL(40f, ReportKind.DAILY, ReportKind.WEEKLY),
    VALUE(25f, ReportKind.DAILY),
    CURRENT(22f, ReportKind.WEEKLY),
    PREVIOUS(22f, ReportKind.WEEKLY),
    TREND(16f, ReportKind.WEEKLY);

    private final float defaultWeight;
    private final Set<ReportKind> kinds;

    SummaryColumn(float defaultWeight, ReportKind... kinds) {
        this.defaultWeight = defaultWeight;
        this.kinds = EnumSet.copyOf(Arrays.asList(kinds));
    }

    /** The relative width used when a table's columns set none. */
    public float defaultWeight() {
        return defaultWeight;
    }

    public boolean availableIn(ReportKind kind) {
        return kinds.contains(kind);
    }
}
