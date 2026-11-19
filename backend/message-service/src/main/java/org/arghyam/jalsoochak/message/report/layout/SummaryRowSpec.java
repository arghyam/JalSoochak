package org.arghyam.jalsoochak.message.report.layout;

import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;

/**
 * One summary row.
 *
 * @param label   unique within the summary: the trend colouring finds negative KPIs by label
 * @param display set only on a KPI with a percentage; {@code null} means
 *                {@link KpiDisplay#COUNT_WITH_PERCENT} there
 */
public record SummaryRowSpec(SummaryKpi kpi, String label, KpiDisplay display) {
}
