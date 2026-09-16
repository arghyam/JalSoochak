package org.arghyam.jalsoochak.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KPI payload for the Daily Water Service Situation Report, computed by analytics-service for a
 * single Section Officer (scoped to that officer's handed-over schemes) and carried unchanged to
 * message-service for rendering.
 *
 * <p>All values are computed from {@code analytics_schema} only: no PII and no {@code common_schema}
 * access. Scheme names, IMIS ids and Jal Mitra contacts are resolved downstream from the operational
 * schema — this payload carries scheme <em>ids</em>.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportKpiDTO {

    /** The day the report covers (today, IST). ISO-8601 string. */
    private String reportDate;

    /**
     * ISO-8601 local date-time (IST) the data window closed at — the instant the job ran. Rendered
     * into the PDF's "Reporting Period: 00:00 hrs – HH:MM hrs" line, so the stated window is the one
     * actually applied rather than a hard-coded 16:00.
     */
    private String cutoffIst;

    /** Total handed-over schemes mapped to the officer — the denominator for the HH percentages. */
    private int totalSchemes;

    /** Schemes that supplied water during the window. */
    private int schemesSupplying;

    /** {@code totalSchemes - schemesSupplying}, never negative. */
    private int schemesNotSupplying;

    /** Households (FHTC) on schemes that supplied water, and that as a percentage of {@link #totalHouseholds}. */
    private long householdsWithSupply;
    private double householdsWithSupplyPct;

    /** Households (FHTC) on schemes that did not supply, and the corresponding percentage. */
    private long householdsWithoutSupply;
    private double householdsWithoutSupplyPct;

    /** Total households (FHTC) across all the officer's handed-over schemes. */
    private long totalHouseholds;

    /**
     * Litres per capita per day across <em>only the schemes that supplied water</em>, per the
     * template's footnote. The numerator is the day's litres; the denominator is the population of
     * the supplying subset. This is the one LPCD in the platform not taken over the full scheme set.
     */
    private double avgLpcd;

    /** Anomalies raised against the officer's schemes during the window. */
    private int anomalousCount;

    /** Section 2 — schemes that had not supplied water by the cut-off. Ids only; PII resolved downstream. */
    private List<Integer> noSupplySchemeIds;

    /** Section 3 — one entry per (scheme, anomaly type) raised during the window. */
    private List<SchemeAnomaly> schemeAnomalies;

    /**
     * One anomaly type observed on one scheme. {@code type} is the anomaly enum NAME as stored in
     * {@code anomaly_table.type} (e.g. {@code UNREADABLE_IMAGE}); rows written before analytics
     * migration V29 may hold the numeric code as a string, and message-service maps either form to a
     * human label.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemeAnomaly {
        private int schemeId;
        private String type;
    }

    /** Anomaly type → count, for callers that want the breakdown without the per-scheme detail. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeCount {
        private String type;
        private int count;
    }
}
