package org.arghyam.jalsoochak.telemetry.controller.staff;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.arghyam.jalsoochak.telemetry.config.OpenApiConfig;
import org.arghyam.jalsoochak.telemetry.config.SecurityConfig;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateYesterdayFinalReadingBySchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.UpdateYesterdayFinalReadingBySchemeResponse;
import org.arghyam.jalsoochak.telemetry.service.StaffCaller;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySchemeReadingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * {@code PATCH /api/v1/telemetry/staff/schemes/{schemeId}/yesterday-final-reading}: a section or
 * sub-divisional officer's correction of a scheme's submitted final reading, from the officer web UI.
 *
 * <p>{@link SecurityConfig} authenticates the officer's Keycloak token before this handler runs. The
 * tenant and the officer are read from that token and handed to the service as a {@link StaffCaller};
 * {@code X-Tenant-Code} plays no part. Every failure past the security chain is answered in the
 * {@link UpdateYesterdayFinalReadingBySchemeResponse} envelope.
 */
@RestController
@RequestMapping(SecurityConfig.STAFF_BASE_PATH)
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class StaffReadingCorrectionController {

    private static final Logger log = LoggerFactory.getLogger(StaffReadingCorrectionController.class);
    private static final String API = SecurityConfig.STAFF_BASE_PATH + "/schemes/{schemeId}/yesterday-final-reading";
    static final String TENANT_CLAIM = "tenant_state_code";

    private final TelemetrySchemeReadingService telemetrySchemeReadingService;

    public StaffReadingCorrectionController(TelemetrySchemeReadingService telemetrySchemeReadingService) {
        this.telemetrySchemeReadingService = telemetrySchemeReadingService;
    }

    @PatchMapping(
            value = "/schemes/{schemeId}/yesterday-final-reading",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> updateYesterdayFinalReading(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long schemeId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody @Valid UpdateYesterdayFinalReadingBySchemeRequest request
    ) {
        // The service logs every outcome, so nothing is logged here.
        try {
            return ResponseEntity.ok(telemetrySchemeReadingService.updateYesterdayFinalReadingBySchemeId(
                    schemeId, request.getReading(), date, callerOf(jwt)));
        } catch (ResponseStatusException e) {
            return reject(e.getStatusCode(), schemeId, request.getReading(), e.getReason());
        } catch (Exception e) {
            return reject(HttpStatus.INTERNAL_SERVER_ERROR, schemeId, request.getReading(), "Internal error");
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .collect(Collectors.joining("; "));
        log.info("{} rejected reason=\"validation\" message=\"{}\"", API, message);
        return reject(HttpStatus.BAD_REQUEST, null, null, message.isBlank() ? "Validation failed" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> handleUnreadableJson(HttpMessageNotReadableException ex) {
        log.info("{} rejected reason=\"malformed body\"", API);
        return reject(HttpStatus.BAD_REQUEST, null, null, "Malformed request body");
    }

    /** {@code null} without a token, which the service refuses as unauthenticated. */
    private static StaffCaller callerOf(Jwt jwt) {
        return jwt == null ? null : new StaffCaller(jwt.getClaimAsString(TENANT_CLAIM), jwt.getSubject());
    }

    private static ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> reject(HttpStatusCode status,
                                                                                      Long schemeId,
                                                                                      BigDecimal reading,
                                                                                      String message) {
        return ResponseEntity.status(status).body(UpdateYesterdayFinalReadingBySchemeResponse.builder()
                .success(false)
                .schemeId(schemeId)
                .finalReading(reading)
                .message(message)
                .build());
    }
}
