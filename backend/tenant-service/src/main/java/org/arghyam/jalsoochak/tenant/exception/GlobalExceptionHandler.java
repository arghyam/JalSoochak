package org.arghyam.jalsoochak.tenant.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication required: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return Map.of("field", field, "message", v.getMessage());
                })
                .toList();
        log.warn("Constraint violation: {}", fieldErrors);
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleValidationErrors(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    String message = Optional.ofNullable(fe.getDefaultMessage()).orElse("Validation failed");
                    return Map.of("field", fe.getField(), "message", message);
                })
                .toList();
        log.warn("Validation failed: {}", fieldErrors);
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenAccessException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleForbiddenAccess(ForbiddenAccessException ex) {
        log.warn("Forbidden access: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage());
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
            message = String.format("Invalid value '%s' for parameter '%s'",
                    ex.getValue(), paramName);
        }
        log.warn("Type mismatch: {}", message);
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(InvalidConfigKeyException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleInvalidConfigKey(InvalidConfigKeyException ex) {
        log.warn("Invalid config key: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InvalidConfigValueException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleInvalidConfigValue(InvalidConfigValueException ex) {
        log.warn("Invalid config value: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * MESSAGING-PROVIDER-SECRETS: 503, not 500 — the request is valid and would succeed once
     * the deployment configures a master key, so this is an unavailable capability rather
     * than a failure. The message names the missing configuration, never key material.
     */
    @ExceptionHandler(SecretStoreUnavailableException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleSecretStoreUnavailable(SecretStoreUnavailableException ex) {
        log.warn("Secret store unavailable: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(ConfigurationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleConfigurationException(ConfigurationException ex) {
        log.error("Configuration error: {}", ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Configuration processing failed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(LocationHierarchyStructureLockedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleLocationHierarchyStructureLocked(
            LocationHierarchyStructureLockedException ex) {
        log.warn("Location hierarchy structure locked: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleConflict(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            Class<?> targetType = ife.getTargetType();
            if (targetType != null && targetType.isEnum()) {
                String invalidValue = String.valueOf(ife.getValue());
                String validValues = Arrays.stream(targetType.getEnumConstants())
                        .map(e -> ((Enum<?>) e).name())
                        .collect(Collectors.joining(", "));
                String message = String.format(
                        "Invalid value '%s' for type '%s'. Accepted values: [%s]",
                        invalidValue, targetType.getSimpleName(), validValues);
                log.warn("Invalid enum value in request body: {}", message);
                return build(HttpStatus.BAD_REQUEST, message);
            }
            String message = String.format("Invalid value '%s': %s", ife.getValue(), ife.getOriginalMessage());
            log.warn("Invalid format in request body: {}", message);
            return build(HttpStatus.BAD_REQUEST, message);
        }
        // A settings DTO that rejects its input from a @JsonCreator or a @JsonAnySetter — as the
        // messaging provider settings do — reaches here wrapped in a JsonMappingException. Its own
        // message names the offending property or value, which is the whole point of rejecting it;
        // the generic fallback below would throw that away and leave the caller guessing.
        String rejection = settingsRejectionMessage(cause);
        if (rejection != null) {
            log.warn("Rejected request body: {}", rejection);
            return build(HttpStatus.BAD_REQUEST, rejection);
        }
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    /**
     * Walks the cause chain and returns the message of a settings DTO's own rejection.
     *
     * <p>Matches {@link SettingsRejectedException} and not {@link IllegalArgumentException}: any
     * JDK or third-party {@code IllegalArgumentException} raised while deserializing any body in
     * this service — a {@code java.time} or {@code URI} coercion, an unrelated DTO's creator —
     * routinely quotes the input that produced it, and on the messaging endpoints that input sits
     * next to a credential. Those stay collapsed into the opaque generic message.
     *
     * <p>Bounded, because a cause chain can in principle be cyclic.
     */
    private static String settingsRejectionMessage(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SettingsRejectedException && current.getMessage() != null
                    && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return null;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMissingRequestParam(MissingServletRequestParameterException ex) {
        String message = String.format("Required request parameter '%s' of type '%s' is missing",
                ex.getParameterName(), ex.getParameterType());
        log.warn("Missing request parameter: {}", message);
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String message = String.format("HTTP method '%s' is not supported for this endpoint. Supported: %s",
                ex.getMethod(), ex.getSupportedHttpMethods());
        log.warn("Method not allowed: {}", message);
        return build(HttpStatus.METHOD_NOT_ALLOWED, message);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        String message = String.format("Content-Type '%s' is not supported. Supported types: %s",
                ex.getContentType(), ex.getSupportedMediaTypes());
        log.warn("Unsupported media type: {}", message);
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message);
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleStorageException(StorageException ex) {
        log.error("Storage operation failed: {}", ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "File storage operation failed");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleRuntimeException(RuntimeException ex) {
        log.error("Unexpected runtime error: {}", ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponseDTO> handleException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<ApiErrorResponseDTO> build(HttpStatus status, String message) {
        return build(status, message, null);
    }

    private ResponseEntity<ApiErrorResponseDTO> build(HttpStatus status, String message, Object fieldErrors) {
        return ResponseEntity.status(status).body(
                new ApiErrorResponseDTO(status.value(), status.getReasonPhrase(), message, fieldErrors));
    }
}
