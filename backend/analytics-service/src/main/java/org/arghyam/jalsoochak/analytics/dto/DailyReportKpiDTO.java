package org.arghyam.jalsoochak.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KPI payload for the Daily Water Service Situation Report, computed by analytics-service
 * for a single officer (scoped to the schemes mapped to that officer) and carried unchanged
 * to message-service for rendering.
 *
 * <p>All values are computed from {@code analytics_schema} only; no PII and no
 * {@code common_schema} access is involved.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportKpiDTO {

    /** The day the report covers (D-1, "Yesterday" in the sample). ISO-8601 string. */
    private String reportDate;

    /** The comparison day (D-2, "Previous Day" in the sample). ISO-8601 string. */
    private String previousDate;

    /** Total schemes mapped to the officer (denominator for the percentage KPIs). */
    private int totalSchemes;

    /** Summary metrics for {@link #reportDate}. */
    private DayKpis yesterday;

    /** Summary metrics for {@link #previousDate}. */
    private DayKpis previousDay;

    /** Section 3 — outage-reason → scheme-count for {@link #reportDate} (raw reason keys). */
    private List<ReasonCount> reasonsForNoSupply;

    /** Section 4 — anomaly-type → count for {@link #reportDate}. Values are the anomaly-type
     *  NAME as stored in {@code anomaly_table.type} (e.g. {@code NO_SUBMISSION}); legacy rows may
     *  hold the numeric code string. message-service maps either form to a human label. */
    private List<TypeCount> anomaliesByType;

    /** Section 2 — Priority Actions: one entry per officer scheme that had an outage reason on
     *  {@link #reportDate}. Scheme name / IMIS id / pump operators are resolved downstream in
     *  message-service (which has the operational schema + PII); analytics carries only the ids. */
    private List<PriorityAction> priorityActions;

    /** SDO-only Summary breakdown — one row per Section Officer under the SDO (for {@link #reportDate}
     *  only). Populated only when the request carried a subordinate-officer list (i.e. the report is
     *  for a SUB_DIVISIONAL_OFFICER); {@code null}/empty for a SECTION_OFFICER report. Officer name and
     *  mobile are resolved downstream in message-service; analytics carries only the user id + KPIs. */
    private List<SectionOfficerSummary> sectionOfficerSummaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayKpis {
        private int schemesSupplying;
        private int schemesNotSupplying;
        /** Average Litres Per Capita per Day across the officer's schemes for the day. */
        private double avgLpcd;
        /** Total Kilo Litres per Day supplied across the officer's schemes for the day. */
        private double avgKld;
        /** Regular-supply percentage over the 7-day window ending on the day (0-100). */
        private double regularSupplyPctWeek;
        /** Reading-submission percentage for the single day (0-100). */
        private double readingSubmissionPct;
        /** Number of anomalies raised for the officer's schemes on the day. */
        private int anomalousCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasonCount {
        private String reason;
        private int count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeCount {
        /** Anomaly type as stored in {@code anomaly_table.type} — the enum NAME
         *  (e.g. {@code NO_SUBMISSION}); legacy rows may hold the numeric code string. */
        private String type;
        private int count;
    }

    /**
     * One Section Officer's single-day summary KPIs, for the SDO report's per-officer breakdown table.
     * Mirrors the Section 1 Summary columns (no trend). {@code officerUserId} lets message-service
     * resolve the officer's name + mobile from the operational schema at render time.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectionOfficerSummary {
        /** Section Officer user id (operational {@code user_table.id}); PII resolved downstream. */
        private long officerUserId;
        private int totalSchemes;
        private int schemesSupplying;
        private int schemesNotSupplying;
        private double avgLpcd;
        private double avgKld;
        private double regularSupplyPctWeek;
        private double readingSubmissionPct;
        private int anomalousCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorityAction {
        /** Scheme surrogate id — same value in analytics {@code dim_scheme_table.scheme_id}
         *  and operational {@code scheme_master_table.id}. */
        private int schemeId;
        /** Outage reason (human name from {@code fact_water_quantity_table.outage_reason}). */
        private String issue;
        /** Consecutive days with no water supply up to the report day; null if never supplied. */
        private Integer daysNoSupply;
    }
}
