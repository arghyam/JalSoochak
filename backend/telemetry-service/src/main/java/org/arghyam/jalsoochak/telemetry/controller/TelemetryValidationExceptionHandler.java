package org.arghyam.jalsoochak.telemetry.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsDataResponse;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice(assignableTypes = SingleTenantTelemetryController.class)
public class TelemetryValidationExceptionHandler {

    private static final String READINGS_PATH = "/api/v1/telemetry/readings";

    private final TelemetrySubmissionAuditService telemetrySubmissionAuditService;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = validationMessage(ex);
        String servletPath = requestPath(request);

        if (READINGS_PATH.equals(servletPath)) {
            logReadingsValidationFailure(ex.getBindingResult().getTarget(), message);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ReadingsApiResponse.builder()
                            .success(false)
                            .data(ReadingsDataResponse.builder()
                                    .message(message)
                                    .qualityStatus("REJECTED")
                                    .build())
                            .build()
            );
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
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

    private void logReadingsValidationFailure(Object target, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit = auditSnapshot(target);
        log.debug(
                "reading_submission api={} status={} phone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                READINGS_PATH,
                "FAILED",
                audit.maskedPhone(),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
    }

    private TelemetrySubmissionAuditService.SubmissionAuditSnapshot auditSnapshot(Object target) {
        if (telemetrySubmissionAuditService != null && target instanceof AssamReadingRequest request) {
            return telemetrySubmissionAuditService.captureForAssamReading(request, null);
        }
        if (telemetrySubmissionAuditService != null && target instanceof UpdateReadingRequest request) {
            return telemetrySubmissionAuditService.captureForPhoneAndScheme(request.getPhoneNumber(), null);
        }
        return new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, LocalDate.now());
    }

    private String validationMessage(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("; "));
        return message.isBlank() ? "Validation failed" : message;
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
