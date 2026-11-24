package org.arghyam.jalsoochak.tenant.enums.reportlayout;

import java.util.EnumSet;
import java.util.Set;

/** The scheme lists analytics-service computes, and the reports that carry each. */
public enum SchemeList {
    /** Daily: not supplied by the cut-off. Weekly: supplied on no day of the week. */
    NO_SUPPLY(ReportKind.DAILY, ReportKind.WEEKLY),
    /** One row per (scheme, anomaly type) raised during the day. */
    ANOMALIES(ReportKind.DAILY),
    /** Supplied on 1 to 3 days of the week. */
    LOW_SUPPLY_DAYS(ReportKind.WEEKLY),
    /** Supplied, but averaged at or below the low-LPCD threshold over the week. */
    LOW_LPCD(ReportKind.WEEKLY);

    private final Set<ReportKind> kinds;

    SchemeList(ReportKind first, ReportKind... rest) {
        this.kinds = EnumSet.of(first, rest);
    }

    public boolean availableIn(ReportKind kind) {
        return kinds.contains(kind);
    }
}
