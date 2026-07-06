package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CreateReadingResponse {
    private String correlationId;
    private BigDecimal meterReading;
    private String qualityStatus;
    private BigDecimal qualityConfidence;
    private BigDecimal lastConfirmedReading;

    private boolean success;
    private String errorCode;
    private String message;
}
