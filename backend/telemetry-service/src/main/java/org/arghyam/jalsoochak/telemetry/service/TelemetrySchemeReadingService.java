package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.response.UpdateYesterdayFinalReadingBySchemeResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryCompletedFlowReading;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetrySchemeReadingService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TelemetryEventPublisher telemetryEventPublisher;

    public UpdateYesterdayFinalReadingBySchemeResponse updateYesterdayFinalReadingBySchemeId(Long schemeId,
                                                                                            String phoneNumber,
                                                                                            BigDecimal finalReading,
                                                                                            LocalDate date) {
        return updateYesterdayFinalReadingBySchemeId(schemeId, phoneNumber, finalReading, date, null);
    }

    /**
     * {@code authenticatedTenantId} is the tenant the caller proved with its API key, and it decides
     * which schema the correction is applied to. The {@code X-Tenant-Code} header behind
     * {@link TenantContext} remains the fallback for in-process callers with no authenticated tenant,
     * but it must never win over the key: it is unauthenticated, so letting it decide would let a
     * caller holding one tenant's key — or none at all — correct another tenant's readings.
     */
    public UpdateYesterdayFinalReadingBySchemeResponse updateYesterdayFinalReadingBySchemeId(Long schemeId,
                                                                                            String phoneNumber,
                                                                                            BigDecimal finalReading,
                                                                                            LocalDate date,
                                                                                            Integer authenticatedTenantId) {
        String maskedPhone = maskPhone(phoneNumber);
        try {
            if (schemeId == null || schemeId < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schemeId must be a positive integer");
            }
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumber is required");
            }
            if (finalReading == null || finalReading.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reading must be greater than zero");
            }

            String schemaName = requireTenantSchema(authenticatedTenantId);
            log.info("[update-yesterday-final-reading] start schemeId={} tenantSchema={} phone={}", schemeId, schemaName, maskedPhone);

            Long updaterUserId = telemetryTenantRepository.findUserIdByPhone(schemaName, phoneNumber)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found for phoneNumber"));
            Integer tenantId = telemetryTenantRepository.findTenantIdBySchemaName(schemaName);
            if (tenantId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found for schema");
            }

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

            // Validate against the reading immediately before the target record, even if both
            // submissions happened on the same date.
            Optional<TelemetryCompletedFlowReading> previousReadingOpt =
                    telemetryTenantRepository.findPreviousFlowReadingForScheme(schemaName, targetDayRecord.id());
            if (previousReadingOpt.isPresent()) {
                TelemetryCompletedFlowReading previousReading = previousReadingOpt.get();
                BigDecimal minReading = previousReading.confirmedReading();
//                if (minReading != null && finalReading.compareTo(minReading) <= 0) {
//                    throw new ResponseStatusException(
//                            HttpStatus.BAD_REQUEST,
//                            "reading must be greater than last confirmed reading (" + previousReading.readingDate() + ")"
//                    );
//                }
            }

            log.info("[update-yesterday-final-reading] targetDayRecord id={} date={} createdBy={}",
                    targetDayRecord.id(), targetDayRecord.readingDate(), targetDayRecord.createdBy());

            Optional<TelemetryCompletedFlowReading> dayBeforeTargetOpt = previousReadingOpt;
            Optional<TelemetryCompletedFlowReading> dayAfterTargetOpt =
                    telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDateForScheme(schemaName, schemeId, targetDay);

            if (dayBeforeTargetOpt.isPresent()) {
                TelemetryCompletedFlowReading dayBeforeTarget = dayBeforeTargetOpt.get();
                log.info("[update-yesterday-final-reading] previousRecord date={} reading={} (no min-check enforced)",
                        dayBeforeTarget.readingDate(), dayBeforeTarget.confirmedReading());
            } else {
                log.info("[update-yesterday-final-reading] previousRecord none (no min-check enforced)");
            }

            // Always treat this as a "confirmed correction": keep extracted_reading untouched (or 0 for created rows).
            telemetryTenantRepository.updateConfirmedReading(schemaName, targetDayRecord.id(), finalReading, updaterUserId);
            log.info("[update-yesterday-final-reading] updated readingId={} newFinalReading={}",
                    targetDayRecord.id(), finalReading);

            BigDecimal previousDayConfirmedReading = dayBeforeTargetOpt
                    .map(TelemetryCompletedFlowReading::confirmedReading)
                    .orElse(BigDecimal.ZERO);
            BigDecimal targetDayWaterQuantity = finalReading.subtract(previousDayConfirmedReading);

            Long eventUserId = targetDayRecord.createdBy() != null ? targetDayRecord.createdBy() : updaterUserId;
            telemetryEventPublisher.publishWaterQuantityRecorded(
                    tenantId,
                    schemeId,
                    eventUserId,
                    targetDay,
                    targetDayWaterQuantity,
                    1
            );
            if (dayAfterTargetOpt.isPresent()) {
                TelemetryCompletedFlowReading dayAfter = dayAfterTargetOpt.get();
                BigDecimal dayAfterWaterQuantity = dayAfter.confirmedReading().subtract(finalReading);
                telemetryEventPublisher.publishWaterQuantityRecorded(
                        tenantId,
                        schemeId,
                        dayAfter.createdBy() != null ? dayAfter.createdBy() : eventUserId,
                        dayAfter.readingDate(),
                        dayAfterWaterQuantity,
                        1
                );
            }

            log.info("[update-yesterday-final-reading] success schemeId={} date={}", schemeId, targetDay);
            return UpdateYesterdayFinalReadingBySchemeResponse.builder()
                    .success(true)
                    .schemeId(schemeId)
                    .readingDate(targetDay.toString())
                    .finalReading(finalReading)
                    .message("Final reading updated successfully.")
                    .build();
        } catch (ResponseStatusException e) {
            log.info("[update-yesterday-final-reading] rejected schemeId={} phone={} status={} reason={}",
                    schemeId, maskedPhone, e.getStatusCode(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("[update-yesterday-final-reading] failed schemeId={} phone={} err={}",
                    schemeId, maskedPhone, e.getMessage(), e);
            throw e;
        }
    }

    private String requireTenantSchema(Integer authenticatedTenantId) {
        if (authenticatedTenantId != null) {
            return telemetryTenantRepository.findSchemaNameByTenantId(authenticatedTenantId)
                    .filter(schema -> !schema.isBlank())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Tenant not found for API key"));
        }
        String schemaName = TenantContext.getSchema();
        if (schemaName == null || schemaName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tenant could not be resolved. Ensure X-Tenant-Code header is set."
            );
        }
        return schemaName;
    }

    private static String maskPhone(String phoneNumber) {
        if (phoneNumber == null) {
            return "null";
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    // No JWT authentication for this endpoint. We resolve the user by phone number and then enforce scheme mapping.
}
