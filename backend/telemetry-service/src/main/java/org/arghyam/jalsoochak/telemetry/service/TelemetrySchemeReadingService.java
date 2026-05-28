package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TelemetrySchemeReadingService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TelemetryEventPublisher telemetryEventPublisher;

    public UpdateYesterdayFinalReadingBySchemeResponse updateYesterdayFinalReadingBySchemeId(Long schemeId,
                                                                                            String phoneNumber,
                                                                                            BigDecimal finalReading) {
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
        Long updaterUserId = telemetryTenantRepository.findUserIdByPhone(schemaName, phoneNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found for phoneNumber"));
        Integer tenantId = telemetryTenantRepository.findTenantIdBySchemaName(schemaName);
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found for schema");
        }

        LocalDate targetDay = LocalDate.now().minusDays(1);

        if (!telemetryTenantRepository.isUserMappedToScheme(schemaName, updaterUserId, schemeId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this scheme");
        }

        Optional<TelemetryCompletedFlowReading> targetDayRecordOpt =
                telemetryTenantRepository.findLatestCompletedFlowReadingOnDateForUser(schemaName, schemeId, updaterUserId, targetDay);
        if (targetDayRecordOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No completed reading found for yesterday for this user");
        }
        TelemetryCompletedFlowReading targetDayRecord = targetDayRecordOpt.get();

        Optional<TelemetryCompletedFlowReading> dayBeforeTargetOpt =
                telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDateForScheme(schemaName, schemeId, targetDay);
        Optional<TelemetryCompletedFlowReading> dayAfterTargetOpt =
                telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDateForScheme(schemaName, schemeId, targetDay);

        if (dayBeforeTargetOpt.isPresent()) {
            TelemetryCompletedFlowReading dayBeforeTarget = dayBeforeTargetOpt.get();
            if (finalReading.compareTo(dayBeforeTarget.confirmedReading()) <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "reading must be greater than the reading on " + dayBeforeTarget.readingDate()
                                + " (" + dayBeforeTarget.confirmedReading().toPlainString() + ")"
                );
            }
        }

        telemetryTenantRepository.updateReadingValues(schemaName, targetDayRecord.id(), finalReading, updaterUserId);

        BigDecimal previousDayConfirmedReading = dayBeforeTargetOpt
                .map(TelemetryCompletedFlowReading::confirmedReading)
                .orElse(BigDecimal.ZERO);
        BigDecimal targetDayWaterQuantity = finalReading.subtract(previousDayConfirmedReading);

        // For parity with the existing update-previous-reading flow, emit water quantity events for the target day
        // and the next day (if present) to correct downstream quantities.
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

        return UpdateYesterdayFinalReadingBySchemeResponse.builder()
                .success(true)
                .schemeId(schemeId)
                .readingDate(targetDay.toString())
                .finalReading(finalReading)
                .message("Yesterday final reading updated successfully.")
                .build();
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

    // No JWT authentication for this endpoint. We resolve the user by phone number and then enforce scheme mapping.
}
