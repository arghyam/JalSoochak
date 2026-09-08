package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /**
     * The operator's current confirmed meter value, carried for the callers that need it in-process
     * and deliberately kept off the wire.
     *
     * <p>This object is the response body of three Glific webhook routes — {@code /manual-reading},
     * {@code /location} and {@code /update-previous-reading} — so every field on it is readable by
     * anyone who can reach them. The Glific flows do not use this one: each webhook node consumes
     * {@code @results.<name>.message} and the HTTP status, nothing else. Serialising it therefore
     * bought no behaviour and handed a caller the operator's current reading, which is the other
     * half of the disclosure that {@code GlificMeterWorkflowService} already closed by dropping the
     * "Maximum allowed reading: N" suffix.
     *
     * <p>{@code @JsonIgnore} rather than deleting the field: {@code SingleTenantTelemetryController}
     * and {@code MultiFormatReadingController} copy it by getter into {@link ReadingsDataResponse},
     * which is served only on the {@code X-Api-Key} ingestion routes where the caller holds the
     * tenant's own key and the value is their own operator's reading. That contract is unchanged.
     * The number also still reaches the server-side reading-submission logs.
     */
    @JsonIgnore
    private BigDecimal lastConfirmedReading;

    private boolean success;

    private TelemetryErrorCode errorCode;
    private String message;
}
