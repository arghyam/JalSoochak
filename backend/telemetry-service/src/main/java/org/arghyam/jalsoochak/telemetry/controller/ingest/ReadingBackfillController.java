package org.arghyam.jalsoochak.telemetry.controller.ingest;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.config.OpenApiConfig;
import org.arghyam.jalsoochak.telemetry.config.TelemetryApiKeyAuthFilter;
import org.arghyam.jalsoochak.telemetry.dto.requests.RepublishReadingsRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.RepublishReadingsResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.ReadingBackfillService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code POST /api/v1/telemetry/readings/republish}: re-sends a tenant's stored ELM and PDU readings
 * to analytics, for a tenant or scheme whose formula or pump data was set up after its readings came
 * in. See {@link ReadingBackfillService}.
 *
 * <p>Its own controller, like {@link MultiFormatReadingController}, so the canonical ingestion
 * endpoint and its advice are left untouched. It answers every failure in its own envelope.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
@SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
public class ReadingBackfillController {

    private static final Logger log = LoggerFactory.getLogger(ReadingBackfillController.class);
    private static final String API = "/api/v1/telemetry/readings/republish";

    private final ReadingBackfillService readingBackfillService;
    private final TelemetryApiKeyService telemetryApiKeyService;

    public ReadingBackfillController(ReadingBackfillService readingBackfillService,
                                     TelemetryApiKeyService telemetryApiKeyService) {
        this.readingBackfillService = readingBackfillService;
        this.telemetryApiKeyService = telemetryApiKeyService;
    }

    @PostMapping(value = "/readings/republish", consumes = "application/json", produces = "application/json")
    public ResponseEntity<RepublishReadingsResponse> republishReadings(
            @RequestHeader(value = TelemetryApiKeyAuthFilter.API_KEY_HEADER, required = false) String apiKey,
            @RequestAttribute(name = TelemetryApiKeyAuthFilter.TENANT_ID_ATTRIBUTE, required = false)
            Integer authenticatedTenantId,
            @RequestBody @Valid RepublishReadingsRequest request
    ) {
        // The filter has already refused an unauthenticated request; resolved here again as defence
        // in depth, so the handler is safe if it is ever reached without the filter.
        Integer tenantId = authenticatedTenantId != null
                ? authenticatedTenantId
                : telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey).orElse(null);
        if (tenantId == null) {
            log.info("{} rejected reason=\"invalid api key\"", API);
            return reject(HttpStatus.UNAUTHORIZED, TelemetryErrorCode.INVALID_API_KEY, "Invalid API key");
        }

        Optional<ReadingChannel> channel = Optional.empty();
        if (ReadingChannel.isDeclared(request.getChannel())) {
            channel = ReadingChannel.parseStrict(request.getChannel())
                    .filter(ReadingBackfillService.REPUBLISHABLE_CHANNELS::contains);
            if (channel.isEmpty()) {
                log.info("{} rejected tenantId={} reason=\"unsupported channel\"", API, tenantId);
                return reject(HttpStatus.BAD_REQUEST, TelemetryErrorCode.CHANNEL_NOT_SUPPORTED,
                        "Unsupported channel. Allowed values are: ELM, PDU");
            }
        }

        try {
            ReadingBackfillService.Outcome outcome = readingBackfillService.republish(
                    tenantId,
                    request.getFromDate(),
                    request.getToDate(),
                    request.getStateSchemeId(),
                    request.getCentreSchemeId(),
                    channel.orElse(null));
            return ResponseEntity.ok(RepublishReadingsResponse.republished(
                    outcome.republishedCount(), outcome.withheldCount()));
        } catch (ResponseStatusException e) {
            log.info("{} rejected tenantId={} httpStatus={} reason=\"{}\" stateSchemeId={} centreSchemeId={}",
                    API, tenantId, e.getStatusCode(), sanitize(e.getReason()),
                    sanitize(request.getStateSchemeId()), sanitize(request.getCentreSchemeId()));
            return ResponseEntity.status(e.getStatusCode()).body(RepublishReadingsResponse.rejected(
                    HttpStatus.NOT_FOUND.equals(e.getStatusCode())
                            ? TelemetryErrorCode.SCHEME_NOT_FOUND
                            : TelemetryErrorCode.REQUEST_FAILED,
                    e.getReason()));
        } catch (Exception e) {
            log.error("{} failed tenantId={}: {}", API, tenantId, e.getMessage(), e);
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
