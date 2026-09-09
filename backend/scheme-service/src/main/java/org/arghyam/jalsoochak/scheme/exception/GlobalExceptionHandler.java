package org.arghyam.jalsoochak.scheme.exception;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.dto.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadErrorDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Translates every exception that reaches the dispatcher into an {@link ApiErrorResponseDTO}.
 *
 * <p>NOTE on ordering: the {@code Exception.class} handler at the bottom is the closest match for
 * anything not named explicitly above it, including the exceptions Spring MVC raises while binding
 * a request — a non-numeric {@code page}, an absent {@code tenantCode}, a malformed JSON body, an
 * oversized upload. Those are all caller errors, but without an explicit mapping each one is
 * reported as 500 "Unexpected server error", which hides a 4xx behind a server fault and puts
 * caller input in the error log. Every such exception the service can raise is therefore mapped
 * below; add to that list rather than relying on the catch-all.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(FileValidationException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleFileValidation(FileValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getErrors());
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleUnsupportedType(UnsupportedFileTypeException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return build(status, message, List.of());
    }

    /**
     * Maps {@code @PreAuthorize} denials to 403.
     *
     * <p>Without this, the {@code Exception.class} handler below claims {@code AccessDeniedException}
     * — it reaches the advice chain via {@code DispatcherServlet} rather than Spring Security's
     * {@code ExceptionTranslationFilter} — and every authorization failure surfaces as a 500.
     * The reason is deliberately generic so that a denial leaks nothing about the target tenant.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "Access denied", List.of());
    }

    /**
     * A query or path value that will not convert to the declared type — {@code ?page=abc},
     * {@code ?tenantId=abc}, {@code /schemes/abc/statuses}.
     *
     * <p>Reads the name off {@link MethodArgumentTypeMismatchException#getName()} rather than its
     * {@code MethodParameter}, which is null when the exception is raised outside argument
     * resolution and does not carry a name unless the class was compiled with {@code -parameters}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
        log.warn("Type mismatch: {}", message);
        return build(HttpStatus.BAD_REQUEST, message, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing", List.of());
    }

    /**
     * Body that Jackson cannot read — malformed JSON, or none at all where one is required.
     * The parse detail is logged but kept out of the response, since it echoes the request body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMalformedBody(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", List.of());
    }

    /**
     * Upload past {@code spring.servlet.multipart.max-file-size} (1 MB by default, which the
     * scheme and mapping CSVs can exceed).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        long maxBytes = ex.getMaxUploadSize();
        String message = maxBytes > 0
                ? "Uploaded file exceeds the maximum permitted size of " + maxBytes + " bytes"
                : "Uploaded file exceeds the maximum permitted size";
        log.warn("Upload rejected: {}", message);
        return build(HttpStatus.PAYLOAD_TOO_LARGE, message, List.of());
    }

    /**
     * Multipart body the container could not parse. Declared alongside
     * {@link #handleMaxUploadSize} — Spring resolves the most specific handler, so the
     * size-exceeded subclass still answers 413 rather than falling in here.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMalformedMultipart(MultipartException ex) {
        log.warn("Malformed multipart request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed multipart request", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Media type not supported: " + ex.getContentType(), List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), List.of());
    }

    /**
     * Unmapped path. Raised by {@code ResourceHttpRequestHandler}, so without this entry a plain
     * typo in a URL is reported as a server fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleNoResourceFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponseDTO> handleGenericException(Exception ex) {
        log.error("Unexpected error while processing request", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", List.of());
    }

    private ResponseEntity<ApiErrorResponseDTO> build(
            HttpStatus status,
            String message,
            List<SchemeUploadErrorDTO> errors
    ) {
        ApiErrorResponseDTO body = ApiErrorResponseDTO.builder()
                .timestamp(OffsetDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .errors(errors)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
