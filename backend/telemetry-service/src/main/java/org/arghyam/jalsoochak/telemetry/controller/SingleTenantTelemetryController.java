package org.arghyam.jalsoochak.telemetry.controller;

import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ResetLatestReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateYesterdayFinalReadingBySchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsDataResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.UpdateYesterdayFinalReadingBySchemeResponse;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySchemeReadingService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/telemetry")
public class SingleTenantTelemetryController {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantTelemetryController.class);

    private final GlificWebhookService glificWebhookService;
    private final TelemetryApiKeyService telemetryApiKeyService;
    private final BfmReadingService bfmReadingService;
    private final TelemetrySchemeReadingService telemetrySchemeReadingService;
    private final TelemetrySubmissionAuditService telemetrySubmissionAuditService;

    public SingleTenantTelemetryController(GlificWebhookService glificWebhookService,
                                           TelemetryApiKeyService telemetryApiKeyService,
                                           BfmReadingService bfmReadingService) {
        this(glificWebhookService, telemetryApiKeyService, bfmReadingService, null, null);
    }

    @Autowired
    public SingleTenantTelemetryController(GlificWebhookService glificWebhookService,
                                           TelemetryApiKeyService telemetryApiKeyService,
                                           BfmReadingService bfmReadingService,
                                           TelemetrySchemeReadingService telemetrySchemeReadingService,
                                           TelemetrySubmissionAuditService telemetrySubmissionAuditService) {
        this.glificWebhookService = glificWebhookService;
        this.telemetryApiKeyService = telemetryApiKeyService;
        this.bfmReadingService = bfmReadingService;
        this.telemetrySchemeReadingService = telemetrySchemeReadingService;
        this.telemetrySubmissionAuditService = telemetrySubmissionAuditService;
    }

    @PatchMapping(
            value = "/schemes/{schemeId}/yesterday-final-reading",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> updateYesterdayFinalReadingByScheme(
            @PathVariable Long schemeId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody @Valid UpdateYesterdayFinalReadingBySchemeRequest request
    ) {
        String masked = request != null ? request.getPhoneNumber() : null;
        if (masked != null) {
            masked = masked.replaceAll("\\D", "");
            if (masked.length() > 4) {
                masked = "****" + masked.substring(masked.length() - 4);
            } else {
                masked = "****";
            }
        }
        log.info("PATCH /api/v1/telemetry/schemes/{}/yesterday-final-reading phone={}", schemeId, masked);
        try {
            UpdateYesterdayFinalReadingBySchemeResponse response = telemetrySchemeReadingService.updateYesterdayFinalReadingBySchemeId(
                    schemeId,
                    request.getPhoneNumber(),
                    request.getReading(),
                    date
            );
            logReadingSubmission(
                    "/api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading",
                    request != null ? request.getPhoneNumber() : null,
                    schemeId,
                    response != null && response.isSuccess() ? "SUCCESS" : "FAILED",
                    response != null ? response.getMessage() : "Yesterday final reading processed."
            );
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            logReadingSubmission(
                    "/api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading",
                    request != null ? request.getPhoneNumber() : null,
                    schemeId,
                    "FAILED",
                    e.getReason()
            );
            return ResponseEntity.status(e.getStatusCode()).body(
                    UpdateYesterdayFinalReadingBySchemeResponse.builder()
                            .success(false)
                            .schemeId(schemeId)
                            .finalReading(request != null ? request.getReading() : null)
                            .message(e.getReason())
                            .build()
            );
        } catch (Exception e) {
            logReadingSubmission(
                    "/api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading",
                    request != null ? request.getPhoneNumber() : null,
                    schemeId,
                    "FAILED",
                    e.getMessage()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    UpdateYesterdayFinalReadingBySchemeResponse.builder()
                            .success(false)
                            .schemeId(schemeId)
                            .finalReading(request != null ? request.getReading() : null)
                            .message("Internal error")
                            .build()
            );
        }
    }

    @PostMapping(
            value = "/readings",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingsApiResponse> receiveAssamReading(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody @Valid AssamReadingRequest request
    ) {
        Integer tenantId = null;
        try {
            if (telemetryApiKeyService == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key service not configured");
            }
            tenantId = telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));

            CreateReadingResponse response = glificWebhookService.processAssamReading(request, tenantId);
            boolean rejected = response == null
                    || !response.isSuccess()
                    || "REJECTED".equalsIgnoreCase(response.getQualityStatus());
            logReadingSubmission(
                    "/api/v1/telemetry/readings",
                    request,
                    tenantId,
                    rejected ? "FAILED" : "SUCCESS",
                    response != null ? response.getMessage() : "Reading processed."
            );
            if (rejected) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                        ReadingsApiResponse.builder()
                                .success(false)
                                .data(toReadingsDataResponse(response, false))
                                .build()
                );
            }
            return ResponseEntity.ok(
                    ReadingsApiResponse.builder()
                            .success(true)
                            .data(toReadingsDataResponse(response, true))
                            .build()
            );
        } catch (ResponseStatusException e) {
            logReadingSubmission(
                    "/api/v1/telemetry/readings",
                    request,
                    tenantId,
                    "FAILED",
                    e.getReason()
            );
            return ResponseEntity.status(e.getStatusCode()).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .message(e.getReason())
                                    .qualityStatus("REJECTED")
                                    .build())
                            .build()
            );
        } catch (Exception e) {
            String safeContactId = request != null ? request.getPhoneNumber() : null;
            log.error("Error processing Assam reading: {}", e.getMessage(), e);
            log.debug("Error processing Assam reading for phoneNumber {}: {}", safeContactId, e.getMessage());
            logReadingSubmission(
                    "/api/v1/telemetry/readings",
                    request,
                    tenantId,
                    "FAILED",
                    e.getMessage()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .qualityStatus("REJECTED")
                                    .message("Failed to process reading")
                                    .build())
                            .build()
            );
        }
    }

    @PutMapping(
            value = "/readings",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingsApiResponse> updateReading(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody @Valid UpdateReadingRequest request
    ) {
        try {
            if (telemetryApiKeyService == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key service not configured");
            }
            telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));

            boolean hasPhoneNumber = request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank();
            boolean hasCorrelationId = request.getCorrelationId() != null && !request.getCorrelationId().isBlank();
            boolean hasImageId = request.getImageId() != null && !request.getImageId().isBlank();
            boolean hasConfirmedReading = request.getConfirmedReading() != null;

            if (!hasCorrelationId) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "correlationId must be provided"
                );
            }

            if (!hasPhoneNumber) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "phoneNumber must be provided"
                );
            }

            if (request.getConfirmedReading() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "confirmedReading must be provided for update"
                );
            }

            String resolvedIdentifier = request.getCorrelationId();
            if (hasConfirmedReading && hasImageId) {
                log.debug("Both confirmedReading and imageId provided; confirmedReading update takes precedence.");
            }
            if (resolvedIdentifier == null || resolvedIdentifier.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "phoneNumber must be provided"
                );
            }
            CreateReadingResponse response = bfmReadingService.updateConfirmedReading(
                    resolvedIdentifier,
                    request.getConfirmedReading()
            );
            return ResponseEntity.ok(
                    ReadingsApiResponse.builder()
                            .success(true)
                            .data(toReadingsDataResponse(response, true))
                            .build()
            );
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .message(e.getReason())
                                    .qualityStatus("REJECTED")
                                    .build())
                            .build()
            );
        } catch (Exception e) {
            log.error("Error updating reading: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .qualityStatus("REJECTED")
                                    .message("Failed to update reading")
                                    .build())
                            .build()
            );
        }
    }

    @PostMapping(
            value = "/readings/reset-latest",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingsApiResponse> resetLatestReading(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody @Valid ResetLatestReadingRequest request
    ) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contactId must be provided");
            }

            CreateReadingResponse response = bfmReadingService.resetLatestConfirmedReadingByPhone(request.getContactId());
            return ResponseEntity.ok(
                    ReadingsApiResponse.builder()
                            .success(true)
                            .data(toReadingsDataResponse(response, true))
                            .build()
            );
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .message(e.getReason())
                                    .qualityStatus("REJECTED")
                                    .build())
                            .build()
            );
        } catch (Exception e) {
            log.error("Error resetting latest reading: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .qualityStatus("REJECTED")
                                    .message("Failed to reset latest reading")
                                    .build())
                            .build()
            );
        }
    }

    private ReadingsDataResponse toReadingsDataResponse(CreateReadingResponse response, boolean includeCorrelationId) {
        if (response == null) {
            return ReadingsDataResponse.builder()
                    .qualityStatus("REJECTED")
                    .message("Failed to process reading")
                    .build();
        }
        return ReadingsDataResponse.builder()
                .correlationId(includeCorrelationId ? response.getCorrelationId() : null)
                .meterReading(response.getMeterReading())
                .qualityStatus(response.getQualityStatus())
                .qualityConfidence(response.getQualityConfidence())
                .lastConfirmedReading(response.getLastConfirmedReading())
                .message(response.getMessage())
                .build();
    }

    private void logReadingSubmission(String api, String phoneNumber, Long schemeId, String status, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit =
                telemetrySubmissionAuditService != null
                        ? telemetrySubmissionAuditService.captureForPhoneAndScheme(phoneNumber, schemeId)
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", schemeId, 0, LocalDate.now());

        log.info(
                "reading_submission api={} status={} phone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                api,
                status,
                audit.maskedPhone(),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
    }

    private void logReadingSubmission(String api, AssamReadingRequest request, Integer tenantId, String status, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit =
                telemetrySubmissionAuditService != null
                        ? telemetrySubmissionAuditService.captureForAssamReading(request, tenantId)
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, LocalDate.now());

        log.info(
                "reading_submission api={} status={} phone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                api,
                status,
                audit.maskedPhone(),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "n/a";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
