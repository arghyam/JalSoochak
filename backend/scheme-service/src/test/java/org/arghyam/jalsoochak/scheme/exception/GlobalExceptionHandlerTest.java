package org.arghyam.jalsoochak.scheme.exception;

import org.arghyam.jalsoochak.scheme.dto.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadErrorDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleFileValidation_returnsBadRequestWithErrors() {
        SchemeUploadErrorDTO error = SchemeUploadErrorDTO.builder().rowNumber(2).field("state_scheme_id").message("required").build();
        FileValidationException ex = new FileValidationException("validation failed", List.of(error));

        ResponseEntity<ApiErrorResponseDTO> response = handler.handleFileValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("validation failed");
        assertThat(response.getBody().getErrors()).containsExactly(error);
    }

    @Test
    void handleUnsupportedType_returnsUnsupportedMediaType() {
        ResponseEntity<ApiErrorResponseDTO> response = handler.handleUnsupportedType(new UnsupportedFileTypeException("bad type"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("bad type");
    }

    @Test
    void handleResponseStatus_usesReasonOrDefaultReasonPhrase() {
        ResponseEntity<ApiErrorResponseDTO> explicit = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
        ResponseEntity<ApiErrorResponseDTO> fallback = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThat(explicit.getBody()).isNotNull();
        assertThat(explicit.getBody().getMessage()).isEqualTo("missing");
        assertThat(fallback.getBody()).isNotNull();
        assertThat(fallback.getBody().getMessage()).isEqualTo(HttpStatus.FORBIDDEN.getReasonPhrase());
    }

    @Test
    void handleTypeMismatch_returnsBadRequestNamingTheParameter() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "tenantId", null, new NumberFormatException("abc"));

        ResponseEntity<ApiErrorResponseDTO> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid value 'abc' for parameter 'tenantId'");
    }

    @Test
    void handleMissingParam_returnsBadRequestNamingTheParameter() {
        ResponseEntity<ApiErrorResponseDTO> response = handler.handleMissingParam(
                new MissingServletRequestParameterException("tenantCode", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Required parameter 'tenantCode' is missing");
    }

    @Test
    void handleMalformedBody_returnsBadRequestWithoutEchoingTheBody() {
        HttpInputMessage input = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return InputStream.nullInputStream();
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Unexpected token at {\"secret\":", input);

        ResponseEntity<ApiErrorResponseDTO> response = handler.handleMalformedBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }

    @Test
    void handleMaxUploadSize_returnsPayloadTooLargeWithTheLimit() {
        ResponseEntity<ApiErrorResponseDTO> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1_048_576L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("1048576");
    }

    @Test
    void handleMalformedMultipart_returnsBadRequest() {
        ResponseEntity<ApiErrorResponseDTO> response =
                handler.handleMalformedMultipart(new MultipartException("boundary missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed multipart request");
    }

    @Test
    void handleMediaTypeNotSupported_returnsUnsupportedMediaType() {
        ResponseEntity<ApiErrorResponseDTO> response = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_XML, List.of(MediaType.MULTIPART_FORM_DATA)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains(MediaType.APPLICATION_XML_VALUE);
    }

    @Test
    void handleMethodNotSupported_returnsMethodNotAllowed() {
        ResponseEntity<ApiErrorResponseDTO> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void handleNoResourceFound_returnsNotFoundWithoutEchoingThePath() {
        ResponseEntity<ApiErrorResponseDTO> response =
                handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/api/v1/typo"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Resource not found");
    }

    @Test
    void handleGenericException_returnsInternalServerError() {
        ResponseEntity<ApiErrorResponseDTO> response = handler.handleGenericException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Unexpected server error");
        assertThat(response.getBody().getErrors()).isEmpty();
    }
}
