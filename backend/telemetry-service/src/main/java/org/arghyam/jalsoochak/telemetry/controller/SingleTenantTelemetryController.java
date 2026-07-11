package org.arghyam.jalsoochak.telemetry.controller;

import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ResetLatestReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateYesterdayFinalReadingBySchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsDataResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
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
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;

@RestController
@RequestMapping("/api/v1/telemetry")
public class SingleTenantTelemetryController {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantTelemetryController.class);
    private static final String TENANT_CODE_HEADER = "X-Tenant-Code";
    private static final String API_KEY_TOKEN = "api key";

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
            value = {"/readings", "/readings/"},
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingsApiResponse> receiveAssamReading(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = TENANT_CODE_HEADER, required = false) String tenantCode,
            @RequestBody @Valid AssamReadingRequest request
    ) {
        log.info("POST /api/v1/telemetry/readings received request={}", summarizeAssamReadingRequest(request));
        Integer tenantId = null;
        try {
            if (telemetryApiKeyService == null) {
                log.info("POST /api/v1/telemetry/readings rejected reason=\"API key service not configured\" request={}",
                        summarizeAssamReadingRequest(request));
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key service not configured");
            }
            log.info("POST /api/v1/telemetry/readings resolving API key present={} request={}",
                    apiKey != null && !apiKey.isBlank(),
                    summarizeAssamReadingRequest(request));
            tenantId = telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
            log.info("POST /api/v1/telemetry/readings API key accepted tenantId={} request={}",
                    tenantId,
                    summarizeAssamReadingRequest(request));

            log.info("POST /api/v1/telemetry/readings processing tenantId={} request={}",
                    tenantId,
                    summarizeAssamReadingRequest(request));
            CreateReadingResponse response = glificWebhookService.processAssamReading(request, tenantId);
            boolean rejected = response == null
                    || !response.isSuccess()
                    || "REJECTED".equalsIgnoreCase(response.getQualityStatus());
            log.info("POST /api/v1/telemetry/readings processed tenantId={} status={} response={}",
                    tenantId,
                    rejected ? "FAILED" : "SUCCESS",
                    summarizeCreateReadingResponse(response));
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
            log.info("POST /api/v1/telemetry/readings rejected tenantId={} httpStatus={} reason=\"{}\" request={}",
                    tenantId,
                    e.getStatusCode(),
                    sanitizeLogMessage(e.getReason()),
                    summarizeAssamReadingRequest(request));
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
                                    .errorCode(errorCodeForStatusException(e))
                                    .build())
                            .build()
            );
        } catch (Exception e) {
            String safeContactId = request != null ? request.getPhoneNumber() : null;
            log.error("Error processing Assam reading: {}", e.getMessage(), e);
            log.debug("Error processing Assam reading for phoneNumber {}: {}", safeContactId, e.getMessage());
            log.info("POST /api/v1/telemetry/readings failed tenantId={} reason=\"{}\" request={}",
                    tenantId,
                    sanitizeLogMessage(e.getMessage()),
                    summarizeAssamReadingRequest(request));
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
                                    .errorCode(TelemetryErrorCode.PROCESSING_FAILED)
                                    .message("Failed to process reading")
                                    .build())
                            .build()
            );
        }
    }

    @PutMapping(
            value = {"/readings", "/readings/"},
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingsApiResponse> updateReading(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = TENANT_CODE_HEADER, required = false) String tenantCode,
            @RequestBody @Valid UpdateReadingRequest request
    ) {
        log.info("PUT /api/v1/telemetry/readings received request={}", summarizeUpdateReadingRequest(request));
        Integer tenantId = null;
        try {
            if (telemetryApiKeyService == null) {
                log.info("PUT /api/v1/telemetry/readings rejected reason=\"API key service not configured\" request={}",
                        summarizeUpdateReadingRequest(request));
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key service not configured");
            }
            log.info("PUT /api/v1/telemetry/readings resolving API key present={} request={}",
                    apiKey != null && !apiKey.isBlank(),
                    summarizeUpdateReadingRequest(request));
            tenantId = telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
            log.info("PUT /api/v1/telemetry/readings API key accepted tenantId={} request={}",
                    tenantId,
                    summarizeUpdateReadingRequest(request));

            boolean hasPhoneNumber = request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank();
            boolean hasCorrelationId = request.getCorrelationId() != null && !request.getCorrelationId().isBlank();
            boolean hasImageId = request.getImageId() != null && !request.getImageId().isBlank();
            boolean hasConfirmedReading = request.getConfirmedReading() != null;
            log.info("PUT /api/v1/telemetry/readings validating fields tenantId={} hasPhoneNumber={} hasCorrelationId={} hasImageId={} hasConfirmedReading={}",
                    tenantId,
                    hasPhoneNumber,
                    hasCorrelationId,
                    hasImageId,
                    hasConfirmedReading);

            if (!hasPhoneNumber) {
                log.info("PUT /api/v1/telemetry/readings rejected tenantId={} reason=\"phoneNumber must be provided\" request={}",
                        tenantId,
                        summarizeUpdateReadingRequest(request));
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "phoneNumber must be provided"
                );
            }

            if (request.getConfirmedReading() == null) {
                log.info("PUT /api/v1/telemetry/readings rejected tenantId={} reason=\"confirmedReading must be provided for update\" request={}",
                        tenantId,
                        summarizeUpdateReadingRequest(request));
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "confirmedReading must be provided for update"
                );
            }

            if (hasConfirmedReading && hasImageId) {
                log.info("PUT /api/v1/telemetry/readings confirmedReading and imageId both provided; confirmedReading update takes precedence tenantId={} request={}",
                        tenantId,
                        summarizeUpdateReadingRequest(request));
            }
            log.info("PUT /api/v1/telemetry/readings updating confirmed reading tenantId={} request={}",
                    tenantId,
                    summarizeUpdateReadingRequest(request));
            CreateReadingResponse response = bfmReadingService.updateConfirmedReading(
                    hasCorrelationId ? request.getCorrelationId().trim() : null,
                    request.getPhoneNumber().trim(),
                    request.getConfirmedReading()
            );
            log.info("PUT /api/v1/telemetry/readings updated tenantId={} response={}",
                    tenantId,
                    summarizeCreateReadingResponse(response));
            logReadingSubmission(
                    "/api/v1/telemetry/readings",
                    request,
                    tenantId,
                    "SUCCESS",
                    response != null ? response.getMessage() : "Reading updated."
            );
            return ResponseEntity.ok(
                    ReadingsApiResponse.builder()
                            .success(true)
                            .data(toReadingsDataResponse(response, true))
                            .build()
            );
        } catch (ResponseStatusException e) {
            log.info("PUT /api/v1/telemetry/readings rejected tenantId={} httpStatus={} reason=\"{}\" request={}",
                    tenantId,
                    e.getStatusCode(),
                    sanitizeLogMessage(e.getReason()),
                    summarizeUpdateReadingRequest(request));
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
            log.error("Error updating reading: {}", e.getMessage(), e);
            log.info("PUT /api/v1/telemetry/readings failed tenantId={} reason=\"{}\" request={}",
                    tenantId,
                    sanitizeLogMessage(e.getMessage()),
                    summarizeUpdateReadingRequest(request));
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
                .errorCode(response.getErrorCode())
                .message(response.getMessage())
                .build();
    }

    private TelemetryErrorCode errorCodeForStatusException(ResponseStatusException e) {
        if (e == null) {
            return TelemetryErrorCode.REQUEST_FAILED;
        }
        if (HttpStatus.UNAUTHORIZED.equals(e.getStatusCode())) {
            return TelemetryErrorCode.INVALID_API_KEY;
        }
        if (HttpStatus.INTERNAL_SERVER_ERROR.equals(e.getStatusCode())) {
            return TelemetryErrorCode.SERVER_ERROR;
        }
        String reason = e.getReason();
        if (reason == null || reason.isBlank()) {
            return TelemetryErrorCode.REQUEST_FAILED;
        }
        String normalized = reason.toLowerCase();
        if (normalized.contains(API_KEY_TOKEN)) {
            return TelemetryErrorCode.INVALID_API_KEY;
        }
        return TelemetryErrorCode.BAD_REQUEST;
    }

    private void logReadingSubmission(String api, String phoneNumber, Long schemeId, String status, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit =
                telemetrySubmissionAuditService != null
                        ? telemetrySubmissionAuditService.captureForPhoneAndScheme(phoneNumber, schemeId)
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", schemeId, 0, LocalDate.now(ReadingTime.ZONE));

        log.info(
                "reading_submission api={} status={} phone={} submittedPhone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                api,
                status,
                audit.maskedPhone(),
                maskPhone(phoneNumber),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
        logRawSubmissionPhoneAtDebug(api, status, phoneNumber, audit);
    }

    private void logReadingSubmission(String api, AssamReadingRequest request, Integer tenantId, String status, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit =
                telemetrySubmissionAuditService != null
                        ? telemetrySubmissionAuditService.captureForAssamReading(request, tenantId)
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, LocalDate.now(ReadingTime.ZONE));

        String submittedPhone = request != null ? request.getPhoneNumber() : null;
        log.info(
                "reading_submission api={} status={} phone={} submittedPhone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                api,
                status,
                audit.maskedPhone(),
                maskPhone(submittedPhone),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
        logRawSubmissionPhoneAtDebug(api, status, submittedPhone, audit);
    }

    private void logReadingSubmission(String api, UpdateReadingRequest request, Integer tenantId, String status, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit =
                telemetrySubmissionAuditService != null
                        ? telemetrySubmissionAuditService.captureForPhoneAndScheme(
                        request != null ? request.getPhoneNumber() : null,
                        null
                )
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, LocalDate.now(ReadingTime.ZONE));

        String submittedPhone = request != null ? request.getPhoneNumber() : null;
        log.info(
                "reading_submission api={} status={} phone={} submittedPhone={} schemeId={} tenantId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                api,
                status,
                audit.maskedPhone(),
                maskPhone(submittedPhone),
                audit.schemeId(),
                tenantId,
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
        logRawSubmissionPhoneAtDebug(api, status, submittedPhone, audit);
    }

    private void logRawSubmissionPhoneAtDebug(String api,
                                              String status,
                                              String phoneNumber,
                                              TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit) {
        // Raw phone numbers are PII and must never appear in INFO/WARN/ERROR logs. Expose them only at
        // DEBUG so an operator can correlate a masked rejection back to the actual number while debugging.
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "reading_submission_detail api={} status={} rawPhone={} schemeId={} date={}",
                api,
                status,
                sanitizeLogValue(phoneNumber),
                audit.schemeId(),
                audit.date()
        );
    }

    private String summarizeAssamReadingRequest(AssamReadingRequest request) {
        if (request == null) {
            return "null";
        }
        return String.format(
                "{phone=%s,stateSchemeId=%s,centreSchemeId=%s,hasReadingUrl=%s,confirmedReading=%s,readingDateTime=%s,hasGeolocation=%s}",
                maskPhone(request.getPhoneNumber()),
                sanitizeLogValue(request.getStateSchemeId()),
                sanitizeLogValue(request.getCentreSchemeId()),
                request.getReadingUrl() != null && !request.getReadingUrl().isBlank(),
                request.getConfirmedReading(),
                request.getReadingDateTime(),
                request.getGeolocation() != null
        );
    }

    private String summarizeUpdateReadingRequest(UpdateReadingRequest request) {
        if (request == null) {
            return "null";
        }
        return String.format(
                "{phone=%s,correlationId=%s,hasImageId=%s,confirmedReading=%s}",
                maskPhone(request.getPhoneNumber()),
                sanitizeLogValue(request.getCorrelationId()),
                request.getImageId() != null && !request.getImageId().isBlank(),
                request.getConfirmedReading()
        );
    }

    private String summarizeCreateReadingResponse(CreateReadingResponse response) {
        if (response == null) {
            return "null";
        }
        return String.format(
                "{success=%s,qualityStatus=%s,correlationId=%s,meterReading=%s,lastConfirmedReading=%s,message=\"%s\"}",
                response.isSuccess(),
                sanitizeLogValue(response.getQualityStatus()),
                sanitizeLogValue(response.getCorrelationId()),
                response.getMeterReading(),
                response.getLastConfirmedReading(),
                sanitizeLogMessage(response.getMessage())
        );
    }

    private String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return "n/a";
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "n/a";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
