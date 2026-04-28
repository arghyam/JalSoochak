package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class GlificImageWorkflowService {

    private final GlificMediaService glificMediaService;
    private final BfmReadingService bfmReadingService;
    private final TelemetryTenantRepository telemetryTenantRepository;
    private final GlificOperatorContextService operatorContextService;
    private final GlificLocalizationService localizationService;

    public GlificImageWorkflowService(GlificMediaService glificMediaService,
                                      BfmReadingService bfmReadingService,
                                      TelemetryTenantRepository telemetryTenantRepository,
                                      GlificOperatorContextService operatorContextService,
                                      GlificLocalizationService localizationService) {
        this.glificMediaService = glificMediaService;
        this.bfmReadingService = bfmReadingService;
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.operatorContextService = operatorContextService;
        this.localizationService = localizationService;
    }

    public CreateReadingResponse processImage(GlificWebhookRequest glificWebhookRequest) {
        try {
            String contactId = glificWebhookRequest.getContactId();
            String mediaId = glificWebhookRequest.getMediaId();
            String mediaUrl = glificWebhookRequest.getMediaUrl();
            boolean isMeterReplaced = Boolean.TRUE.equals(glificWebhookRequest.getIsMeterReplaced());

            byte[] imageBytes = glificMediaService.downloadImage(mediaId, mediaUrl);
            log.debug("Downloaded image for contactId {} (bytes={})", contactId, imageBytes.length);

            String imageStorageUrl = glificMediaService.uploadImage(contactId, imageBytes);

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(contactId);
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, operatorWithSchema.operator().tenantId())
            );

            Long schemeId = telemetryTenantRepository
                    .findLatestPendingSchemeSelectionForDate(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id(),
                            LocalDate.now()
                    )
                    .map(TelemetrySchemeSelectionRecord::schemeId)
                    .or(() -> telemetryTenantRepository.findFirstSchemeForUser(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id()
                    ))
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));

            CreateReadingRequest createReadingRequest = CreateReadingRequest.builder()
                    .schemeId(schemeId)
                    .operatorId(operatorWithSchema.operator().id())
                    .readingUrl(imageStorageUrl)
                    .readingValue(null)
                    // Record the reason on the flow_reading_table for meter-replacement submissions.
                    .meterChangeReason(isMeterReplaced ? "METER_REPLACED" : null)
                    .readingTime(null)
                    .build();

            CreateReadingResponse response = bfmReadingService.createReading(
                    createReadingRequest,
                    operatorWithSchema.schemaName(),
                    operatorWithSchema.operator(),
                    contactId,
                    isMeterReplaced
            );
            response.setMessage(localizationService.localizeMessage(response.getMessage(), languageKey));
            return response;
        } catch (Exception e) {
            log.error("Unexpected error processing image for contactId {}: {}", glificWebhookRequest.getContactId(), e.getMessage(), e);
            String languageKey = localizationService.resolveLanguageKeyForContact(glificWebhookRequest.getContactId());
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(e, "Image could not be processed.", languageKey);
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .correlationId(glificWebhookRequest.getContactId())
                    .build();
        }
    }

    public CreateReadingResponse processAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
        String safeContactId = request != null ? request.getPhoneNumber() : null;
        try {
            String contactId = safeContactId;
            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(contactId, preferredTenantId);
            Long operatorId = operatorWithSchema.operator().id();
            String schemaName = operatorWithSchema.schemaName();
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, operatorWithSchema.operator().tenantId())
            );
            Long schemeId = resolveAssamSchemeId(schemaName, operatorId, request.getStateSchemeId(), request.getCentreSchemeId());
            LocalDateTime readingTime = request.getReadingDateTime().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            CreateReadingRequest createReadingRequest = CreateReadingRequest.builder()
                    .schemeId(schemeId)
                    .operatorId(operatorId)
                    .readingUrl(request.getReadingUrl())
                    .readingValue(request.getConfirmedReading())
                    .meterChangeReason(null)
                    .readingTime(readingTime)
                    .build();

            CreateReadingResponse response = bfmReadingService.createReading(
                    createReadingRequest,
                    schemaName,
                    operatorWithSchema.operator(),
                    contactId,
                    false
            );

            applyGeolocationIfPresent(request, schemaName, operatorId, response.getCorrelationId());
            response.setMessage(localizationService.localizeMessage(response.getMessage(), languageKey));
            return response;
        } catch (Exception e) {
            log.error("Unexpected error processing Assam reading for contactId {}: {}", safeContactId, e.getMessage(), e);
            String languageKey = localizationService.resolveLanguageKeyForContact(safeContactId);
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(e, "Assam reading could not be processed.", languageKey);
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .correlationId(safeContactId)
                    .build();
        }
    }

    private Long resolveAssamSchemeId(String schemaName, Long operatorId, String stateSchemeId, String centreSchemeId) {
        Optional<Long> stateResolvedSchemeId = telemetryTenantRepository.findSchemeIdByStateSchemeId(schemaName, stateSchemeId);
        if (stateResolvedSchemeId.isPresent()
                && telemetryTenantRepository.isOperatorMappedToScheme(schemaName, operatorId, stateResolvedSchemeId.get())) {
            return stateResolvedSchemeId.get();
        }

        Optional<Long> centreResolvedSchemeId = telemetryTenantRepository.findSchemeIdByCentreSchemeId(schemaName, centreSchemeId);
        if (centreResolvedSchemeId.isPresent()
                && telemetryTenantRepository.isOperatorMappedToScheme(schemaName, operatorId, centreResolvedSchemeId.get())) {
            return centreResolvedSchemeId.get();
        }

        throw new IllegalStateException("Operator is not mapped to the provided state or centre scheme");
    }

    private void applyGeolocationIfPresent(AssamReadingRequest request,
                                           String schemaName,
                                           Long operatorId,
                                           String correlationId) {
        AssamReadingRequest.Geolocation geolocation = request.getGeolocation();
        if (geolocation == null) {
            return;
        }

        validateGeolocation(geolocation);
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }

        Optional<TelemetryReadingRecord> readingOpt = telemetryTenantRepository.findReadingByCorrelationId(schemaName, correlationId);
        if (readingOpt.isEmpty()) {
            return;
        }

        List<BigDecimal> coordinates = geolocation.getCoordinates();
        BigDecimal longitude = coordinates.get(0);
        BigDecimal latitude = coordinates.get(1);
        telemetryTenantRepository.updateReadingLocation(
                schemaName,
                readingOpt.get().id(),
                latitude,
                longitude,
                operatorId
        );
    }

    private void validateGeolocation(AssamReadingRequest.Geolocation geolocation) {
        String type = geolocation.getType();
        if (type != null && !type.isBlank() && !"Point".equalsIgnoreCase(type)) {
            throw new IllegalStateException("geolocation.type must be Point");
        }

        List<BigDecimal> coordinates = geolocation.getCoordinates();
        if (coordinates == null || coordinates.size() != 2) {
            throw new IllegalStateException("geolocation.coordinates must contain [longitude, latitude]");
        }

        BigDecimal longitude = coordinates.get(0);
        BigDecimal latitude = coordinates.get(1);
        if (latitude == null || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalStateException("geolocation latitude must be between -90 and 90");
        }
        if (longitude == null || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalStateException("geolocation longitude must be between -180 and 180");
        }
    }
}
