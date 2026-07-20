package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, machine-readable error codes returned on telemetry reading responses
 * ({@link CreateReadingResponse} and {@link ReadingsDataResponse}).
 *
 * <p>This enum is the single source of truth for the {@code errorCode} field of the reading API.
 * Each constant serializes to its {@code UPPER_SNAKE_CASE} {@link #code()} value via {@link JsonValue};
 * the field is omitted from the JSON when {@code null} (the DTOs are annotated
 * {@code @JsonInclude(NON_NULL)}), so a code is present only on failure responses. The wire value
 * intentionally mirrors the constant name so the Java identifier and the JSON contract stay in step.
 *
 * <p><b>Contract note:</b> the wire values below are part of the public API. Consumers may branch on
 * them, so add new constants rather than repurposing existing ones, and do not rename a
 * {@link #code()} string once released.
 */
public enum TelemetryErrorCode {

    /** The FlowVision OCR call threw / failed while extracting a reading from the image. */
    FLOW_VISION_FAILED("FLOW_VISION_FAILED"),

    /**
     * Reserved for an explicit FlowVision provider "REJECTED" status. Not currently emitted — the
     * unreadable-image path uses {@link #UNREADABLE_IMAGE}. Kept for forward compatibility.
     */
    FLOW_VISION_REJECTED("FLOW_VISION_REJECTED"),

    /** FlowVision responded but produced no usable meter reading (image could not be read). */
    UNREADABLE_IMAGE("UNREADABLE_IMAGE"),

    /** The submitted image's extracted reading duplicates the previous confirmed reading. */
    DUPLICATE_IMAGE("DUPLICATE_IMAGE"),

    /** Request bean validation ({@code @Valid}) failed (e.g. a required field was blank). */
    VALIDATION_FAILED("VALIDATION_FAILED"),

    /** The request body could not be parsed (malformed JSON / unreadable message). */
    MALFORMED_REQUEST("MALFORMED_REQUEST"),

    /** Missing or invalid API key (HTTP 401), or a 400 whose reason references the API key. */
    INVALID_API_KEY("INVALID_API_KEY"),

    /** Unclassified server-side failure while processing the reading. */
    PROCESSING_FAILED("PROCESSING_FAILED"),

    /** The operator is not mapped to the submitted (state/centre) scheme. */
    OPERATOR_NOT_MAPPED_TO_SCHEME("OPERATOR_NOT_MAPPED_TO_SCHEME"),

    /** The submitted scheme could not be found. */
    SCHEME_NOT_FOUND("SCHEME_NOT_FOUND"),

    /** The operator could not be found. */
    OPERATOR_NOT_FOUND("OPERATOR_NOT_FOUND"),

    /** An internal server error (HTTP 500) surfaced as a business response. */
    SERVER_ERROR("SERVER_ERROR"),

    /** A client request error (HTTP 400) not matched by a more specific code. */
    BAD_REQUEST("BAD_REQUEST"),

    /** Fallback used when the failure could not be classified (e.g. null/blank reason). */
    REQUEST_FAILED("REQUEST_FAILED");

    private final String code;

    TelemetryErrorCode(String code) {
        this.code = code;
    }

    /** The stable {@code UPPER_SNAKE_CASE} value serialized to the {@code errorCode} JSON field. */
    @JsonValue
    public String code() {
        return code;
    }
}
