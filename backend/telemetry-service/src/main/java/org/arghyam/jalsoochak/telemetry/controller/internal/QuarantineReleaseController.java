package org.arghyam.jalsoochak.telemetry.controller.internal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.config.OpenApiConfig;
import org.arghyam.jalsoochak.telemetry.config.TenantInterceptor;
import org.arghyam.jalsoochak.telemetry.dto.requests.ReleaseQuarantinedReadingsRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ReleaseQuarantinedReadingsResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.QuarantinedReadingReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code POST /api/v1/telemetry/internal/readings/quarantine/release}: assesses a tenant's quarantined
 * readings again and releases the ones the implausible-supply check now accepts. See
 * {@link QuarantinedReadingReleaseService}.
 *
 * <p>An operations route, not a partner one: {@code InternalAuthFilter} authenticates it with the
 * operations token, and the tenant is named by {@code X-Tenant-Code}. It answers every failure past
 * that filter in its own envelope.
 */
@RestController
@RequestMapping("/api/v1/telemetry/internal")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
public class QuarantineReleaseController {

    private static final Logger log = LoggerFactory.getLogger(QuarantineReleaseController.class);
    private static final String API = "/api/v1/telemetry/internal/readings/quarantine/release";
    static final String NOT_SENT_MESSAGE =
            "Analytics stopped accepting readings part-way, so the rest were left quarantined. "
                    + "Release the same range again.";

    private final QuarantinedReadingReleaseService releaseService;

    public QuarantineReleaseController(QuarantinedReadingReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping(value = "/readings/quarantine/release", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ReleaseQuarantinedReadingsResponse> releaseQuarantinedReadings(
            @RequestHeader(value = TenantInterceptor.TENANT_HEADER, required = false) String tenantHeader,
            @RequestBody @Valid ReleaseQuarantinedReadingsRequest request
    ) {
        String tenantCode = tenantHeader == null ? "" : tenantHeader.trim();
        if (tenantCode.isEmpty()) {
            log.info("{} rejected reason=\"missing tenant code\"", API);
            return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.VALIDATION_FAILED,
                    TenantInterceptor.TENANT_HEADER + " must be provided");
        }

        try {
            Optional<Integer> tenantId = releaseService.findTenantId(tenantCode);
            if (tenantId.isEmpty()) {
                log.info("{} rejected tenantCode={} reason=\"unknown tenant\"", API, tenantCode);
                return reject(HttpStatus.NOT_FOUND, TelemetryErrorCode.TENANT_NOT_FOUND, "Tenant not found");
            }
            QuarantinedReadingReleaseService.Outcome outcome = releaseService.release(
                    tenantId.get(),
                    request.getFromDate(),
                    request.getToDate(),
                    request.getStateSchemeId(),
                    request.getCentreSchemeId(),
                    request.isDryRun());
            if (outcome.notSentCount() > 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ReleaseQuarantinedReadingsResponse.stopped(
                        outcome.releasedCount(), outcome.stillQuarantinedCount(), outcome.notSentCount(),
                        TelemetryErrorCode.PROCESSING_FAILED, NOT_SENT_MESSAGE));
            }
            return ResponseEntity.ok(ReleaseQuarantinedReadingsResponse.released(
                    request.isDryRun(), outcome.releasedCount(), outcome.stillQuarantinedCount()));
        } catch (ResponseStatusException e) {
            log.info("{} rejected tenantCode={} httpStatus={} reason=\"{}\" stateSchemeId={} centreSchemeId={}",
                    API, tenantCode, e.getStatusCode(), sanitize(e.getReason()),
                    sanitize(request.getStateSchemeId()), sanitize(request.getCentreSchemeId()));
            return ResponseEntity.status(e.getStatusCode()).body(ReleaseQuarantinedReadingsResponse.rejected(
                    HttpStatus.NOT_FOUND.equals(e.getStatusCode())
                            ? TelemetryErrorCode.SCHEME_NOT_FOUND
                            : TelemetryErrorCode.REQUEST_FAILED,
                    e.getReason()));
        } catch (Exception e) {
            log.error("{} failed tenantCode={}: {}", API, tenantCode, e.getMessage(), e);
            return reject(HttpStatus.INTERNAL_SERVER_ERROR, TelemetryErrorCode.PROCESSING_FAILED,
                    "Failed to release quarantined readings");
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ReleaseQuarantinedReadingsResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .collect(Collectors.joining("; "));
        log.info("{} rejected reason=\"validation\" message=\"{}\"", API, sanitize(message));
        return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.VALIDATION_FAILED,
                message.isBlank() ? "Validation failed" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ReleaseQuarantinedReadingsResponse> handleUnreadableJson(HttpMessageNotReadableException ex) {
        log.info("{} rejected reason=\"malformed body\" error=\"{}\"", API, sanitize(ex.getMessage()));
        return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.MALFORMED_REQUEST, "Malformed request body");
    }

    private static ResponseEntity<ReleaseQuarantinedReadingsResponse> reject(HttpStatus status,
                                                                             TelemetryErrorCode errorCode,
                                                                             String message) {
        return ResponseEntity.status(status).body(ReleaseQuarantinedReadingsResponse.rejected(errorCode, message));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
