package org.arghyam.jalsoochak.telemetry.service.capture;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A scheme's PDU runs on one day can't add up to more than the day, 1,440 minutes. Applies to
 * submissions and corrections alike, on top of {@link SubmittedValueCapture}'s limit on a single run.
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
    private final TransactionTemplate transactionTemplate;

    /**
     * Writes a PDU run unless it would take its scheme's day past the limit. The day stays locked from
     * before the check until the write commits, so a run sent at the same moment for the same scheme
     * and day waits, then counts this one. Joins the caller's transaction if there is one, and the lock
     * is then held until that transaction ends.
     *
     * @param minutes           the run's value, in minutes
     * @param replacedReadingId the row {@code write} writes over, whose stored minutes don't count;
     *                          looked up under the lock, and null when {@code write} adds a row
     * @param write             the write, which must return a non-null result
     * @return what {@code write} returned, or empty, with nothing written, when the day would pass the
     *         limit
     */
    public <T> Optional<T> writeWithinLimit(String schemaName,
                                            Long schemeId,
                                            LocalDate readingDate,
                                            BigDecimal minutes,
                                            Supplier<Long> replacedReadingId,
                                            Supplier<T> write) {
        return transactionTemplate.execute(status -> {
            telemetryTenantRepository.lockPduDay(schemaName, schemeId, readingDate);
            BigDecimal otherRuns = telemetryTenantRepository.sumPduMinutesForDay(
                    schemaName, schemeId, readingDate, replacedReadingId.get());
            if (otherRuns.add(minutes).compareTo(SubmittedValueCapture.PDU_MAX_MINUTES) > 0) {
                return Optional.empty();
            }
            return Optional.of(write.get());
        });
    }
}
