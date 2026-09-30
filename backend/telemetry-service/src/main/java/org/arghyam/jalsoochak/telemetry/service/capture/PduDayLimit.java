package org.arghyam.jalsoochak.telemetry.service.capture;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A scheme's PDU runs on one day can't add up to more than the day, 1,440 minutes. Applies to
 * submissions and corrections alike, on top of {@link SubmittedValueCapture}'s limit on a single run.
 *
 * <p>Checked against the runs already stored, before the value is written, so two runs submitted
 * for the same day at the same moment can each pass.
 */
@Component
@RequiredArgsConstructor
public class PduDayLimit {

    /** Also sent to WhatsApp operators; {@code ConversationLocalizationService} translates it. */
    public static final String PDU_DAY_TOO_LONG_MESSAGE =
            "Total pump running time for the day can't be more than 24 hours (1440 minutes).";

    public static final CaptureOutcome.Rejected EXCEEDED =
            new CaptureOutcome.Rejected(TelemetryErrorCode.ABNORMAL_READING, PDU_DAY_TOO_LONG_MESSAGE);

    private final TelemetryTenantRepository telemetryTenantRepository;

    /**
     * @param minutes           the run's value, in minutes
     * @param replacedReadingId the row the value is written over, whose stored minutes don't count;
     *                          null when the value adds a row
     */
    public boolean wouldExceed(String schemaName,
                               Long schemeId,
                               LocalDate readingDate,
                               BigDecimal minutes,
                               Long replacedReadingId) {
        BigDecimal otherRuns = telemetryTenantRepository.sumPduMinutesForDay(
                schemaName, schemeId, readingDate, replacedReadingId);
        return otherRuns.add(minutes).compareTo(SubmittedValueCapture.PDU_MAX_MINUTES) > 0;
    }
}
