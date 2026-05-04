package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ReadingsDataResponse {
    private String correlationId;
    private BigDecimal meterReading;
    private String qualityStatus;
    private BigDecimal qualityConfidence;
    private BigDecimal lastConfirmedReading;
    private String message;
}
