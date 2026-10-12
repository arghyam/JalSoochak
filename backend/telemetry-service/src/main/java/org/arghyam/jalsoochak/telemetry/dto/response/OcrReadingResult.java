package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.Builder;
import lombok.Data;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OcrReadingResult {

    private BigDecimal adjustedReading;

    /**
     * The unit {@code adjustedReading} was read in; null means the channel's standard unit. Set only by
     * a provider that reads more than one register: the ELM provider reads kWh or kVAh. The value is
     * stored as read, so this must be a unit of the provider's channel that needs no converting; any
     * other unit fails the read.
     */
    private ReadingUnit readingUnit;

    private String requestId;

    private String correlationId;

    private String qualityStatus;

    private BigDecimal qualityConfidence;

    private String rejectionReason;

    /**
     * {@code true} when the provider found no meter in the photo at all (the provider's no-meter status:
     * a rotated, unrelated or random image). Distinct from an unreadable meter: the photo has to be
     * retaken of the meter, not just made clearer. Any digits returned alongside are ignored.
     */
    private boolean noMeter;

    /**
     * Raw {@code data.meterReading} string (built from the OCR provider's {@code selectedDigit}s), before the
     * red-last-digit decimal shift. Preserves digit count and leading zeros for rollover candidate
     * enumeration. {@code null} when the reading could not be parsed.
     */
    private String rawMeterReading;

    /** {@code true} when {@code data.lastDigitColor == "red"} — the last raw digit is a decimal. */
    private boolean redLastDigit;

    /** {@code true} when the OCR response reported rollover metadata. Old payloads yield {@code false}. */
    private boolean hasRollover;

    /**
     * Per-position rollover metadata. Empty (never {@code null}) when the payload carried no rollover
     * information, so callers can iterate without null checks.
     */
    @Builder.Default
    private List<RolloverPosition> rolloverPositions = List.of();
}
