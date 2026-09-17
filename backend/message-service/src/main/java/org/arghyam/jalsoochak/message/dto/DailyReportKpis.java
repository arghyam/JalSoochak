package org.arghyam.jalsoochak.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Daily Water Service Situation Report KPI payload, as received from analytics-service inside the
 * {@code DAILY_REPORT_KPIS} event. Mirrors analytics-service's {@code DailyReportKpiDTO}.
 *
 * <p>Carries no PII: the scheme ids in {@link #noSupplySchemeIds} and {@link #schemeAnomalies} are
 * resolved to names, IMIS ids and Jal Mitra contacts here, from the operational schema, at render
 * time.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyReportKpis {

    /** The day the report covers (today, IST). */
    private String reportDate;

    /** ISO-8601 local date-time (IST) the window closed at; rendered into the Reporting Period line. */
    private String cutoffIst;

    private int totalSchemes;
    private int schemesSupplying;
    private int schemesNotSupplying;

    private long householdsWithSupply;
    private double householdsWithSupplyPct;
    private long householdsWithoutSupply;
    private double householdsWithoutSupplyPct;
    private long totalHouseholds;

    /** Litres per capita per day across only the schemes that supplied water. */
    private double avgLpcd;

    private int anomalousCount;

    /** Section 2 — schemes that had not supplied water by the cut-off. */
    private List<Integer> noSupplySchemeIds;

    /** Section 3 — one entry per (scheme, anomaly type) raised during the window. */
    private List<SchemeAnomaly> schemeAnomalies;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SchemeAnomaly {
        private int schemeId;
        /** Anomaly enum NAME, or the numeric code as a string on pre-migration rows. */
        private String type;
    }
}
