package org.arghyam.jalsoochak.tenant.enums.reportlayout;

import java.util.EnumSet;
import java.util.Set;

/**
 * The columns a summary table can have. The daily summary is one value per KPI; the weekly one
 * compares the reported week with the one before.
 */
public enum SummaryColumn {
    /** The row label. Every summary starts with it. */
    LABEL(ReportKind.DAILY, ReportKind.WEEKLY),
    VALUE(ReportKind.DAILY),
    CURRENT(ReportKind.WEEKLY),
    PREVIOUS(ReportKind.WEEKLY),
    TREND(ReportKind.WEEKLY);

    private final Set<ReportKind> kinds;

    SummaryColumn(ReportKind first, ReportKind... rest) {
        this.kinds = EnumSet.of(first, rest);
    }

    public boolean availableIn(ReportKind kind) {
        return kinds.contains(kind);
    }
}
