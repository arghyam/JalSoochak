package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.MeterRegister;
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
 * <p>The value is converted from the declared unit to the unit it is stored in here, before anything
 * compares it with a stored reading: the channel's standard unit, or kVAh as it is
 * ({@link ReadingUnit#storedUnit()}). A PDU run longer than a day is rejected, and so is a value above
 * the channel's configured maximum ({@link ManualReadingMaxValues}), compared in that stored unit.
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
        return capture(input.tenantId(), input.channel(), input.submittedValue(), input.readingUnit(),
                MeterRegister.STANDARD, source);
    }

    /**
     * Checks a value that corrects a stored reading of {@code channel}, with the same unit and limit
     * rules as a submission. A value with no unit stays on the row's register, so correcting a kVAh
     * reading without naming a unit doesn't turn it into a kWh one. The captured source is
     * {@code SOURCE_MANUAL}; a correction path that keeps the row's existing provenance when the value
     * doesn't change decides that itself.
     *
     * @param tenantId    tenant whose maximum applies
     * @param rowRegister the register of the reading being corrected
     * @param readingUnit the unit the correction declared, which wins over the row's register; null or
     *                    blank means the unit the row's register is stored in
     */
    public CaptureOutcome captureCorrection(Integer tenantId, ReadingChannel channel, MeterRegister rowRegister,
                                            BigDecimal value, String readingUnit) {
        return capture(tenantId, channel, value, readingUnit, rowRegister, RolloverResolutionService.SOURCE_MANUAL);
    }

    /** @param undeclaredRegister the register a value with no declared unit is on */
    private CaptureOutcome capture(Integer tenantId, ReadingChannel channel, BigDecimal value, String readingUnit,
                                   MeterRegister undeclaredRegister, int source) {
        if (!ReadingUnit.isDeclared(readingUnit)) {
            return withinLimits(tenantId, channel, value, channel.storedUnit(undeclaredRegister).orElse(null),
                    source);
        }
        return ReadingUnit.parseFor(channel, readingUnit)
                .map(unit -> withinLimits(tenantId, channel, unit.toStoredUnit(value), unit, source))
                .orElseGet(() -> new CaptureOutcome.Rejected(
                        TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                        ReadingUnit.unsupportedMessage(channel)));
    }

    /**
     * {@code storedValue} is already in the unit it is stored in, so a PDU value is in minutes.
     *
     * @param unit the unit the value arrived in; null for a channel with no units (IOT, MAN)
     */
    private CaptureOutcome withinLimits(Integer tenantId, ReadingChannel channel, BigDecimal storedValue,
                                        ReadingUnit unit, int source) {
        if (channel == ReadingChannel.PDU && storedValue.compareTo(PDU_MAX_MINUTES) > 0) {
            return new CaptureOutcome.Rejected(TelemetryErrorCode.ABNORMAL_READING, PDU_RUN_TOO_LONG_MESSAGE);
        }
        Optional<BigDecimal> max = maxValues.maxFor(tenantId, channel);
        if (max.isPresent() && storedValue.compareTo(max.get()) > 0) {
            return new CaptureOutcome.Rejected(TelemetryErrorCode.ABNORMAL_READING,
                    aboveMaximumMessage(unit == null ? null : unit.storedUnit(), max.get()));
        }
        return new CaptureOutcome.Captured(new CapturedReading(storedValue, unit, null, null, source, null));
    }

    /**
     * States the limit in the unit the value was compared in.
     *
     * @param storedUnit null for a channel with no units, whose limit is stated without one
     */
    static String aboveMaximumMessage(ReadingUnit storedUnit, BigDecimal max) {
        String unit = storedUnit == null ? "" : switch (storedUnit) {
            case CUBIC_METRE -> " m³";
            case KILOWATT_HOUR -> " kWh";
            case KILOVOLT_AMPERE_HOUR -> " kVAh";
            case MINUTE -> " minutes";
            case KILOLITRE, LITRE, HOUR -> throw new IllegalArgumentException(storedUnit + " is never a stored unit");
        };
        return ABOVE_MAXIMUM_MESSAGE_PREFIX + max.stripTrailingZeros().toPlainString() + unit + ".";
    }
}
