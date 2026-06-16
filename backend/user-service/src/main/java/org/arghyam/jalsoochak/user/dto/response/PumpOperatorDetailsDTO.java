package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.arghyam.jalsoochak.user.enums.TenantUserStatus;

@Builder
public record PumpOperatorDetailsDTO(
        Long id,
        String uuid,
        String name,
        String email,
        String phoneNumber,
        TenantUserStatus status,
        Integer schemeId,
        String stateSchemeId,
        String centerSchemeId,
        String schemeName,
        Double schemeLatitude,
        Double schemeLongitude,
        LocalDateTime lastSubmissionAt,
        LocalDate firstSubmissionDate,
        Integer totalDaysSinceFirstSubmission,
        Integer submittedDays,
        BigDecimal reportingRatePercent,
        List<LocalDate> missedSubmissionDays
) {
}
