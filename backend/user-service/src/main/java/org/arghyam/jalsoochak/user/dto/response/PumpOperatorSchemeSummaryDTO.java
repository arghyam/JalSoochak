package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;

@Builder
public record PumpOperatorSchemeSummaryDTO(
        Long schemeId,
        String schemeName,
        String stateSchemeId
) {
}
