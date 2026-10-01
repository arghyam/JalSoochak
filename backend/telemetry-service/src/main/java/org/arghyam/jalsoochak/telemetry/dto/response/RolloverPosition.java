package org.arghyam.jalsoochak.telemetry.dto.response;

import java.math.BigDecimal;

/**
 * OCR rollover-digit metadata for a single ambiguous digit position.
 *
 * <p>A mechanical water-meter digit wheel caught mid-turn is ambiguous between two adjacent values.
 * The OCR provider reports, per ambiguous position, its higher-confidence pick ({@code selectedValue},
 * already used to build {@code meterReading}) and the runner-up ({@code alternateValue}).
 *
 * @param position            1-indexed position from the left of the raw {@code meterReading} string; stored verbatim.
 * @param selectedValue       the digit the OCR provider picked (used to build {@code meterReading}).
 * @param selectedConfidence  confidence of the selected digit (may be {@code null} if absent in the payload).
 * @param alternateValue      the runner-up digit.
 * @param alternateConfidence confidence of the alternate digit (may be {@code null} if absent in the payload).
 */
public record RolloverPosition(
        int position,
        int selectedValue,
        BigDecimal selectedConfidence,
        int alternateValue,
        BigDecimal alternateConfidence
) {
}
