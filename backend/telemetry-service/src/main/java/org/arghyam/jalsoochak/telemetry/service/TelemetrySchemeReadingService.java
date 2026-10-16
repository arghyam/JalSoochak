package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.MeterRegister;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.UpdateYesterdayFinalReadingBySchemeResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryCompletedFlowReading;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.CaptureOutcome;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetrySchemeReadingService {

    private static final String SCHEMA_PREFIX = "tenant_";
    /** A state code that can name a tenant schema; anything else is refused before it reaches SQL. */
    private static final Pattern TENANT_STATE_CODE = Pattern.compile("^[a-z0-9_]+$");

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final ReadingRepublisher readingRepublisher;
    private final SubmittedValueCapture submittedValueCapture;

    /**
     * An SO/SDO's correction of a scheme's latest submitted BFM reading, or of the one on {@code date}.
     *
     * <p>The tenant and the user both come from the officer's verified token: the tenant from
     * {@code tenant_state_code}, the user from the subject. The scheme must then be mapped to that
     * user. Nothing in the request's headers or body selects any of the three.
     */
    public UpdateYesterdayFinalReadingBySchemeResponse updateYesterdayFinalReadingBySchemeId(Long schemeId,
                                                                                            BigDecimal finalReading,
                                                                                            LocalDate date,
                                                                                            StaffCaller caller) {
        try {
            if (schemeId == null || schemeId < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schemeId must be a positive integer");
            }
            if (finalReading == null || finalReading.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reading must be greater than zero");
            }
            if (caller == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
            }

            String schemaName = requireCallerSchema(caller.tenantStateCode());
            Integer tenantId = telemetryTenantRepository.findTenantIdBySchemaName(schemaName);
            if (tenantId == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this tenant");
            }
            Long updaterUserId = telemetryTenantRepository.findUserIdByUuid(schemaName, caller.userUuid())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "User not found for token"));
            log.info("[update-yesterday-final-reading] start schemeId={} tenantSchema={} userId={}",
                    schemeId, schemaName, updaterUserId);

            boolean mapped = telemetryTenantRepository.isUserMappedToScheme(schemaName, updaterUserId, schemeId);
            log.info("[update-yesterday-final-reading] resolved userId={} mappedToScheme={}", updaterUserId, mapped);
            if (!mapped) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this scheme");
            }

            Optional<TelemetryCompletedFlowReading> targetReadingOpt = date != null
                    ? telemetryTenantRepository.findLatestCompletedFlowReadingOnDate(schemaName, schemeId, date)
                    : telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(schemaName, schemeId);
            if (targetReadingOpt.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        date != null
                                ? "No submitted reading found for the provided date"
                                : "No submitted reading found for this scheme"
                );
            }

            TelemetryCompletedFlowReading targetDayRecord = targetReadingOpt.get();
            LocalDate targetDay = targetDayRecord.readingDate();
            if (targetDay == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Target reading date is missing");
            }

            log.info("[update-yesterday-final-reading] targetDayRecord id={} date={} createdBy={}",
                    targetDayRecord.id(), targetDayRecord.readingDate(), targetDayRecord.createdBy());

            // A correction can't store what a submission on the same channel would have been refused.
            // The target is always a BFM row in m3, as below.
            if (submittedValueCapture.captureCorrection(
                    tenantId, ReadingChannel.BFM, MeterRegister.STANDARD, finalReading, null)
                    instanceof CaptureOutcome.Rejected rejected) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.message());
            }

            // Always treat this as a "confirmed correction": keep extracted_reading untouched (or 0 for created rows).
            // The officer's number is a manual override, so the row must also stop claiming its
            // confirmed_reading is what the AI extracted (confirmed_reading_source DEFAULT 0 =
            // AS_EXTRACTED). Retag only when the value actually moves, so a correction that restates the
            // stored number preserves an existing ROLLOVER_RESOLVED or EXTERNALLY_ASSERTED marker.
            // The target is always a BFM row and the request has no unit field, so the value is in m3.
            telemetryTenantRepository.updateConfirmedReading(schemaName, targetDayRecord.id(), finalReading, updaterUserId,
                    RolloverResolutionService.manualConfirmSource(finalReading, targetDayRecord.confirmedReading()),
                    ReadingUnit.CUBIC_METRE.code(), ReportingChannel.WEB);
            log.info("[update-yesterday-final-reading] updated readingId={} newFinalReading={}",
                    targetDayRecord.id(), finalReading);

            // Analytics recalculates the corrected day and the day after it from the stored readings, so
            // the corrected row is published again rather than a water quantity worked out here.
            readingRepublisher.republish(schemaName, tenantId, targetDayRecord.id());
            log.info("[update-yesterday-final-reading] republished readingId={}", targetDayRecord.id());

            log.info("[update-yesterday-final-reading] success schemeId={} date={}", schemeId, targetDay);
            return UpdateYesterdayFinalReadingBySchemeResponse.builder()
                    .success(true)
                    .schemeId(schemeId)
                    .readingDate(targetDay.toString())
                    .finalReading(finalReading)
                    .message("Final reading updated successfully.")
                    .build();
        } catch (ResponseStatusException e) {
            log.info("[update-yesterday-final-reading] rejected schemeId={} status={} reason={}",
                    schemeId, e.getStatusCode(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("[update-yesterday-final-reading] failed schemeId={} err={}", schemeId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * The schema the token's {@code tenant_state_code} names. A token with no usable tenant is refused
     * as unauthorised for any tenant, rather than reaching the schema-name guard as a server error.
     */
    private static String requireCallerSchema(String tenantStateCode) {
        String code = tenantStateCode == null ? "" : tenantStateCode.trim().toLowerCase(Locale.ROOT);
        if (!TENANT_STATE_CODE.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this tenant");
        }
        return SCHEMA_PREFIX + code;
    }
}
