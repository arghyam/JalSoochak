package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class FlowVisionResult {

    private BigDecimal adjustedReading;

    private String requestId;

    private String correlationId;

    private String qualityStatus;

    private BigDecimal qualityConfidence;

    private String rejectionReason;

    /**
     * Raw {@code data.meterReading} string (built from FlowVision's {@code selectedDigit}s), before the
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
