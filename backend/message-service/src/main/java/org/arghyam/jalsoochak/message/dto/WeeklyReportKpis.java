package org.arghyam.jalsoochak.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Weekly Water Service Situation Report KPI payload, as received from analytics-service inside the
 * {@code WEEKLY_REPORT_KPIS} event. Mirrors analytics-service's {@code WeeklyReportKpiDTO} and serves
 * both the Section Officer and Sub-Divisional Officer layouts.
 *
 * <p>Carries no PII: scheme ids are resolved to names, villages and Jal Mitra contacts here, and
 * {@code officerUserId} to a name and mobile, from the operational schema at render time.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeeklyReportKpis {

    private String weekStart;
    private String weekEnd;
    private String previousWeekStart;
    private String previousWeekEnd;

    private WeekKpis week;
    private WeekKpis previousWeek;

    /** Schemes that supplied on no day of the reported week. */
    private List<Integer> noSupplySchemeIds;

    /** Schemes that supplied on 1-3 days. Section Officer report only. */
    private List<Integer> lowSupplyDaysSchemeIds;

    /** Schemes that ran but averaged at or below the low-LPCD threshold across the week. */
    private List<Integer> lowLpcdSchemeIds;

    /** SDO only — one row per Section Officer under the SDO. Null/empty for a Section Officer report. */
    private List<SectionOfficerWeekSummary> sectionOfficerSummaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeekKpis {
        private int totalSchemes;
        /** Schemes meeting the role's minimum supply-days bar: >= 1 day for an SO, >= 4 for an SDO. */
        private int schemesSupplying;
        private int schemesNotSupplying;
        private int schemesLowLpcd;
        private double avgLpcd;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionOfficerWeekSummary {
        private long officerUserId;
        private int totalSchemes;
        private int schemesSupplying;
        private int schemesNotSupplying;
        private int schemesLowLpcd;
        private double avgLpcd;
    }
}
