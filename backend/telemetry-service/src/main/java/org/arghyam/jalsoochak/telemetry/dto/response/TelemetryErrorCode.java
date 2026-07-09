package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, machine-readable error codes returned on telemetry reading responses
 * ({@link CreateReadingResponse} and {@link ReadingsDataResponse}).
 *
 * <p>This enum is the single source of truth for the {@code error_code} field of the reading API.
 * Each constant serializes to its lowerCamelCase {@link #code()} value via {@link JsonValue}; the
 * field is omitted from the JSON when {@code null} (the DTOs are annotated
 * {@code @JsonInclude(NON_NULL)}), so a code is present only on failure responses.
 *
 * <p><b>Contract note:</b> the wire values below are part of the public API. Consumers may branch on
 * them, so add new constants rather than repurposing existing ones, and do not rename a
 * {@link #code()} string once released.
 */
public enum TelemetryErrorCode {

    /** The FlowVision OCR call threw / failed while extracting a reading from the image. */
    FLOW_VISION_FAILED("flowVisionFailed"),

    /**
     * Reserved for an explicit FlowVision provider "REJECTED" status. Not currently emitted — the
     * unreadable-image path uses {@link #UNREADABLE_IMAGE}. Kept for forward compatibility.
     */
    FLOW_VISION_REJECTED("flowVisionRejected"),

    /** FlowVision responded but produced no usable meter reading (image could not be read). */
    UNREADABLE_IMAGE("unreadableImage"),

    /** The submitted image's extracted reading duplicates the previous confirmed reading. */
    DUPLICATE_IMAGE("duplicateImage"),

    /** Request bean validation ({@code @Valid}) failed (e.g. a required field was blank). */
    VALIDATION_FAILED("validationFailed"),

    /** The request body could not be parsed (malformed JSON / unreadable message). */
    MALFORMED_REQUEST("malformedRequest"),

    /** Missing or invalid API key (HTTP 401), or a 400 whose reason references the API key. */
    INVALID_API_KEY("invalidApiKey"),

    /** Unclassified server-side failure while processing the reading. */
    PROCESSING_FAILED("processingFailed"),

    /** The operator is not mapped to the submitted (state/centre) scheme. */
    OPERATOR_NOT_MAPPED_TO_SCHEME("operatorNotMappedToScheme"),

    /** The submitted scheme could not be found. */
    SCHEME_NOT_FOUND("schemeNotFound"),

    /** The operator could not be found. */
    OPERATOR_NOT_FOUND("operatorNotFound"),

    /** An internal server error (HTTP 500) surfaced as a business response. */
    SERVER_ERROR("serverError"),

    /** A client request error (HTTP 400) not matched by a more specific code. */
    BAD_REQUEST("badRequest"),

    /** Fallback used when the failure could not be classified (e.g. null/blank reason). */
    REQUEST_FAILED("requestFailed");

    private final String code;

    TelemetryErrorCode(String code) {
        this.code = code;
    }

    /** The stable lowerCamelCase value serialized to the {@code error_code} JSON field. */
    @JsonValue
    public String code() {
        return code;
    }
}
