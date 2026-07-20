package org.arghyam.jalsoochak.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Daily Water Service Situation Report KPI payload, as received from analytics-service inside the
 * {@code DAILY_REPORT_KPIS} event. Mirrors {@code analytics-service}'s {@code DailyReportKpiDTO}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyReportKpis {

    private String reportDate;
    private String previousDate;
    private int totalSchemes;
    private DayKpis yesterday;
    private DayKpis previousDay;
    private List<ReasonCount> reasonsForNoSupply;
    private List<TypeCount> anomaliesByType;
    private List<PriorityAction> priorityActions;

    /**
     * SDO-only Summary breakdown — one row per Section Officer under the SDO (report day only).
     * Present only for a SUB_DIVISIONAL_OFFICER report; null/empty otherwise. Officer name + mobile are
     * resolved by message-service from the operational {@code user_table} at render time.
     */
    private List<SectionOfficerSummary> sectionOfficerSummaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DayKpis {
        private int schemesSupplying;
        private int schemesNotSupplying;
        private double avgLpcd;
        private double avgMld;
        private double regularSupplyPctWeek;
        private double readingSubmissionPct;
        private int anomalousCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasonCount {
        private String reason;
        private int count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeCount {
        private String type;
        private int count;
    }

    /**
     * Section 2 — one Priority Actions entry as computed by analytics. Scheme name / IMIS id /
     * pump operators are resolved in message-service (operational schema + PII) at render time.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriorityAction {
        private int schemeId;
        private String issue;
        private Integer daysNoSupply;
    }

    /**
     * SDO Summary breakdown row for one Section Officer (report day only). Officer name + mobile are
     * resolved by message-service from the operational {@code user_table} using {@link #officerUserId}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionOfficerSummary {
        private long officerUserId;
        private int totalSchemes;
        private int schemesSupplying;
        private int schemesNotSupplying;
        private double avgLpcd;
        private double avgMld;
        private double regularSupplyPctWeek;
        private double readingSubmissionPct;
        private int anomalousCount;
    }
}
