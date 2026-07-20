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
public class NationalDashboardResponse {

    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysInRange;
    // The window the regularity KPI (stateWiseRegularity, incl. its totalSupplyDays / regularSchemeCount)
    // was computed over. Equals [startDate, endDate] for multi-day requests; for a single-day request it
    // widens to a trailing lookback so a share-of-days KPI isn't judged on one day. endDate is shared.
    private LocalDate regularityStartDate;
    private Integer regularityDaysInRange;
    private List<StateQuantityPerformance> stateWiseQuantityPerformance;
    private List<StateRegularity> stateWiseRegularity;
    private List<StateReadingSubmissionRate> stateWiseReadingSubmissionRate;
    private Map<String, Integer> overallOutageReasonDistribution;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateQuantityPerformance {
        private Integer tenantId;
        private Integer lgdId;
        private Integer tenantStatus;
        private String stateCode;
        private String stateTitle;
        private Integer schemeCount;
        private Long totalHouseholdCount;
        private Long totalAchievedFhtcCount;
        private Long totalPlannedFhtcCount;
        private Long totalWaterSuppliedLiters;
        private BigDecimal avgWaterSupplyPerScheme;
        private Long supplyDaysInEfficientRange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateRegularity {
        private Integer tenantId;
        private Integer lgdId;
        private Integer tenantStatus;
        private String stateCode;
        private String stateTitle;
        private Integer schemeCount;
        private Integer totalSupplyDays;
        private Integer regularSchemeCount;
        private BigDecimal averageRegularity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateReadingSubmissionRate {
        private Integer tenantId;
        private Integer lgdId;
        private Integer tenantStatus;
        private String stateCode;
        private String stateTitle;
        private Integer schemeCount;
        private Integer totalSubmissionDays;
        private BigDecimal readingSubmissionRate;
    }
}
