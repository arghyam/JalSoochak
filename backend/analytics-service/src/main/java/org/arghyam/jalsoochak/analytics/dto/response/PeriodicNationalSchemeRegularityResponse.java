package org.arghyam.jalsoochak.analytics.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeriodicNationalSchemeRegularityResponse {

    private Integer schemeCount;
    private Long totalAchievedFhtcCount;
    private String scale;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer periodCount;
    private List<PeriodicNationalSchemeRegularityPeriodMetric> metrics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "PeriodicNationalSchemeRegularityPeriodMetric")
    public static class PeriodicNationalSchemeRegularityPeriodMetric {
        private LocalDate periodStartDate;
        private LocalDate periodEndDate;
        private Integer schemeCount;
        private Integer totalSupplyDays;
        private Long totalWaterQuantity;
        private BigDecimal averageRegularity;
    }
}

