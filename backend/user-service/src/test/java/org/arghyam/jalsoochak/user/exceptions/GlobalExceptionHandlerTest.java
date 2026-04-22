package org.arghyam.jalsoochak.user.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.arghyam.jalsoochak.user.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── ConstraintViolationException ─────────────────────────────────────────

    @Nested
    @DisplayName("handleConstraintViolation")
    class HandleConstraintViolation {

        @Test
        @DisplayName("returns 400 with field errors from constraint violations")
        void returns400WithFieldErrors() {
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("methodName.fieldName");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("must not be null");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConstraintViolation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Validation failed");
        }

        @Test
        @DisplayName("extracts field name after last dot in path")
        void extractsFieldNameAfterLastDot() {
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("myMethod.myField");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("invalid");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConstraintViolation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("uses whole path as field name when path contains no dot")
        void usesWholePathWhenNoDot() {
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("fieldOnly");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("must not be blank");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConstraintViolation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
        }
    }

    // ── MethodArgumentNotValidException ──────────────────────────────────────

    @Nested
    @DisplayName("handleValidation")
    class HandleValidation {

        @Test
        @DisplayName("returns 400 with field errors from binding result")
        void returns400WithFieldErrors() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("obj", "email", "must not be blank");
            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Validation failed");
        }
    }

    // ── HttpMessageNotReadableException ──────────────────────────────────────

    @Nested
    @DisplayName("handleMalformedJson")
    class HandleMalformedJson {

        @Test
        @DisplayName("returns 400 for malformed JSON request body")
        void returns400ForMalformedJson() {
            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMalformedJson(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Malformed JSON request");
        }
    }

    // ── MissingRequestHeaderException ────────────────────────────────────────

    @Nested
    @DisplayName("handleMissingHeader")
    class HandleMissingHeader {

        @Test
        @DisplayName("returns tenant-specific message for X-Tenant-Code header")
        void returnsTenantMessageForTenantCodeHeader() {
            MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
            when(ex.getHeaderName()).thenReturn("X-Tenant-Code");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMissingHeader(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Tenant code is required");
        }

        @Test
        @DisplayName("returns generic message for other missing headers")
        void returnsGenericMessageForOtherHeaders() {
            MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
            when(ex.getHeaderName()).thenReturn("Authorization");
            when(ex.getMessage()).thenReturn("Missing request header 'Authorization'");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMissingHeader(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).contains("Authorization");
        }
    }

    // ── BadRequestException ───────────────────────────────────────────────────

    @Nested
    @DisplayName("handleBadRequest")
    class HandleBadRequest {

        @Test
        @DisplayName("returns 400 with message and no errors")
        void returns400WithMessage() {
            BadRequestException ex = new BadRequestException("Invalid input");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleBadRequest(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Invalid input");
        }

        @Test
        @DisplayName("returns 400 with message and field errors")
        void returns400WithFieldErrors() {
            List<String> errors = List.of("field1 error", "field2 error");
            BadRequestException ex = new BadRequestException("Validation failed", errors);
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleBadRequest(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getFieldErrors()).isEqualTo(errors);
        }
    }

    // ── InvalidCredentialsException ───────────────────────────────────────────

    @Nested
    @DisplayName("handleInvalidCredentials")
    class HandleInvalidCredentials {

        @Test
        @DisplayName("returns 401 for invalid credentials")
        void returns401() {
            InvalidCredentialsException ex = new InvalidCredentialsException("Bad credentials");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleInvalidCredentials(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Bad credentials");
        }
    }

    // ── AccountDeactivatedException ───────────────────────────────────────────

    @Nested
    @DisplayName("handleDeactivated")
    class HandleDeactivated {

        @Test
        @DisplayName("returns 403 for deactivated account")
        void returns403() {
            AccountDeactivatedException ex = new AccountDeactivatedException("Account is deactivated");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleDeactivated(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Account is deactivated");
        }
    }

    // ── AccessDeniedException ─────────────────────────────────────────────────

    @Nested
    @DisplayName("handleAccessDenied")
    class HandleAccessDenied {

        @Test
        @DisplayName("returns 403 with generic access denied message")
        void returns403() {
            ResponseEntity<ApiErrorResponseDTO> response =
                    handler.handleAccessDenied(new AccessDeniedException("Access Denied"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Access denied");
        }
    }

    // ── ForbiddenAccessException ──────────────────────────────────────────────

    @Nested
    @DisplayName("handleForbidden")
    class HandleForbidden {

        @Test
        @DisplayName("returns 403 with exception message")
        void returns403WithMessage() {
            ForbiddenAccessException ex = new ForbiddenAccessException("Not allowed");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleForbidden(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Not allowed");
        }
    }

    // ── UnauthorizedAccessException ───────────────────────────────────────────

    @Nested
    @DisplayName("handleUnauthorized")
    class HandleUnauthorized {

        @Test
        @DisplayName("returns 401 with exception message")
        void returns401WithMessage() {
            UnauthorizedAccessException ex = new UnauthorizedAccessException("Unauthorized");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleUnauthorized(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Unauthorized");
        }
    }

    // ── KeycloakOperationException ────────────────────────────────────────────

    @Nested
    @DisplayName("handleKeycloakOperation")
    class HandleKeycloakOperation {

        @Test
        @DisplayName("returns 500 when statusCode is null")
        void returns500WhenStatusCodeNull() {
            KeycloakOperationException ex = new KeycloakOperationException("Keycloak error");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleKeycloakOperation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("An identity provider error occurred");
        }

        @Test
        @DisplayName("maps statusCode to corresponding HTTP status")
        void mapsStatusCodeToHttpStatus() {
            KeycloakOperationException ex = new KeycloakOperationException("Conflict", 409);
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleKeycloakOperation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Conflict");
        }

        @Test
        @DisplayName("falls back to 500 when statusCode is not a valid HTTP status")
        void fallsBackTo500ForInvalidStatusCode() {
            KeycloakOperationException ex = new KeycloakOperationException("Unknown", 999);
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleKeycloakOperation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── ResourceNotFoundException ─────────────────────────────────────────────

    @Nested
    @DisplayName("handleNotFound")
    class HandleNotFound {

        @Test
        @DisplayName("returns 404 with exception message")
        void returns404() {
            ResourceNotFoundException ex = new ResourceNotFoundException("User not found");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleNotFound(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("User not found");
        }
    }

    // ── IllegalArgumentException ──────────────────────────────────────────────

    @Nested
    @DisplayName("handleIllegalArgument")
    class HandleIllegalArgument {

        @Test
        @DisplayName("returns 400 with exception message")
        void returns400() {
            IllegalArgumentException ex = new IllegalArgumentException("Bad argument");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleIllegalArgument(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Bad argument");
        }
    }

    // ── UserAlreadyExistsException ────────────────────────────────────────────

    @Nested
    @DisplayName("handleConflict")
    class HandleConflict {

        @Test
        @DisplayName("returns 409 for user already exists")
        void returns409() {
            UserAlreadyExistsException ex = new UserAlreadyExistsException("User exists");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConflict(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("User exists");
        }
    }

    // ── InsufficientActiveUsersException ─────────────────────────────────────

    @Nested
    @DisplayName("handleInsufficientUsers")
    class HandleInsufficientUsers {

        @Test
        @DisplayName("returns 409 for insufficient active users")
        void returns409() {
            InsufficientActiveUsersException ex = new InsufficientActiveUsersException("Not enough active users");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleInsufficientUsers(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Not enough active users");
        }
    }

    // ── TokenAlreadyUsedException ─────────────────────────────────────────────

    @Nested
    @DisplayName("handleTokenUsed")
    class HandleTokenUsed {

        @Test
        @DisplayName("returns 400 for already-used token")
        void returns400() {
            TokenAlreadyUsedException ex = new TokenAlreadyUsedException("Token already used");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleTokenUsed(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Token already used");
        }
    }

    // ── ResponseStatusException ───────────────────────────────────────────────

    @Nested
    @DisplayName("handleResponseStatus")
    class HandleResponseStatus {

        @Test
        @DisplayName("uses reason phrase when present")
        void usesReasonPhraseWhenPresent() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.GONE, "Resource gone");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleResponseStatus(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Resource gone");
        }

        @Test
        @DisplayName("falls back to exception message when reason is blank")
        void fallsBackToExceptionMessageWhenReasonBlank() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleResponseStatus(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo(ex.getMessage());
        }
    }

    // ── MissingServletRequestParameterException ───────────────────────────────

    @Nested
    @DisplayName("handleMissingParam")
    class HandleMissingParam {

        @Test
        @DisplayName("returns 400 with parameter name in message")
        void returns400WithParamName() {
            MissingServletRequestParameterException ex =
                    new MissingServletRequestParameterException("page", "Integer");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMissingParam(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).contains("page");
        }
    }

    // ── MethodArgumentTypeMismatchException ───────────────────────────────────

    @Nested
    @DisplayName("handleTypeMismatch")
    class HandleTypeMismatch {

        @Test
        @DisplayName("includes enum accepted values for enum types")
        void includesEnumAcceptedValues() {
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            MethodParameter param = mock(MethodParameter.class);

            when(ex.getRequiredType()).thenAnswer(inv -> AdminUserStatus.class);
            when(ex.getParameter()).thenReturn(param);
            when(param.getParameterName()).thenReturn(null);
            when(ex.getName()).thenReturn("status");
            when(ex.getValue()).thenReturn("WRONG");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleTypeMismatch(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            String message = response.getBody().getMessage();
            assertThat(message).contains("WRONG").contains("status");
            for (AdminUserStatus s : AdminUserStatus.values()) {
                assertThat(message).contains(s.name());
            }
        }

        @Test
        @DisplayName("produces generic message for null required type")
        void producesGenericMessageForNullType() {
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            MethodParameter param = mock(MethodParameter.class);

            when(ex.getRequiredType()).thenReturn(null);
            when(ex.getParameter()).thenReturn(param);
            when(param.getParameterName()).thenReturn("id");
            when(ex.getValue()).thenReturn("xyz");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleTypeMismatch(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getMessage()).contains("xyz").contains("id");
        }

        @Test
        @DisplayName("produces generic message for non-enum types")
        void producesGenericMessageForNonEnum() {
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            MethodParameter param = mock(MethodParameter.class);

            when(ex.getRequiredType()).thenAnswer(inv -> Integer.class);
            when(ex.getParameter()).thenReturn(param);
            when(param.getParameterName()).thenReturn("page");
            when(ex.getValue()).thenReturn("abc");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleTypeMismatch(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getMessage()).doesNotContain("Accepted values");
        }
    }

    // ── HttpRequestMethodNotSupportedException ────────────────────────────────

    @Nested
    @DisplayName("handleMethodNotSupported")
    class HandleMethodNotSupported {

        @Test
        @DisplayName("returns 405 for unsupported HTTP method")
        void returns405() {
            HttpRequestMethodNotSupportedException ex =
                    new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMethodNotSupported(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody()).isNotNull();
        }
    }

    // ── NoResourceFoundException ──────────────────────────────────────────────

    @Nested
    @DisplayName("handleNoResourceFound")
    class HandleNoResourceFound {

        @Test
        @DisplayName("returns 404 with generic resource not found message")
        void returns404() {
            ResponseEntity<ApiErrorResponseDTO> response =
                    handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/unknown"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Resource not found");
        }
    }

    // ── HttpMediaTypeNotSupportedException ────────────────────────────────────

    @Nested
    @DisplayName("handleMediaTypeNotSupported")
    class HandleMediaTypeNotSupported {

        @Test
        @DisplayName("returns 415 with content type in message")
        void returns415() {
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMediaTypeNotSupported(
                    new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_XML,
                            List.of(MediaType.APPLICATION_JSON)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(response.getBody().getMessage()).contains("application/xml");
        }
    }

    // ── HttpMediaTypeNotAcceptableException ───────────────────────────────────

    @Nested
    @DisplayName("handleMediaTypeNotAcceptable")
    class HandleMediaTypeNotAcceptable {

        @Test
        @DisplayName("returns 406 with not acceptable message")
        void returns406() {
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMediaTypeNotAcceptable(
                    new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
            assertThat(response.getBody().getMessage()).isEqualTo("Requested media type not acceptable");
        }
    }

    // ── DataIntegrityViolationException ───────────────────────────────────────

    @Nested
    @DisplayName("handleDataIntegrity")
    class HandleDataIntegrity {

        @Test
        @DisplayName("returns 409 for data integrity violation")
        void returns409() {
            DataIntegrityViolationException ex =
                    new DataIntegrityViolationException("Duplicate key", new RuntimeException("unique_violation"));
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleDataIntegrity(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Request conflicts with existing data");
        }
    }

    // ── Unexpected Exception ──────────────────────────────────────────────────

    @Nested
    @DisplayName("handleUnexpected")
    class HandleUnexpected {

        @Test
        @DisplayName("returns 500 for any unhandled exception")
        void returns500() {
            Exception ex = new RuntimeException("Something went very wrong");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleUnexpected(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
        }
    }
}
