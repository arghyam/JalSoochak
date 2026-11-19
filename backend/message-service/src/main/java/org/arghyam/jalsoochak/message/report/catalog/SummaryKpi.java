package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.report.ReportKind;

import java.util.function.ToDoubleFunction;

/**
 * The KPIs a summary table can show, and where each is read from in the KPI payload. A KPI is
 * available in a report exactly when it has a reader for that report's payload.
 */
public enum SummaryKpi {
    TOTAL_SCHEMES(ValueType.COUNT, false,
            DailyReportKpis::getTotalSchemes, null, WeeklyReportKpis.WeekKpis::getTotalSchemes),
    SCHEMES_SUPPLYING(ValueType.COUNT, false,
            DailyReportKpis::getSchemesSupplying, null, WeeklyReportKpis.WeekKpis::getSchemesSupplying),
    SCHEMES_NOT_SUPPLYING(ValueType.COUNT, true,
            DailyReportKpis::getSchemesNotSupplying, null, WeeklyReportKpis.WeekKpis::getSchemesNotSupplying),
    SCHEMES_LOW_LPCD(ValueType.COUNT, true,
            null, null, WeeklyReportKpis.WeekKpis::getSchemesLowLpcd),
    HOUSEHOLDS_WITH_SUPPLY(ValueType.COUNT, false,
            DailyReportKpis::getHouseholdsWithSupply, DailyReportKpis::getHouseholdsWithSupplyPct, null),
    HOUSEHOLDS_WITHOUT_SUPPLY(ValueType.COUNT, true,
            DailyReportKpis::getHouseholdsWithoutSupply, DailyReportKpis::getHouseholdsWithoutSupplyPct, null),
    TOTAL_HOUSEHOLDS(ValueType.COUNT, false,
            DailyReportKpis::getTotalHouseholds, null, null),
    /** Litres per capita per day, of only the schemes that supplied. */
    AVG_LPCD(ValueType.DECIMAL, false,
            DailyReportKpis::getAvgLpcd, null, WeeklyReportKpis.WeekKpis::getAvgLpcd),
    ANOMALOUS_SUBMISSIONS(ValueType.COUNT, true,
            DailyReportKpis::getAnomalousCount, null, null);

    /** Whether a value is a whole count or a decimal figure, which is printed per the number format. */
    public enum ValueType {
        COUNT,
        DECIMAL
    }

    private final ValueType valueType;
    private final boolean negative;
    private final ToDoubleFunction<DailyReportKpis> daily;
    private final ToDoubleFunction<DailyReportKpis> dailyPercent;
    private final ToDoubleFunction<WeeklyReportKpis.WeekKpis> weekly;

    SummaryKpi(ValueType valueType, boolean negative,
               ToDoubleFunction<DailyReportKpis> daily,
               ToDoubleFunction<DailyReportKpis> dailyPercent,
               ToDoubleFunction<WeeklyReportKpis.WeekKpis> weekly) {
        this.valueType = valueType;
        this.negative = negative;
        this.daily = daily;
        this.dailyPercent = dailyPercent;
        this.weekly = weekly;
    }

    public ValueType valueType() {
        return valueType;
    }

    /** A rise is a deterioration, so the trend colouring inverts: an up arrow is drawn red. */
    public boolean negative() {
        return negative;
    }

    /** Whether the KPI carries a share of the officer's households, so a {@link KpiDisplay} applies. */
    public boolean hasPercent() {
        return dailyPercent != null;
    }

    public boolean availableIn(ReportKind kind) {
        return switch (kind) {
            case DAILY -> daily != null;
            case WEEKLY -> weekly != null;
        };
    }

    /** @throws IllegalStateException if the KPI is not available in daily reports */
    public double dailyValue(DailyReportKpis kpis) {
        return require(daily, ReportKind.DAILY).applyAsDouble(kpis);
    }

    /** @throws IllegalStateException if the KPI has no percentage */
    public double dailyPercent(DailyReportKpis kpis) {
        if (dailyPercent == null) {
            throw new IllegalStateException(name() + " has no percentage");
        }
        return dailyPercent.applyAsDouble(kpis);
    }

    /** @throws IllegalStateException if the KPI is not available in weekly reports */
    public double weeklyValue(WeeklyReportKpis.WeekKpis week) {
        return require(weekly, ReportKind.WEEKLY).applyAsDouble(week);
    }

    private <T> ToDoubleFunction<T> require(ToDoubleFunction<T> reader, ReportKind kind) {
        if (reader == null) {
            throw new IllegalStateException(name() + " is not available in " + kind + " reports");
        }
        return reader;
    }
}
