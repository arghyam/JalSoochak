package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * LOCATION-AFFINITY: true when the submitted location is farther from the scheme than the
     * configured {@code LOCATION_AFFINITY_THRESHOLD} metres. The chatbot flow branches on this to
     * show the operator a proceed/cancel prompt.
     *
     * <p>It is <strong>not</strong> a failure: on {@code /location} the coordinates were saved, and
     * on the reading API the reading was captured. {@code success} stays true and {@code errorCode}
     * stays null, which is why this is a separate field rather than another
     * {@link TelemetryErrorCode}.
     *
     * <p><strong>Primitive on purpose.</strong> This class carries no
     * {@code @JsonInclude(NON_NULL)} and there is no global inclusion setting, so it already
     * serializes nulls — {@code POST /api/v1/telemetry/readings} sends {@code "errorCode": null} to
     * the state IT system on every success. A boxed {@code Boolean} would add
     * {@code "locationMismatch": null} to every one of those responses, and annotating the class to
     * avoid that would silently delete keys from a live integration's payload. A primitive is
     * always present and always false unless the check actually fired.
     */
    private boolean locationMismatch;

    private TelemetryErrorCode errorCode;
    private String message;

    /**
     * Chatbot only: the text of the screen that follows a successful answer — the image prompt after
     * {@code /location}, the closing line after a manual or corrected reading and after an image
     * result — so the flow sends one message instead of calling {@code /intro} or {@code /closing}.
     *
     * <p>Unlike the fields above these are annotated {@code NON_NULL} at field level: they are never
     * set on the reading API, so its payload keeps exactly the keys it always had.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String introMessage;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String closingMessage;
}
