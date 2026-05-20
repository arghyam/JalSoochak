package org.arghyam.jalsoochak.telemetry.controller;

import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ResetLatestReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsDataResponse;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/telemetry")
public class SingleTenantTelemetryController {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantTelemetryController.class);

    private final GlificWebhookService glificWebhookService;
    private final TelemetryApiKeyService telemetryApiKeyService;
    private final BfmReadingService bfmReadingService;

    public SingleTenantTelemetryController(GlificWebhookService glificWebhookService,
                                           TelemetryApiKeyService telemetryApiKeyService,
                                           BfmReadingService bfmReadingService) {
        this.glificWebhookService = glificWebhookService;
        this.telemetryApiKeyService = telemetryApiKeyService;
        this.bfmReadingService = bfmReadingService;
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
        try {
            if (telemetryApiKeyService == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key service not configured");
            }
            Integer tenantId = telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));

            CreateReadingResponse response = glificWebhookService.processAssamReading(request, tenantId);
            boolean rejected = response == null
                    || !response.isSuccess()
                    || "REJECTED".equalsIgnoreCase(response.getQualityStatus());
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
            if (telemetryApiKeyService == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key service not configured");
            }
            telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));

            if (request.getPhoneNumber() == null || request.getPhoneNumber().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumber must be provided");
            }

            CreateReadingResponse response = bfmReadingService.resetLatestConfirmedReadingByPhone(request.getPhoneNumber());
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
}
