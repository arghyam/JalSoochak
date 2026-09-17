package org.arghyam.jalsoochak.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KPI payload for the Weekly Water Service Situation Report, computed for a single officer and
 * carried unchanged to message-service for rendering. Shared by the Section Officer and
 * Sub-Divisional Officer variants — the role decides which sections are drawn, not which are
 * computed, except for {@link #sectionOfficerSummaries} which only an SDO request populates.
 *
 * <p>{@code analytics_schema} only: no PII. Scheme names, village names, and officer and Jal Mitra
 * contacts are resolved downstream from the operational schema; this payload carries ids.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyReportKpiDTO {

    /** Reported week (seven days, IST) and the comparison week before it. ISO-8601 strings. */
    private String weekStart;
    private String weekEnd;
    private String previousWeekStart;
    private String previousWeekEnd;

    /** Summary metrics for the reported week and for the comparison week; the PDF renders the delta. */
    private WeekKpis week;
    private WeekKpis previousWeek;

    /** Schemes that supplied on no day of the reported week. Section 2 (SO) / Section 3 (SDO). */
    private List<Integer> noSupplySchemeIds;

    /**
     * Schemes that supplied on 1–3 days of the reported week. Section 3 of the SO report only; the
     * SDO report has no equivalent, its attention band being the ≤15 LPCD list instead.
     */
    private List<Integer> lowSupplyDaysSchemeIds;

    /**
     * Schemes that supplied at least one day but averaged at or below the low-LPCD threshold across
     * the week. Section 4 of both reports. Schemes with no connections are excluded — their LPCD is
     * undefined rather than zero — as are schemes that supplied on no day, which the no-supply list
     * already covers in full.
     */
    private List<Integer> lowLpcdSchemeIds;

    /**
     * SDO only — one row per Section Officer under the SDO, each computed over the schemes that
     * officer shares with the SDO. Null/empty for a Section Officer report.
     */
    private List<SectionOfficerWeekSummary> sectionOfficerSummaries;

    /** The five summary metrics for one week. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeekKpis {
        /** Handed-over schemes mapped to the officer. Reflects current mappings, not historical ones. */
        private int totalSchemes;

        /**
         * Schemes that supplied on at least the role's minimum number of days: 1 for a Section
         * Officer ("did it run at all?"), 4 for an SDO ("did it run often enough?"). The asymmetry is
         * intended, and means an SO's own figure is normally higher than their row in an SDO's table.
         */
        private int schemesSupplying;

        /** Schemes that supplied on no day of the week. Same definition in both variants. */
        private int schemesNotSupplying;

        /** Schemes at or below the low-LPCD threshold across the week. */
        private int schemesLowLpcd;

        /** Litres over the week ÷ (population × 7), across all the officer's handed-over schemes. */
        private double avgLpcd;
    }

    /**
     * One Section Officer's week, for the SDO report's performance table. {@code officerUserId} lets
     * message-service resolve the name and mobile from the operational schema at render time.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectionOfficerWeekSummary {
        private long officerUserId;
        private int totalSchemes;
        private int schemesSupplying;
        private int schemesNotSupplying;
        private int schemesLowLpcd;
        private double avgLpcd;
    }
}
