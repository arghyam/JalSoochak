package org.arghyam.jalsoochak.scheme.exception;

import org.arghyam.jalsoochak.scheme.dto.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadErrorDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

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
    void handleGenericException_returnsInternalServerError() {
        ResponseEntity<ApiErrorResponseDTO> response = handler.handleGenericException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Unexpected server error");
        assertThat(response.getBody().getErrors()).isEmpty();
    }
}
