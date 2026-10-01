package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.RolloverResolutionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Captures a value someone typed in or an integrating system asserted. Nothing is extracted, so the
 * reading carries no extracted value, confidence or OCR result.
 *
 * <p>The value is converted from the declared unit to the channel's standard unit here, before
 * anything compares it with a stored reading. A PDU run longer than a day is rejected.
 *
 * <p>A correction goes through the same rules through {@link #captureCorrection}, so a stored reading
 * can't be changed to a value its submission would have been refused.
 */
@Component
public class SubmittedValueCapture implements ReadingCapture {

    /** A pump can't run for more than a day in one run. */
    static final BigDecimal PDU_MAX_MINUTES = new BigDecimal("1440");

    /** Also sent to WhatsApp operators; {@code ConversationLocalizationService} translates it. */
    public static final String PDU_RUN_TOO_LONG_MESSAGE =
            "Pump running time can't be more than 24 hours (1440 minutes).";

    @Override
    public CaptureOutcome capture(CaptureInput input) {
        int source = input.externallyAsserted()
                ? RolloverResolutionService.SOURCE_EXTERNALLY_ASSERTED
                : RolloverResolutionService.SOURCE_MANUAL;
        return capture(input.channel(), input.submittedValue(), input.readingUnit(), source);
    }

    /**
     * Checks a value that corrects a stored reading of {@code channel}, with the same unit and limit
     * rules as a submission. The captured source is {@code SOURCE_MANUAL}; a correction path that keeps
     * the row's existing provenance when the value doesn't change decides that itself.
     *
     * @param readingUnit the unit the correction declared; null or blank means the channel's standard
     *                    unit
     */
    public CaptureOutcome captureCorrection(ReadingChannel channel, BigDecimal value, String readingUnit) {
        return capture(channel, value, readingUnit, RolloverResolutionService.SOURCE_MANUAL);
    }

    private static CaptureOutcome capture(ReadingChannel channel, BigDecimal value, String readingUnit, int source) {
        if (!ReadingUnit.isDeclared(readingUnit)) {
            return withinLimits(channel, value, channel.standardUnit().orElse(null), source);
        }
        return ReadingUnit.parseFor(channel, readingUnit)
                .map(unit -> withinLimits(channel, unit.toStandardUnit(value), unit, source))
                .orElseGet(() -> new CaptureOutcome.Rejected(
                        TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                        ReadingUnit.unsupportedMessage(channel)));
    }

    /** {@code standardValue} is already in the channel's standard unit, so a PDU value is in minutes. */
    private static CaptureOutcome withinLimits(ReadingChannel channel, BigDecimal standardValue,
                                               ReadingUnit unit, int source) {
        if (channel == ReadingChannel.PDU && standardValue.compareTo(PDU_MAX_MINUTES) > 0) {
            return new CaptureOutcome.Rejected(TelemetryErrorCode.ABNORMAL_READING, PDU_RUN_TOO_LONG_MESSAGE);
        }
        return new CaptureOutcome.Captured(new CapturedReading(standardValue, unit, null, null, source, null));
    }
}
