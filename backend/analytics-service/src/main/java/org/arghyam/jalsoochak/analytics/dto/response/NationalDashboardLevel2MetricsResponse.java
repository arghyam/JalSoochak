package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NationalDashboardLevel2MetricsResponse {

    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysInRange;
    // The window the regularity KPI (each district's totalSupplyDays / regularSchemeCount / averageRegularity)
    // was computed over. Equals [startDate, endDate] for multi-day requests; for a single-day request it
    // widens to a trailing lookback so a share-of-days KPI isn't judged on one day. endDate is shared.
    private LocalDate regularityStartDate;
    private Integer regularityDaysInRange;
    private Map<String, Integer> overallOutageReasonDistribution;
    private List<LgdLevel2MetricsRow> districts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LgdLevel2MetricsRow {
        private Integer tenantId;
        private Integer lgdId;
        private Integer tenantStatus;
        private String stateCode;
        private String stateTitle;
        private String districtTitle;

        private Integer schemeCount;

        // Quantity performance (same fields as NationalDashboardResponse.StateQuantityPerformance)
        private Long totalHouseholdCount;
        private Long totalAchievedFhtcCount;
        private Long totalPlannedFhtcCount;
        private Long totalWaterSuppliedLiters;
        private BigDecimal avgWaterSupplyPerScheme;
        private Long supplyDaysInEfficientRange;

        // Regularity (same fields as NationalDashboardResponse.StateRegularity)
        private Integer totalSupplyDays;
        private Integer regularSchemeCount;
        private BigDecimal averageRegularity;

        // Reading submission (same fields as NationalDashboardResponse.StateReadingSubmissionRate)
        private Integer totalSubmissionDays;
        private BigDecimal readingSubmissionRate;
    }
}
