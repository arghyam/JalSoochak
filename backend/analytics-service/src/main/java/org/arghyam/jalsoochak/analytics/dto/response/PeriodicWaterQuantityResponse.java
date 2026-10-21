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
public class PeriodicWaterQuantityResponse {

    private Integer lgdId;
    private Integer departmentId;
    private String scale;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer periodCount;
    private List<PeriodicWaterQuantityPeriodMetric> metrics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "PeriodicWaterQuantityPeriodMetric")
    public static class PeriodicWaterQuantityPeriodMetric {
        private LocalDate periodStartDate;
        private LocalDate periodEndDate;
        private BigDecimal averageWaterQuantity;
        private Long householdCount;
        private Long achievedFhtcCount;
        private Long plannedFhtcCount;
        // LPCD denominator for the bucket: each scheme's achieved FHTC summed over the days it supplied water.
        private Long totalSuppliedFhtcDays;
    }
}
