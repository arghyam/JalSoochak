package org.arghyam.jalsoochak.telemetry.service.capture;

/**
 * The first step of a reading submission: turns what was submitted, a meter photo or a value, into
 * the reading the rest of the pipeline stores, or into the reason it can't be.
 *
 * <p>{@link ImageReadingCapture} reads a photo through OCR. {@link SubmittedValueCapture} takes a
 * value someone typed in or an integrating system asserted. Both hand the same
 * {@link CapturedReading} to the one shared submission pipeline, so nothing after this step depends
 * on how the value arrived beyond what the reading itself records.
 */
public interface ReadingCapture {

    CaptureOutcome capture(CaptureInput input);
}
