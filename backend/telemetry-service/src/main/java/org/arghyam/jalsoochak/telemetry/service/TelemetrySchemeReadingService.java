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
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

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

            String schemaName = requireTenantSchema();
            log.info("[update-yesterday-final-reading] start schemeId={} tenantSchema={} phone={}", schemeId, schemaName, maskedPhone);

            Long updaterUserId = telemetryTenantRepository.findUserIdByPhone(schemaName, phoneNumber)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found for phoneNumber"));
            Integer tenantId = telemetryTenantRepository.findTenantIdBySchemaName(schemaName);
            if (tenantId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found for schema");
            }

            LocalDate targetDay = date != null ? date : LocalDate.now().minusDays(1);
            if (!targetDay.isBefore(LocalDate.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be in the past");
            }

            boolean mapped = telemetryTenantRepository.isUserMappedToScheme(schemaName, updaterUserId, schemeId);
            log.info("[update-yesterday-final-reading] resolved userId={} mappedToScheme={}", updaterUserId, mapped);
            if (!mapped) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this scheme");
            }

            // Find the baseline reading to compare against before making any DB changes.
            // We compare against the latest completed (confirmed) reading before targetDay.
            Optional<TelemetryCompletedFlowReading> baselineOpt =
                    telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDateForScheme(schemaName, schemeId, targetDay);
            if (baselineOpt.isPresent()) {
                TelemetryCompletedFlowReading baseline = baselineOpt.get();
                BigDecimal minReading = baseline.confirmedReading();
                if (minReading != null && finalReading.compareTo(minReading) <= 0) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "reading must be greater than last confirmed reading (" + baseline.readingDate() + ")"
                    );
                }
            }

            // Update is scoped to the scheme/day (not strictly to the user's own submissions).
            Optional<TelemetryCompletedFlowReading> targetDayRecordOpt =
                    telemetryTenantRepository.findLatestCompletedFlowReadingOnDate(schemaName, schemeId, targetDay);
            TelemetryCompletedFlowReading targetDayRecord;
            boolean createdTargetDayRecord = false;
            if (targetDayRecordOpt.isEmpty()) {
                // No completed reading yesterday for this scheme; create one so we can still apply the correction.
                String correlationId = "manual-prev-" + UUID.randomUUID();
                LocalDateTime readingAt = LocalDateTime.of(targetDay, LocalTime.of(23, 59, 59));
                Long readingId = telemetryTenantRepository.createFlowReading(
                        schemaName,
                        schemeId,
                        updaterUserId,
                        readingAt,
                        BigDecimal.ZERO,
                        finalReading,
                        correlationId,
                        "",
                        null
                );
                if (readingId == null) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create yesterday reading record");
                }
                targetDayRecord = new TelemetryCompletedFlowReading(readingId, correlationId, updaterUserId, targetDay, finalReading);
                createdTargetDayRecord = true;
                log.info("[update-yesterday-final-reading] created targetDayRecord id={} date={} createdBy={}",
                        targetDayRecord.id(), targetDayRecord.readingDate(), targetDayRecord.createdBy());
            } else {
                targetDayRecord = targetDayRecordOpt.get();
            }
            log.info("[update-yesterday-final-reading] targetDayRecord id={} date={} createdBy={}",
                    targetDayRecord.id(), targetDayRecord.readingDate(), targetDayRecord.createdBy());

            Optional<TelemetryCompletedFlowReading> dayBeforeTargetOpt = baselineOpt;
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
            log.info("[update-yesterday-final-reading] updated readingId={} newFinalReading={} createdRecord={}",
                    targetDayRecord.id(), finalReading, createdTargetDayRecord);

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

    private String requireTenantSchema() {
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
