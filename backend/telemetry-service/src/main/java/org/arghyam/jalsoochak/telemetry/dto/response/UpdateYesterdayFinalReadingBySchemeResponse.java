package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateYesterdayFinalReadingBySchemeResponse {
    private boolean success;
    private Long schemeId;
    private String readingDate;
    private BigDecimal finalReading;
    private String message;
}

