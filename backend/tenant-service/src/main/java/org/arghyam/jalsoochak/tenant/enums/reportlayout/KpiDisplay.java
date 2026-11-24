package org.arghyam.jalsoochak.tenant.enums.reportlayout;

/** How a summary row of a KPI with a percentage (see {@link SummaryKpi#hasPercent()}) is printed. */
public enum KpiDisplay {
    /** {@code 14200 (95.9%)}: the default. */
    COUNT_WITH_PERCENT,
    /** {@code 14200} */
    COUNT,
    /** {@code 95.9%} */
    PERCENT
}
