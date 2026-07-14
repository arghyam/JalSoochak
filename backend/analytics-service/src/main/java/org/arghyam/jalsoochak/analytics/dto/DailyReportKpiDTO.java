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

    /** Section 4 — anomaly-type-code → count for {@link #reportDate} (codes "1".."9"). */
    private List<TypeCount> anomaliesByType;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayKpis {
        private int schemesSupplying;
        private int schemesNotSupplying;
        /** Average Litres Per Capita per Day across the officer's schemes for the day. */
        private double avgLpcd;
        /** Total Million Litres per Day supplied across the officer's schemes for the day. */
        private double avgMld;
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
        /** Anomaly type code as stored in {@code anomaly_table.type} ("1".."9"). */
        private String type;
        private int count;
    }
}
