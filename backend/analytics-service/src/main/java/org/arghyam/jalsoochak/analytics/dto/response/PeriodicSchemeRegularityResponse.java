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
public class PeriodicSchemeRegularityResponse {

    private Integer lgdId;
    private Integer departmentId;
    private Integer schemeCount;
    private String scale;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer periodCount;
    private List<PeriodicSchemeRegularityPeriodMetric> metrics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "PeriodicSchemeRegularityPeriodMetric")
    public static class PeriodicSchemeRegularityPeriodMetric {
        private LocalDate periodStartDate;
        private LocalDate periodEndDate;
        private Integer totalSupplyDays;
        private Integer regularSchemeCount;
        private Long totalWaterQuantity;
        private BigDecimal averageRegularity;
    }
}

