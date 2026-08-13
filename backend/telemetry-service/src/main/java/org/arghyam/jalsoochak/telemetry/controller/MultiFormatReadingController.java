package org.arghyam.jalsoochak.telemetry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsDataResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapperRegistry;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pluggable ingestion endpoint for state IT systems whose reading payload does not match the
 * canonical contract served by {@code SingleTenantTelemetryController#receiveAssamReading}.
 *
 * <p>{@code POST /api/v1/telemetry/readings/formats/{format}} accepts the raw JSON body, selects the
 * matching {@link ReadingRequestMapper} (via {@link ReadingRequestMapperRegistry}), maps it to the
 * canonical {@link AssamReadingRequest}, applies the same bean-validation constraints, then runs the
 * identical processing path as the canonical endpoint. Adding a new state format therefore needs only
 * a new mapper bean — no change to this controller or the core pipeline.
 *
 * <p>Deliberately a separate controller so the canonical endpoint and its {@code @RestControllerAdvice}
 * are left untouched.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class MultiFormatReadingController {

    private static final Logger log = LoggerFactory.getLogger(MultiFormatReadingController.class);

    private final ReadingRequestMapperRegistry mapperRegistry;
    private final TelemetryApiKeyService telemetryApiKeyService;
    private final GlificWebhookService glificWebhookService;
    private final Validator validator;

    public MultiFormatReadingController(ReadingRequestMapperRegistry mapperRegistry,
                                        TelemetryApiKeyService telemetryApiKeyService,
                                        GlificWebhookService glificWebhookService,
                                        Validator validator) {
        this.mapperRegistry = mapperRegistry;
        this.telemetryApiKeyService = telemetryApiKeyService;
        this.glificWebhookService = glificWebhookService;
        this.validator = validator;
    }

    @PostMapping(
            value = "/readings/formats/{format}",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingsApiResponse> receiveReadingInFormat(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @PathVariable String format,
            @RequestBody JsonNode rawBody
    ) {
        String safeFormat = sanitize(format);
        log.info("POST /api/v1/telemetry/readings/formats/{} received", safeFormat);

        if (!mapperRegistry.supports(format)) {
            log.info("POST /api/v1/telemetry/readings/formats/{} rejected reason=\"unsupported format\"", safeFormat);
            return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.BAD_REQUEST,
                    "Unsupported reading format: " + safeFormat);
        }
        ReadingRequestMapper mapper = mapperRegistry.resolve(format);

        Integer tenantId = telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey).orElse(null);
        if (tenantId == null) {
            log.info("POST /api/v1/telemetry/readings/formats/{} rejected reason=\"invalid api key\"", safeFormat);
            return reject(HttpStatus.UNAUTHORIZED, TelemetryErrorCode.INVALID_API_KEY, "Invalid API key");
        }

        AssamReadingRequest request;
        try {
            request = mapper.map(rawBody);
        } catch (Exception e) {
            log.info("POST /api/v1/telemetry/readings/formats/{} rejected tenantId={} reason=\"mapping failed\" error=\"{}\"",
                    safeFormat, tenantId, sanitize(e.getMessage()));
            return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.MALFORMED_REQUEST,
                    "Could not parse reading payload for format '" + safeFormat + "'");
        }
        if (request == null) {
            return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.MALFORMED_REQUEST,
                    "Mapper produced no reading for format '" + safeFormat + "'");
        }

        Set<ConstraintViolation<AssamReadingRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .filter(m -> m != null && !m.isBlank())
                    .sorted()
                    .collect(Collectors.joining("; "));
            log.info("POST /api/v1/telemetry/readings/formats/{} rejected tenantId={} reason=\"validation\" fields=\"{}\"",
                    safeFormat, tenantId, sanitize(message));
            return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.VALIDATION_FAILED,
                    message.isBlank() ? "Validation failed" : message);
        }

        try {
            CreateReadingResponse response = glificWebhookService.processAssamReading(request, tenantId);
            boolean retry = response != null
                    && !response.isSuccess()
                    && "RETRY".equalsIgnoreCase(response.getQualityStatus());
            boolean rejected = response == null
                    || !response.isSuccess()
                    || "REJECTED".equalsIgnoreCase(response.getQualityStatus());
            log.info("POST /api/v1/telemetry/readings/formats/{} processed tenantId={} status={}",
                    safeFormat, tenantId, rejected ? "FAILED" : "SUCCESS");
            if (retry) {
                // Mirror the canonical endpoint: a transient OCR outage is a retryable server-side
                // condition, surfaced as 503 rather than a client 400.
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(body(false, toReadingsDataResponse(response, false)));
            }
            if (rejected) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(body(false, toReadingsDataResponse(response, false)));
            }
            return ResponseEntity.ok(body(true, toReadingsDataResponse(response, true)));
        } catch (Exception e) {
            log.error("Error processing reading (format={}): {}", safeFormat, e.getMessage(), e);
            return reject(HttpStatus.INTERNAL_SERVER_ERROR, TelemetryErrorCode.PROCESSING_FAILED,
                    "Failed to process reading");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ReadingsApiResponse> handleUnreadableJson(HttpMessageNotReadableException ex) {
        log.info("POST /api/v1/telemetry/readings/formats rejected reason=\"malformed body\" error=\"{}\"",
                sanitize(ex.getMessage()));
        return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.MALFORMED_REQUEST, "Malformed request body");
    }

    private ResponseEntity<ReadingsApiResponse> reject(HttpStatus status, TelemetryErrorCode code, String message) {
        return ResponseEntity.status(status).body(
                ReadingsApiResponse.builder()
                        .success(false)
                        .data(ReadingsDataResponse.builder()
                                .qualityStatus("REJECTED")
                                .errorCode(code)
                                .message(message)
                                .build())
                        .build()
        );
    }

    private ReadingsApiResponse body(boolean success, ReadingsDataResponse data) {
        return ReadingsApiResponse.builder().success(success).data(data).build();
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

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
