package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TelemetryErrorCode {
    FLOW_VISION_FAILED("flowVisionFailed"),
    FLOW_VISION_REJECTED("flowVisionRejected"),
    UNREADABLE_IMAGE("unreadableImage"),
    DUPLICATE_IMAGE("duplicateImage"),
    VALIDATION_FAILED("validationFailed"),
    MALFORMED_REQUEST("malformedRequest"),
    INVALID_API_KEY("invalidApiKey"),
    PROCESSING_FAILED("processingFailed"),
    OPERATOR_NOT_MAPPED_TO_SCHEME("operatorNotMappedToScheme"),
    SCHEME_NOT_FOUND("schemeNotFound"),
    OPERATOR_NOT_FOUND("operatorNotFound"),
    SERVER_ERROR("serverError"),
    BAD_REQUEST("badRequest"),
    REQUEST_FAILED("requestFailed");

    private final String code;

    TelemetryErrorCode(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
