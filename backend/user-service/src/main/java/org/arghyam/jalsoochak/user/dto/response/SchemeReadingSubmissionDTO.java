package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record SchemeReadingSubmissionDTO(
        Long pumpOperatorId,
        String pumpOperatorName,
        LocalDateTime submittedAt,
        BigDecimal readingValue,
        BigDecimal waterSupplied
){
}
