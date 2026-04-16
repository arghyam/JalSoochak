package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BfmReadingService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final FlowVisionService flowVisionService;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;

    public CreateReadingResponse createReading(CreateReadingRequest request,
                                               String schemaName,
                                               TelemetryOperator operator,
                                               String contactId,
                                               boolean isMeterReplaced) {
        if (!telemetryTenantRepository.existsSchemeById(schemaName, request.getSchemeId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "State scheme not found");
        }

        TelemetryOperator operatorInRequest = telemetryTenantRepository
                .findOperatorById(schemaName, request.getOperatorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Operator not found"));
        Integer tenantId = operatorInRequest.tenantId();

        boolean belongsToScheme = telemetryTenantRepository
                .isOperatorMappedToScheme(schemaName, operatorInRequest.id(), request.getSchemeId());

        if (!belongsToScheme) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operator does not belong to the specified scheme");
        }
        FlowVisionResult ocrResult = null;
        BigDecimal finalReading = request.getReadingValue();
        BigDecimal confidenceLevel = null;
        String message = "Reading created successfully";

        if (finalReading == null) {
            if (request.getReadingUrl() == null || request.getReadingUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either readingValue or readingUrl must be provided");
            }

            try {
                ocrResult = flowVisionService.extractReading(request.getReadingUrl());
                if (ocrResult == null || ocrResult.getAdjustedReading() == null) {
                    String anomalyCorrelationId = buildImageAnomalyCorrelationId(
                            AnomalyConstants.TYPE_UNREADABLE_IMAGE,
                            operatorInRequest.id(),
                            request.getSchemeId(),
                            request.getReadingUrl()
                    );
                    int retries = telemetryTenantRepository.countAnomaliesByTypeForToday(
                            schemaName,
                            operatorInRequest.id(),
                            request.getSchemeId(),
                            AnomalyConstants.TYPE_UNREADABLE_IMAGE
                    ) + 1;
                    telemetryTenantRepository.createTenantAnomalyRecord(
                            schemaName,
                            operatorInRequest.id(),
                            request.getSchemeId(),
                            AnomalyConstants.TYPE_UNREADABLE_IMAGE,
                            "Unreadable image. OCR could not extract a valid meter reading.",
                            AnomalyConstants.STATUS_OPEN
                    );
                    telemetryEventPublisher.publishAnomalyRecorded(
                            tenantId,
                            AnomalyConstants.TYPE_UNREADABLE_IMAGE,
                            operatorInRequest.id(),
                            request.getSchemeId(),
                            null,
                            null,
                            null,
                            retries,
                            null,
                            null,
                            0,
                            "Unreadable image. OCR could not extract a valid meter reading.",
                            AnomalyConstants.STATUS_OPEN,
                            anomalyCorrelationId
                    );
                    return CreateReadingResponse.builder()
                            .success(false)
                            .message("Could not read meter value from image. Please retry with a clearer photo.")
                            .correlationId(UUID.randomUUID().toString())
                            .qualityStatus("REJECTED")
                            .build();
                }
                finalReading = ocrResult.getAdjustedReading();
                confidenceLevel = ocrResult.getQualityConfidence();
            } catch (Exception ex) {
                log.error("FlowVision OCR failed for URL: {}", request.getReadingUrl(), ex);
                String anomalyCorrelationId = buildImageAnomalyCorrelationId(
                        AnomalyConstants.TYPE_UNREADABLE_IMAGE,
                        operatorInRequest.id(),
                        request.getSchemeId(),
                        request.getReadingUrl()
                );
                int retries = telemetryTenantRepository.countAnomaliesByTypeForToday(
                        schemaName,
                        operatorInRequest.id(),
                        request.getSchemeId(),
                        AnomalyConstants.TYPE_UNREADABLE_IMAGE
                ) + 1;
                telemetryTenantRepository.createTenantAnomalyRecord(
                        schemaName,
                        operatorInRequest.id(),
                        request.getSchemeId(),
                        AnomalyConstants.TYPE_UNREADABLE_IMAGE,
                        "Unreadable image. OCR failed during extraction.",
                        AnomalyConstants.STATUS_OPEN
                );
                telemetryEventPublisher.publishAnomalyRecorded(
                        tenantId,
                        AnomalyConstants.TYPE_UNREADABLE_IMAGE,
                        operatorInRequest.id(),
                        request.getSchemeId(),
                        null,
                        null,
                        null,
                        retries,
                        null,
                        null,
                        0,
                        "Unreadable image. OCR failed during extraction.",
                        AnomalyConstants.STATUS_OPEN,
                        anomalyCorrelationId
                );
                return CreateReadingResponse.builder()
                        .success(false)
                        .message("OCR failed. Please try again with a clearer image.")
                        .correlationId(UUID.randomUUID().toString())
                        .qualityStatus("REJECTED")
                        .build();
            }
        }

        boolean hasPositiveReading = finalReading != null
                && finalReading.compareTo(BigDecimal.ZERO) > 0;
        boolean hasAcceptableConfidence = confidenceLevel == null
                || confidenceLevel.compareTo(BigDecimal.valueOf(0.7)) >= 0;
        boolean isValid = hasPositiveReading && hasAcceptableConfidence;

        String correlationId = Optional.ofNullable(ocrResult)
                .map(FlowVisionResult::getCorrelationId)
                .orElse(UUID.randomUUID().toString());
        LocalDateTime readingAt = Optional.ofNullable(request.getReadingTime()).orElse(LocalDateTime.now());

        BigDecimal extractedReading = Optional.ofNullable(ocrResult)
                .map(FlowVisionResult::getAdjustedReading)
                .orElse(finalReading);
        BigDecimal confirmedReading = request.getReadingValue() != null ? request.getReadingValue() : finalReading;

        Optional<TelemetryConfirmedReadingSnapshot> latestSnapshotOpt = telemetryTenantRepository
                .findLatestConfirmedReadingSnapshot(schemaName, request.getSchemeId(), null);

        // For non-meter-replacement submissions, validate against the latest confirmed reading.
        // Meter-replacement submissions are treated as a new baseline.
        Optional<TelemetryConfirmedReadingSnapshot> validationBaselineOpt = isMeterReplaced
                ? latestSnapshotOpt
                : latestSnapshotOpt;

        if (!isMeterReplaced
                && validationBaselineOpt.isPresent()
                && confirmedReading != null
                && confirmedReading.compareTo(validationBaselineOpt.get().confirmedReading()) < 0) {
            TelemetryConfirmedReadingSnapshot previousSnapshot = validationBaselineOpt.get();
            String reason = "Submitted reading is less than previous confirmed reading.";
            telemetryTenantRepository.createTenantAnomalyRecord(
                    schemaName,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS,
                    reason,
                    AnomalyConstants.STATUS_OPEN
            );
            telemetryEventPublisher.publishAnomalyRecorded(
                    tenantId,
                    AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    extractedReading,
                    confidenceLevel,
                    confirmedReading,
                    0,
                    previousSnapshot.confirmedReading(),
                    previousSnapshot.createdAt(),
                    0,
                    reason,
                    AnomalyConstants.STATUS_OPEN,
                    correlationId
            );
            return CreateReadingResponse.builder()
                    .success(false)
                    .message("Reading cannot be less than previous confirmed reading. Submitted reading: "
                            + toPlain(confirmedReading) + ". Previous reading: " + toPlain(previousSnapshot.confirmedReading()) + ".")
                    .correlationId(correlationId)
                    .meterReading(confirmedReading)
                    .qualityStatus("REJECTED")
                    .lastConfirmedReading(previousSnapshot.confirmedReading())
                    .build();
        }

        Optional<WaterSupplyThreshold> thresholdOpt = !isMeterReplaced ? loadWaterSupplyThreshold(tenantId) : Optional.empty();
        Optional<BigDecimal> waterNormOpt = !isMeterReplaced ? loadWaterNorm(tenantId) : Optional.empty();
        BigDecimal minAllowed = null;
        if (thresholdOpt.isPresent() && waterNormOpt.isPresent()) {
            WaterSupplyThreshold threshold = thresholdOpt.get();
            minAllowed = waterNormOpt.get()
                    .multiply(BigDecimal.valueOf(100.0d - threshold.undersupplyThresholdPercent()))
                    .divide(BigDecimal.valueOf(100.0d), 6, RoundingMode.HALF_UP);
        }

        if (!isMeterReplaced && confirmedReading != null && minAllowed != null
                && confirmedReading.compareTo(minAllowed) < 0) {
            TelemetryConfirmedReadingSnapshot previousSnapshot = validationBaselineOpt.orElse(null);
            BigDecimal previousConfirmed = previousSnapshot != null ? previousSnapshot.confirmedReading() : null;
            LocalDateTime previousConfirmedAt = previousSnapshot != null ? previousSnapshot.createdAt() : null;
            String reason = "Submitted reading is below allowed minimum (" + toPlain(minAllowed) + ").";
            telemetryTenantRepository.createTenantAnomalyRecord(
                    schemaName,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
                    reason,
                    AnomalyConstants.STATUS_OPEN
            );
            telemetryEventPublisher.publishAnomalyRecorded(
                    tenantId,
                    AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    extractedReading,
                    confidenceLevel,
                    confirmedReading,
                    0,
                    previousConfirmed,
                    previousConfirmedAt,
                    0,
                    reason,
                    AnomalyConstants.STATUS_OPEN,
                    null
            );
            return CreateReadingResponse.builder()
                    .success(false)
                    .message("Reading rejected because it is below the allowed minimum. Submitted: "
                            + toPlain(confirmedReading) + ". Minimum allowed: " + toPlain(minAllowed) + ".")
                    .correlationId(correlationId)
                    .meterReading(confirmedReading)
                    .qualityStatus("REJECTED")
                    .lastConfirmedReading(previousConfirmed)
                    .build();
        }

        if (latestSnapshotOpt.isPresent() && extractedReading != null
                && extractedReading.compareTo(latestSnapshotOpt.get().confirmedReading()) == 0
                && request.getReadingUrl() != null && !request.getReadingUrl().isBlank()) {
            TelemetryConfirmedReadingSnapshot previousSnapshot = latestSnapshotOpt.get();
            String anomalyCorrelationId = buildImageAnomalyCorrelationId(
                    AnomalyConstants.TYPE_DUPLICATE_IMAGE_SUBMISSION,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    request.getReadingUrl()
            );
            recordImageAnomalyOncePerDay(
                    schemaName,
                    tenantId,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    AnomalyConstants.TYPE_DUPLICATE_IMAGE_SUBMISSION,
                    "Duplicate image submission detected. Extracted reading matches previous confirmed reading.",
                    0,
                    extractedReading,
                    confidenceLevel,
                    confirmedReading,
                    previousSnapshot.confirmedReading(),
                    previousSnapshot.createdAt(),
                    0,
                    anomalyCorrelationId
            );
            return CreateReadingResponse.builder()
                    .success(false)
                    .message("Duplicate image submission detected. The extracted reading matches the previous reading.")
                    .correlationId(correlationId)
                    .meterReading(confirmedReading)
                    .qualityConfidence(confidenceLevel)
                    .qualityStatus("REJECTED")
                    .lastConfirmedReading(previousSnapshot.confirmedReading())
                    .build();
        }

        Long readingId = null;
        Optional<Long> placeholderIdOpt = telemetryTenantRepository.findLatestPlaceholderFlowReadingIdForDate(
                schemaName,
                request.getSchemeId(),
                operatorInRequest.id(),
                LocalDate.from(readingAt)
        );
        if (placeholderIdOpt.isPresent()) {
            readingId = placeholderIdOpt.get();
            telemetryTenantRepository.updateFlowReadingFromIngestion(
                    schemaName,
                    readingId,
                    readingAt,
                    extractedReading,
                    confirmedReading,
                    correlationId,
                    request.getReadingUrl(),
                    request.getMeterChangeReason(),
                    operatorInRequest.id()
            );
        } else {
            readingId = telemetryTenantRepository.createFlowReading(
                    schemaName,
                    request.getSchemeId(),
                    operatorInRequest.id(),
                    readingAt,
                    extractedReading,
                    confirmedReading,
                    correlationId,
                    request.getReadingUrl(),
                    request.getMeterChangeReason()
            );
        }

        BigDecimal lastConfirmedReading = latestSnapshotOpt
                .map(TelemetryConfirmedReadingSnapshot::confirmedReading)
                .orElse(null);
        if (lastConfirmedReading == null) {
            lastConfirmedReading = telemetryTenantRepository
                    .findLastConfirmedReading(schemaName, request.getSchemeId(), readingId)
                    .orElse(null);
        }

        telemetryEventPublisher.publishMeterReadingRecorded(
                tenantId,
                request.getSchemeId(),
                operatorInRequest.id(),
                extractedReading,
                confirmedReading,
                confidenceLevel,
                request.getReadingUrl(),
                readingAt,
                null,
                LocalDate.from(readingAt),
                1,
                0
        );

        String finalMessage;
        String readingText = finalReading != null ? finalReading.stripTrailingZeros().toPlainString() : null;
        if (isValid) {
            if (ocrResult != null && readingText != null) {
                finalMessage = "Reading captured successfully. Extracted reading: " + readingText;
            } else {
                finalMessage = "Reading captured successfully";
            }
        } else if (!hasPositiveReading) {
            finalMessage = "Invalid reading value";
        } else if (ocrResult != null && readingText != null) {
            finalMessage = "Low OCR confidence. Extracted reading: " + readingText + ". Please confirm reading.";
        } else {
            finalMessage = "Low OCR confidence. Please confirm reading.";
        }

        return CreateReadingResponse.builder()
                .success(hasPositiveReading)
                .message(messageOverride(contactId, finalMessage))
                .correlationId(correlationId)
                .meterReading(finalReading)
                .qualityConfidence(confidenceLevel)
                .qualityStatus(ocrResult != null ? ocrResult.getQualityStatus() : (isValid ? "CONFIRMED" : "REVIEW"))
                .lastConfirmedReading(lastConfirmedReading)
                .build();
    }

    @Transactional
    public CreateReadingResponse updateConfirmedReading(String correlationId, BigDecimal confirmedReading) {
        String schemaName = TenantContext.getSchema();
        if (schemaName == null || schemaName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant could not be resolved");
        }

        if (correlationId == null || correlationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correlationId must be provided");
        }

        if (confirmedReading == null || confirmedReading.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmedReading must be a non-negative number");
        }

        TelemetryReadingRecord reading = telemetryTenantRepository
                .findReadingByCorrelationId(schemaName, correlationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reading not found"));

        telemetryTenantRepository.updateConfirmedReading(
                schemaName,
                reading.id(),
                confirmedReading,
                reading.createdBy() != null ? reading.createdBy() : 1L
        );

        return CreateReadingResponse.builder()
                .success(true)
                .message("Reading updated successfully")
                .correlationId(reading.correlationId())
                .meterReading(confirmedReading)
                .qualityStatus("CONFIRMED")
                .build();
    }

    private String messageOverride(String contactId, String fallbackMessage) {
        if (contactId == null || contactId.isBlank()) {
            return fallbackMessage;
        }
        return fallbackMessage;
    }

    private Optional<BigDecimal> loadWaterNorm(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return safeFindConfigValue(tenantId, "WATER_NORM")
                .flatMap(raw -> {
                    try {
                        JsonNode root = objectMapper.readTree(raw);
                        String value = root != null ? root.path("value").asText(null) : null;
                        if (value == null || value.isBlank()) {
                            return Optional.empty();
                        }
                        String normalized = value.trim().replace(",", "");
                        if (!normalized.matches("^\\d+(\\.\\d+)?$")) {
                            return Optional.empty();
                        }
                        BigDecimal norm = new BigDecimal(normalized);
                        return norm.compareTo(BigDecimal.ZERO) > 0 ? Optional.of(norm) : Optional.empty();
                    } catch (Exception e) {
                        log.warn("Invalid WATER_NORM config for tenantId {}: {}", tenantId, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    private Optional<String> safeFindConfigValue(Integer tenantId, String key) {
        Optional<String> opt = tenantConfigRepository.findConfigValue(tenantId, key);
        return opt == null ? Optional.empty() : opt;
    }

    private Optional<WaterSupplyThreshold> loadWaterSupplyThreshold(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        Optional<String> rawOpt = safeFindConfigValue(tenantId, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD")
                .or(() -> safeFindConfigValue(tenantId, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .or(() -> safeFindConfigValue(0, "WATER_QUANTITY_SUPPLY_THRESHOLD"));
        if (rawOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(rawOpt.get());
            if (root == null || root.isNull() || !root.isObject()) {
                return Optional.empty();
            }
            double under = root.path("undersupplyThresholdPercent").asDouble(Double.NaN);
            double over = root.path("oversupplyThresholdPercent").asDouble(Double.NaN);
            if (!Double.isFinite(under) || !Double.isFinite(over)) {
                return Optional.empty();
            }
            if (under < 0.0d || under > 100.0d) {
                return Optional.empty();
            }
            if (over < 0.0d || over > 1000.0d) {
                return Optional.empty();
            }
            return Optional.of(new WaterSupplyThreshold(under, over));
        } catch (Exception e) {
            log.warn("Invalid water supply threshold config for tenantId {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String toPlain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String buildImageAnomalyCorrelationId(int anomalyType, Long userId, Long schemeId, String readingUrl) {
        String normalizedUrl = readingUrl == null ? "" : readingUrl.trim();
        String key = anomalyType + ":" + userId + ":" + schemeId + ":" + normalizedUrl;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void recordImageAnomalyOncePerDay(String schemaName,
                                              Integer tenantId,
                                              Long userId,
                                              Long schemeId,
                                              int anomalyType,
                                              String reason,
                                              int retries,
                                              BigDecimal aiReading,
                                              BigDecimal aiConfidencePercentage,
                                              BigDecimal overriddenReading,
                                              BigDecimal previousReading,
                                              LocalDateTime previousReadingDate,
                                              Integer consecutiveDaysMissed,
                                              String correlationId) {
        int existingCount = telemetryTenantRepository.countAnomaliesByTypeForToday(
                schemaName,
                userId,
                schemeId,
                anomalyType
        );
        if (existingCount > 0) {
            log.info("Skipping duplicate image anomaly publish for userId={} schemeId={} anomalyType={} correlationId={}",
                    userId, schemeId, anomalyType, correlationId);
            return;
        }

        telemetryTenantRepository.createTenantAnomalyRecord(
                schemaName,
                userId,
                schemeId,
                anomalyType,
                reason,
                AnomalyConstants.STATUS_OPEN
        );
        telemetryEventPublisher.publishAnomalyRecorded(
                tenantId,
                anomalyType,
                userId,
                schemeId,
                aiReading,
                aiConfidencePercentage,
                overriddenReading,
                retries,
                previousReading,
                previousReadingDate,
                consecutiveDaysMissed,
                reason,
                AnomalyConstants.STATUS_OPEN,
                correlationId
        );
    }

    private record WaterSupplyThreshold(double undersupplyThresholdPercent, double oversupplyThresholdPercent) {
    }
}
