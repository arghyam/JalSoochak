package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;

import java.math.BigDecimal;

/**
 * A reading ready for the submission pipeline.
 *
 * @param value            the reading in the channel's standard unit, which is what
 *                         {@code confirmed_reading} holds
 * @param submittedUnit    the unit the value arrived in; null for a channel with no units defined
 *                         (IOT, MAN)
 * @param extractedReading what OCR read off the photo; null when nothing was extracted
 * @param confidence       OCR's confidence in {@code extractedReading}; null when nothing was
 *                         extracted
 * @param source           how the value arrived, as a {@code RolloverResolutionService.SOURCE_*}
 *                         value
 * @param ocrResult        the full OCR result; null when nothing was extracted
 */
public record CapturedReading(
        BigDecimal value,
        ReadingUnit submittedUnit,
        BigDecimal extractedReading,
        BigDecimal confidence,
        int source,
        OcrReadingResult ocrResult) {

    /** The code {@code submitted_unit} stores; null when there is no unit. */
    public String submittedUnitCode() {
        return submittedUnit == null ? null : submittedUnit.code();
    }
}
