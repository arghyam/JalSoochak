package org.arghyam.jalsoochak.tenant.enums.reportlayout;

import java.util.EnumSet;
import java.util.Set;

/** The KPIs a summary table can show, and the reports whose KPI payload carries each. */
public enum SummaryKpi {
    TOTAL_SCHEMES(false, ReportKind.DAILY, ReportKind.WEEKLY),
    SCHEMES_SUPPLYING(false, ReportKind.DAILY, ReportKind.WEEKLY),
    SCHEMES_NOT_SUPPLYING(false, ReportKind.DAILY, ReportKind.WEEKLY),
    SCHEMES_LOW_LPCD(false, ReportKind.WEEKLY),
    HOUSEHOLDS_WITH_SUPPLY(true, ReportKind.DAILY),
    HOUSEHOLDS_WITHOUT_SUPPLY(true, ReportKind.DAILY),
    TOTAL_HOUSEHOLDS(false, ReportKind.DAILY),
    /** Litres per capita per day, of only the schemes that supplied. */
    AVG_LPCD(false, ReportKind.DAILY, ReportKind.WEEKLY),
    ANOMALOUS_SUBMISSIONS(false, ReportKind.DAILY);

    private final boolean hasPercent;
    private final Set<ReportKind> kinds;

    SummaryKpi(boolean hasPercent, ReportKind first, ReportKind... rest) {
        this.hasPercent = hasPercent;
        this.kinds = EnumSet.of(first, rest);
    }

    /** Whether the KPI carries a share of the officer's households, so a {@link KpiDisplay} applies. */
    public boolean hasPercent() {
        return hasPercent;
    }

    public boolean availableIn(ReportKind kind) {
        return kinds.contains(kind);
    }
}
