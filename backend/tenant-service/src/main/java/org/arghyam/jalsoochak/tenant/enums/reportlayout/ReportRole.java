package org.arghyam.jalsoochak.tenant.enums.reportlayout;

/**
 * The officer roles a situation report can be configured for.
 *
 * <p>Only these two: they are the roles the report schedulers (the subordinate lookup) and
 * analytics-service's KPI queries (the minimum supply-days bar) model.</p>
 */
public enum ReportRole {
    SECTION_OFFICER,
    SUB_DIVISIONAL_OFFICER
}
