package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record PumpOperatorSummaryWithMetricsDTO(
        Long id,
        String uuid,
        String name,
        Integer status,
        List<PumpOperatorSchemeSummaryDTO> schemes,
        BigDecimal reportingRatePercent,
        LocalDateTime lastSubmissionAt,
        BigDecimal lastWaterSupplied
) {
}
