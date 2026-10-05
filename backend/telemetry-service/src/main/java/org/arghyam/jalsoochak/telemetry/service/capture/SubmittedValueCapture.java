package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.RolloverResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Captures a value someone typed in or an integrating system asserted. Nothing is extracted, so the
 * reading carries no extracted value, confidence or OCR result.
 *
 * <p>The value is converted from the declared unit to the channel's standard unit here, before
 * anything compares it with a stored reading. A PDU run longer than a day is rejected, and so is a value
 * above the channel's configured maximum ({@link ManualReadingMaxValues}).
 *
 * <p>A correction goes through the same rules through {@link #captureCorrection}, so a stored reading
 * can't be changed to a value its submission would have been refused.
 */
@Component
@RequiredArgsConstructor
public class SubmittedValueCapture implements ReadingCapture {

    /** A pump can't run for more than a day in one run. */
    static final BigDecimal PDU_MAX_MINUTES = new BigDecimal("1440");

    /** Also sent to WhatsApp operators; {@code ConversationLocalizationService} translates it. */
    public static final String PDU_RUN_TOO_LONG_MESSAGE =
            "Pump running time can't be more than 24 hours (1440 minutes).";

    /**
     * How the message for a value above the configured maximum starts. Also sent to WhatsApp operators;
     * {@code ConversationLocalizationService} translates it.
     */
    public static final String ABOVE_MAXIMUM_MESSAGE_PREFIX = "Reading can't be more than ";

    private final ManualReadingMaxValues maxValues;

    @Override
    public CaptureOutcome capture(CaptureInput input) {
        int source = input.externallyAsserted()
                ? RolloverResolutionService.SOURCE_EXTERNALLY_ASSERTED
                : RolloverResolutionService.SOURCE_MANUAL;
        return capture(input.tenantId(), input.channel(), input.submittedValue(), input.readingUnit(), source);
    }

    /**
     * Checks a value that corrects a stored reading of {@code channel}, with the same unit and limit
     * rules as a submission. The captured source is {@code SOURCE_MANUAL}; a correction path that keeps
     * the row's existing provenance when the value doesn't change decides that itself.
     *
     * @param tenantId    tenant whose maximum applies
     * @param readingUnit the unit the correction declared; null or blank means the channel's standard
     *                    unit
     */
    public CaptureOutcome captureCorrection(Integer tenantId, ReadingChannel channel, BigDecimal value,
                                            String readingUnit) {
        return capture(tenantId, channel, value, readingUnit, RolloverResolutionService.SOURCE_MANUAL);
    }

    private CaptureOutcome capture(Integer tenantId, ReadingChannel channel, BigDecimal value, String readingUnit,
                                   int source) {
        if (!ReadingUnit.isDeclared(readingUnit)) {
            return withinLimits(tenantId, channel, value, channel.standardUnit().orElse(null), source);
        }
        return ReadingUnit.parseFor(channel, readingUnit)
                .map(unit -> withinLimits(tenantId, channel, unit.toStandardUnit(value), unit, source))
                .orElseGet(() -> new CaptureOutcome.Rejected(
                        TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                        ReadingUnit.unsupportedMessage(channel)));
    }

    /** {@code standardValue} is already in the channel's standard unit, so a PDU value is in minutes. */
    private CaptureOutcome withinLimits(Integer tenantId, ReadingChannel channel, BigDecimal standardValue,
                                        ReadingUnit unit, int source) {
        if (channel == ReadingChannel.PDU && standardValue.compareTo(PDU_MAX_MINUTES) > 0) {
            return new CaptureOutcome.Rejected(TelemetryErrorCode.ABNORMAL_READING, PDU_RUN_TOO_LONG_MESSAGE);
        }
        Optional<BigDecimal> max = maxValues.maxFor(tenantId, channel);
        if (max.isPresent() && standardValue.compareTo(max.get()) > 0) {
            return new CaptureOutcome.Rejected(
                    TelemetryErrorCode.ABNORMAL_READING, aboveMaximumMessage(channel, max.get()));
        }
        return new CaptureOutcome.Captured(new CapturedReading(standardValue, unit, null, null, source, null));
    }

    /** States the limit in the channel's standard unit, which is what the value was compared in. */
    static String aboveMaximumMessage(ReadingChannel channel, BigDecimal max) {
        String unit = switch (channel) {
            case BFM -> " m³";
            case ELM -> " kWh";
            case PDU -> " minutes";
            default -> "";
        };
        return ABOVE_MAXIMUM_MESSAGE_PREFIX + max.stripTrailingZeros().toPlainString() + unit + ".";
    }
}
