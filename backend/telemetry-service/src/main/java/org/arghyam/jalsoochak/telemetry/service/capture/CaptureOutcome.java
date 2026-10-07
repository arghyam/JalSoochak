package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;

/**
 * What a {@link ReadingCapture} produced.
 *
 * <p>Sealed so the submission pipeline has to handle every arm: an outcome added later won't
 * compile until the pipeline decides what to answer with it. None of them is an exception, because
 * the catch-all around the reading APIs would turn an exception into {@code PROCESSING_FAILED}.
 */
public sealed interface CaptureOutcome
        permits CaptureOutcome.Captured, CaptureOutcome.Rejected, CaptureOutcome.Retry {

    /** A reading to store. */
    record Captured(CapturedReading reading) implements CaptureOutcome {
    }

    /**
     * The submission can't be turned into a reading. Answered with {@code errorCode}; nothing is
     * stored.
     */
    record Rejected(TelemetryErrorCode errorCode, String message) implements CaptureOutcome {
    }

    /** OCR is temporarily unavailable; the submitter should try again shortly. Nothing is stored. */
    record Retry(String message) implements CaptureOutcome {
    }
}
