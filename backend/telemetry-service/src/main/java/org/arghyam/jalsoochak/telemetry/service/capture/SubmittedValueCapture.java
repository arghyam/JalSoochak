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
 * anything compares it with a stored reading.
 */
@Component
public class SubmittedValueCapture implements ReadingCapture {

    @Override
    public CaptureOutcome capture(CaptureInput input) {
        ReadingChannel channel = input.channel();
        int source = input.externallyAsserted()
                ? RolloverResolutionService.SOURCE_EXTERNALLY_ASSERTED
                : RolloverResolutionService.SOURCE_MANUAL;
        if (!ReadingUnit.isDeclared(input.readingUnit())) {
            return captured(input.submittedValue(), channel.standardUnit().orElse(null), source);
        }
        return ReadingUnit.parseFor(channel, input.readingUnit())
                .map(unit -> captured(unit.toStandardUnit(input.submittedValue()), unit, source))
                .orElseGet(() -> new CaptureOutcome.Rejected(
                        TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                        ReadingUnit.unsupportedMessage(channel)));
    }

    private static CaptureOutcome captured(BigDecimal value, ReadingUnit unit, int source) {
        return new CaptureOutcome.Captured(new CapturedReading(value, unit, null, null, source, null));
    }
}
