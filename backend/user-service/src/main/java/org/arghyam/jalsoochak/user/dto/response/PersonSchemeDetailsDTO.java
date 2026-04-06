package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record PersonSchemeDetailsDTO(
        Long schemeId,
        String stateSchemeId,
        String schemeName,
        List<String> pumpOperatorNames,
        BigDecimal lastReading,
        LocalDateTime lastReadingAt,
        BigDecimal yesterdayReading,
        BigDecimal lastWaterSupplied
) {
}
