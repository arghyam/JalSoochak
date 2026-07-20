package org.arghyam.jalsoochak.user.exceptions;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.dto.common.ApiErrorResponseDTO;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return Map.of("field", field, "message", v.getMessage());
                })
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMalformedJson(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMissingHeader(MissingRequestHeaderException ex) {
        String message = "X-Tenant-Code".equals(ex.getHeaderName())
                ? "Tenant code is required"
                : ex.getMessage();
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleBadRequest(BadRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getErrors());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccountTemporarilyLockedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleAccountLocked(AccountTemporarilyLockedException ex) {
        log.warn("Login blocked – account temporarily locked by brute-force detection");
        ResponseEntity<ApiErrorResponseDTO> response = build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(response.getBody());
    }

    @ExceptionHandler(CaptchaVerificationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleCaptchaVerification(CaptchaVerificationException ex) {
        log.warn("CAPTCHA verification failed");
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(AccountDeactivatedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleDeactivated(AccountDeactivatedException ex) {
        log.warn("Access denied – account deactivated: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(ForbiddenAccessException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleForbidden(ForbiddenAccessException ex) {
        log.warn("Authorization violation: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleUnauthorized(UnauthorizedAccessException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(KeycloakOperationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleKeycloakOperation(KeycloakOperationException ex) {
        log.error("Keycloak operation failed: {}", ex.getMessage(), ex);
        Integer statusCode = ex.getStatusCode();
        HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        if (statusCode != null) {
            try {
                httpStatus = HttpStatus.valueOf(statusCode);
            } catch (IllegalArgumentException ignored) {
                // fall back to 500
            }
        }
        String message = statusCode != null ? ex.getMessage() : "An identity provider error occurred";
        return build(httpStatus, message);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleConflict(UserAlreadyExistsException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InsufficientActiveUsersException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleInsufficientUsers(InsufficientActiveUsersException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(TokenAlreadyUsedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleTokenUsed(TokenAlreadyUsedException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason()
                : ex.getMessage();
        return build(status, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> requiredType = ex.getRequiredType();
        String paramName = ex.getParameter().getParameterName() != null
                ? ex.getParameter().getParameterName()
                : ex.getName();
        String message;
        if (requiredType != null && requiredType.isEnum()) {
            String validValues = Arrays.stream(requiredType.getEnumConstants())
                    .map(e -> ((Enum<?>) e).name())
                    .collect(Collectors.joining(", "));
            message = String.format("Invalid value '%s' for parameter '%s'. Accepted values: [%s]",
                    ex.getValue(), paramName, validValues);
        } else {
            message = String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), paramName);
        }
        log.warn("Type mismatch: {}", message);
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleNoResourceFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Media type not supported: " + ex.getContentType());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return build(HttpStatus.NOT_ACCEPTABLE, "Requested media type not acceptable");
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleStorage(StorageException ex) {
        log.error("Object storage failure", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Report storage is currently unavailable");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String causeType = cause != null ? cause.getClass().getSimpleName() : ex.getClass().getSimpleName();
        log.error("Data integrity violation occurred: {}", causeType);
        log.debug("Full exception:", ex);
        return build(HttpStatus.CONFLICT, "Request conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponseDTO> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<ApiErrorResponseDTO> build(HttpStatus status, String message) {
        return build(status, message, null);
    }

    private ResponseEntity<ApiErrorResponseDTO> build(HttpStatus status, String message, Object fieldErrors) {
        String requestId = MDC.get("requestId");
        return ResponseEntity.status(status).body(
                new ApiErrorResponseDTO(status.value(), status.getReasonPhrase(), message,
                        requestId != null && !requestId.isBlank() ? requestId : null,
                        fieldErrors));
    }
}
