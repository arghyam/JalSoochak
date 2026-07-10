package org.arghyam.jalsoochak.telemetry.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsDataResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDate;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice(assignableTypes = SingleTenantTelemetryController.class)
public class TelemetryValidationExceptionHandler {

    private static final String READINGS_PATH = "/api/v1/telemetry/readings";
    // Assam's integration hits both /readings and /readings/ (trailing slash), so treat both as the readings endpoint.
    private static final String READINGS_PATH_TRAILING_SLASH = READINGS_PATH + "/";

    private final TelemetrySubmissionAuditService telemetrySubmissionAuditService;
    // REPORTED-METRIC: optional (may be null in unit tests); publishes pre-anomaly rejects for the
    // "reported" scheme counts. Remove this field + publishSubmissionRejected(...) to revert.
    private final TelemetryEventPublisher telemetryEventPublisher;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = validationMessage(ex);
        String servletPath = requestPath(request);

        if (isReadingsPath(servletPath)) {
            logReadingsValidationFailure(request, ex.getBindingResult().getTarget(), ex, message);
            publishSubmissionRejected(ex.getBindingResult().getTarget(), "validation: " + message);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .message(message)
                                    .qualityStatus("REJECTED")
                                    .errorCode(TelemetryErrorCode.VALIDATION_FAILED)
                                    .build())
                            .build()
            );
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String servletPath = requestPath(request);
        String message = "Malformed request body";

        if (isReadingsPath(servletPath)) {
            log.info(
                    "reading_validation_rejected method={} api={} status=FAILED reason=\"{}\" exception=\"{}\" request=\"unreadable\"",
                    requestMethod(request),
                    servletPath,
                    message,
                    sanitizeLogMessage(ex.getMessage())
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .message(message)
                                    .qualityStatus("REJECTED")
                                    .errorCode(TelemetryErrorCode.MALFORMED_REQUEST)
                                    .build())
                            .build()
            );
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }

    private boolean isReadingsPath(String servletPath) {
        return READINGS_PATH.equals(servletPath) || READINGS_PATH_TRAILING_SLASH.equals(servletPath);
    }

    private String requestPath(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri != null && contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri != null ? uri : "";
    }

    private void logReadingsValidationFailure(HttpServletRequest request,
                                              Object target,
                                              MethodArgumentNotValidException ex,
                                              String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit = auditSnapshot(target);
        log.info(
                "reading_validation_rejected method={} api={} status={} phone={} submittedPhone={} schemeId={} dailyUniqueUserCount={} date={} fields=\"{}\" message=\"{}\" request={}",
                requestMethod(request),
                requestPath(request),
                "FAILED",
                audit.maskedPhone(),
                submittedPhone(target),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                validationFields(ex),
                sanitizeLogMessage(message),
                summarizeTarget(target)
        );
        log.info(
                "reading_submission api={} status={} phone={} submittedPhone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                requestPath(request),
                "FAILED",
                audit.maskedPhone(),
                submittedPhone(target),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
        // Raw phone numbers are PII and must never appear in INFO/WARN/ERROR logs. Expose them only at
        // DEBUG so an operator can correlate a masked rejection back to the actual number while debugging.
        if (log.isDebugEnabled()) {
            log.debug(
                    "reading_validation_rejected_detail method={} api={} status=FAILED rawPhone={} fields=\"{}\"",
                    requestMethod(request),
                    requestPath(request),
                    rawSubmittedPhone(target),
                    validationFields(ex)
            );
        }
    }

    // REPORTED-METRIC: capture a validation-rejected submission (before any reading/anomaly is written)
    // so analytics can count the scheme as having reported. Tenant is not resolved at validation time;
    // analytics resolves the scheme/tenant from the submitted scheme id. No raw PII is sent.
    private void publishSubmissionRejected(Object target, String reason) {
        if (telemetryEventPublisher == null || !(target instanceof AssamReadingRequest request)) {
            return;
        }
        boolean hasSchemeId = (request.getStateSchemeId() != null && !request.getStateSchemeId().isBlank())
                || (request.getCentreSchemeId() != null && !request.getCentreSchemeId().isBlank());
        if (!hasSchemeId) {
            return; // nothing to attribute the reject to
        }
        telemetryEventPublisher.publishSubmissionRejected(
                null,                          // tenantId: not resolved at validation time; analytics derives it from the scheme id
                request.getStateSchemeId(),
                request.getCentreSchemeId(),
                null,                          // submittedPhoneHash: intentionally null here — validation rejects often carry a
                                               // blank/invalid phone, and the reported-count keys on scheme_id, not the phone.
                                               // The field stays for callers that DO have a resolvable phone to hash.
                sanitizeLogMessage(reason)
        );
    }

    private TelemetrySubmissionAuditService.SubmissionAuditSnapshot auditSnapshot(Object target) {
        if (telemetrySubmissionAuditService != null && target instanceof AssamReadingRequest request) {
            return telemetrySubmissionAuditService.captureForAssamReading(request, null);
        }
        if (telemetrySubmissionAuditService != null && target instanceof UpdateReadingRequest request) {
            return telemetrySubmissionAuditService.captureForPhoneAndScheme(request.getPhoneNumber(), null);
        }
        return new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, LocalDate.now(ReadingTime.ZONE));
    }

    private String validationMessage(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("; "));
        return message.isBlank() ? "Validation failed" : message;
    }

    private String validationFields(MethodArgumentNotValidException ex) {
        String fields = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fieldError) {
                        return fieldError.getField() + ":" + fieldError.getCode();
                    }
                    return error.getObjectName() + ":" + error.getCode();
                })
                .collect(Collectors.joining(","));
        return fields.isBlank() ? "unknown" : sanitizeLogMessage(fields);
    }

    private String summarizeTarget(Object target) {
        if (target instanceof AssamReadingRequest request) {
            return String.format(
                    "{type=AssamReadingRequest,phone=%s,stateSchemeId=%s,centreSchemeId=%s,hasReadingUrl=%s,confirmedReading=%s,readingDateTime=%s,hasGeolocation=%s}",
                    maskPhone(request.getPhoneNumber()),
                    sanitizeLogValue(request.getStateSchemeId()),
                    sanitizeLogValue(request.getCentreSchemeId()),
                    request.getReadingUrl() != null && !request.getReadingUrl().isBlank(),
                    request.getConfirmedReading(),
                    request.getReadingDateTime(),
                    request.getGeolocation() != null
            );
        }
        if (target instanceof UpdateReadingRequest request) {
            return String.format(
                    "{type=UpdateReadingRequest,phone=%s,correlationId=%s,hasImageId=%s,confirmedReading=%s}",
                    maskPhone(request.getPhoneNumber()),
                    sanitizeLogValue(request.getCorrelationId()),
                    request.getImageId() != null && !request.getImageId().isBlank(),
                    request.getConfirmedReading()
            );
        }
        return target == null ? "null" : "{type=" + target.getClass().getSimpleName() + "}";
    }

    private String submittedPhone(Object target) {
        return maskPhone(rawTargetPhone(target));
    }

    private String rawSubmittedPhone(Object target) {
        return sanitizeLogValue(rawTargetPhone(target));
    }

    private String rawTargetPhone(Object target) {
        if (target instanceof AssamReadingRequest request) {
            return request.getPhoneNumber();
        }
        if (target instanceof UpdateReadingRequest request) {
            return request.getPhoneNumber();
        }
        return null;
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

    private String requestMethod(HttpServletRequest request) {
        if (request == null || request.getMethod() == null || request.getMethod().isBlank()) {
            return "UNKNOWN";
        }
        return request.getMethod();
    }

    private String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
