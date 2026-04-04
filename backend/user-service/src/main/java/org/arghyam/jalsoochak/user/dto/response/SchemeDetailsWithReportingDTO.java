package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record SchemeDetailsWithReportingDTO(
        Long schemeId,
        String stateSchemeId,
        String schemeName,
        LocalDateTime lastSubmissionAt,
        BigDecimal reportingRatePercent
) {
}
