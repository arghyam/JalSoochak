package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record PumpOperatorReadingDetailDTO(
        Long schemeId,
        String schemeName,
        String stateSchemeId,
        LocalDateTime readingAt,
        BigDecimal readingValue,
        BigDecimal waterSupplied
) {
}
