package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class FlowVisionResult {

    private BigDecimal adjustedReading;

    private String correlationId;

    private String qualityStatus;

    private BigDecimal qualityConfidence;

    private String lastDigitColor;
}
