package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import org.arghyam.jalsoochak.tenant.enums.reportlayout.KpiDisplay;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SummaryKpi;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One summary row.
 *
 * @param label   unique within the summary: the trend colouring finds negative KPIs by label
 * @param display only on a KPI with a percentage; absent means {@link KpiDisplay#COUNT_WITH_PERCENT}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummaryRowDTO(SummaryKpi kpi, String label, KpiDisplay display) {
}
