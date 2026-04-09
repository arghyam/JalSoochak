package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

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
                    .orElseThrow(() -> new IllegalStateException("Please select a scheme first."));

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
}
