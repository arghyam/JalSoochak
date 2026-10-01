package org.arghyam.jalsoochak.telemetry.controller.internal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.config.OpenApiConfig;
import org.arghyam.jalsoochak.telemetry.config.TenantInterceptor;
import org.arghyam.jalsoochak.telemetry.dto.requests.RepublishReadingsRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.RepublishReadingsResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.ReadingBackfillService;
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
 * {@code POST /api/v1/telemetry/internal/readings/republish}: re-sends a tenant's stored ELM and PDU
 * readings to analytics, for a tenant or scheme whose formula or pump data was set up after its
 * readings came in. See {@link ReadingBackfillService}.
 *
 * <p>An operations route, not a partner one: {@code InternalAuthFilter} authenticates it with the
 * operations token, and the tenant is named by {@code X-Tenant-Code}. It answers every failure past
 * that filter in its own envelope.
 */
@RestController
@RequestMapping("/api/v1/telemetry/internal")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
public class ReadingBackfillController {

    private static final Logger log = LoggerFactory.getLogger(ReadingBackfillController.class);
    private static final String API = "/api/v1/telemetry/internal/readings/republish";
    static final String NOT_SENT_MESSAGE =
            "Analytics stopped accepting readings part-way, so the rest were not sent. Send the same range again.";

    private final ReadingBackfillService readingBackfillService;

    public ReadingBackfillController(ReadingBackfillService readingBackfillService) {
        this.readingBackfillService = readingBackfillService;
    }

    @PostMapping(value = "/readings/republish", consumes = "application/json", produces = "application/json")
    public ResponseEntity<RepublishReadingsResponse> republishReadings(
            @RequestHeader(value = TenantInterceptor.TENANT_HEADER, required = false) String tenantHeader,
            @RequestBody @Valid RepublishReadingsRequest request
    ) {
        String tenantCode = tenantHeader == null ? "" : tenantHeader.trim();
        if (tenantCode.isEmpty()) {
            log.info("{} rejected reason=\"missing tenant code\"", API);
            return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.VALIDATION_FAILED,
                    TenantInterceptor.TENANT_HEADER + " must be provided");
        }

        Optional<ReadingChannel> channel = Optional.empty();
        if (ReadingChannel.isDeclared(request.getChannel())) {
            channel = ReadingChannel.parseStrict(request.getChannel())
                    .filter(ReadingBackfillService.REPUBLISHABLE_CHANNELS::contains);
            if (channel.isEmpty()) {
                log.info("{} rejected tenantCode={} reason=\"unsupported channel\"", API, tenantCode);
                return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.CHANNEL_NOT_SUPPORTED,
                        "Unsupported channel. Allowed values are: ELM, PDU");
            }
        }

        try {
            Optional<Integer> tenantId = readingBackfillService.findTenantId(tenantCode);
            if (tenantId.isEmpty()) {
                log.info("{} rejected tenantCode={} reason=\"unknown tenant\"", API, tenantCode);
                return reject(HttpStatus.NOT_FOUND, TelemetryErrorCode.TENANT_NOT_FOUND, "Tenant not found");
            }
            ReadingBackfillService.Outcome outcome = readingBackfillService.republish(
                    tenantId.get(),
                    request.getFromDate(),
                    request.getToDate(),
                    request.getStateSchemeId(),
                    request.getCentreSchemeId(),
                    channel.orElse(null));
            if (outcome.notSentCount() > 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(RepublishReadingsResponse.stopped(
                        outcome.republishedCount(), outcome.withheldCount(), outcome.notSentCount(),
                        TelemetryErrorCode.PROCESSING_FAILED, NOT_SENT_MESSAGE));
            }
            return ResponseEntity.ok(RepublishReadingsResponse.republished(
                    outcome.republishedCount(), outcome.withheldCount()));
        } catch (ResponseStatusException e) {
            log.info("{} rejected tenantCode={} httpStatus={} reason=\"{}\" stateSchemeId={} centreSchemeId={}",
                    API, tenantCode, e.getStatusCode(), sanitize(e.getReason()),
                    sanitize(request.getStateSchemeId()), sanitize(request.getCentreSchemeId()));
            return ResponseEntity.status(e.getStatusCode()).body(RepublishReadingsResponse.rejected(
                    HttpStatus.NOT_FOUND.equals(e.getStatusCode())
                            ? TelemetryErrorCode.SCHEME_NOT_FOUND
                            : TelemetryErrorCode.REQUEST_FAILED,
                    e.getReason()));
        } catch (Exception e) {
            log.error("{} failed tenantCode={}: {}", API, tenantCode, e.getMessage(), e);
            return reject(HttpStatus.INTERNAL_SERVER_ERROR, TelemetryErrorCode.PROCESSING_FAILED,
                    "Failed to republish readings");
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RepublishReadingsResponse> handleValidation(MethodArgumentNotValidException ex) {
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
    public ResponseEntity<RepublishReadingsResponse> handleUnreadableJson(HttpMessageNotReadableException ex) {
        log.info("{} rejected reason=\"malformed body\" error=\"{}\"", API, sanitize(ex.getMessage()));
        return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.MALFORMED_REQUEST, "Malformed request body");
    }

    private static ResponseEntity<RepublishReadingsResponse> reject(HttpStatus status,
                                                                    TelemetryErrorCode errorCode,
                                                                    String message) {
        return ResponseEntity.status(status).body(RepublishReadingsResponse.rejected(errorCode, message));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
