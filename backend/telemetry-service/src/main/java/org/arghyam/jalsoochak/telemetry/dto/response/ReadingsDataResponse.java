package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadingsDataResponse {
    private String correlationId;
    private BigDecimal meterReading;
    private String qualityStatus;
    private BigDecimal qualityConfidence;
    private BigDecimal lastConfirmedReading;
    @JsonProperty("error_code")
    private TelemetryErrorCode errorCode;
    private String message;
}
