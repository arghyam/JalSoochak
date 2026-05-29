package org.arghyam.jalsoochak.tenant.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

/**
 * Unit tests for GlobalExceptionHandler.
 * Covers exception handling for various Spring Web validation and type mismatch scenarios.
 */
@DisplayName("Global Exception Handler Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("Authentication Handler Tests")
    class AuthenticationHandlerTests {

        @Test
        @DisplayName("Should handle AuthenticationException and return 401")
        void testHandleAuthenticationException() {
            AuthenticationException ex = new AuthenticationException("Token expired") {};

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleAuthenticationException(ex);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(401, response.getBody().getStatus());
            assertEquals("Unauthorized", response.getBody().getError());
            assertEquals("Authentication required", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("Access Denied Handler Tests")
    class AccessDeniedHandlerTests {

        @Test
        @DisplayName("Should handle AccessDeniedException and return 403")
        void testHandleAccessDeniedException() {
            AccessDeniedException ex = new AccessDeniedException("Insufficient scope");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleAccessDeniedException(ex);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(403, response.getBody().getStatus());
            assertEquals("Forbidden", response.getBody().getError());
            assertEquals("Access denied", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("ConstraintViolation Handler Tests")
    class ConstraintViolationHandlerTests {

        @Test
        @DisplayName("Should handle ConstraintViolationException and return 400 with field errors")
        @SuppressWarnings("unchecked")
        void testHandleConstraintViolation() {
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("createRequest.name");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("must not be blank");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConstraintViolation(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Validation failed", response.getBody().getMessage());
            assertNotNull(response.getBody().getFieldErrors());
        }

        @Test
        @DisplayName("Should extract last segment of dotted path as field name")
        @SuppressWarnings("unchecked")
        void testHandleConstraintViolation_extractsLastSegmentAsFieldName() {
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("object.nested.fieldName");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("must not be null");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConstraintViolation(ex);

            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, String>> fieldErrors =
                    (java.util.List<java.util.Map<String, String>>) response.getBody().getFieldErrors();
            assertThat(fieldErrors).anyMatch(e -> "fieldName".equals(e.get("field")));
        }
    }

    @Nested
    @DisplayName("StorageException Handler Tests")
    class StorageExceptionHandlerTests {

        @Test
        @DisplayName("Should handle StorageException and return 500 with safe message")
        void testHandleStorageException() {
            StorageException ex = new StorageException("S3 upload failed: connection refused");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleStorageException(ex);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(500, response.getBody().getStatus());
            assertEquals("Internal Server Error", response.getBody().getError());
            assertEquals("File storage operation failed", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("Validation Error Handler Tests")
    class ValidationErrorHandlerTests {

        @Test
        @DisplayName("Should handle validation errors and return 400")
        void testHandleValidationErrors_Success() {
            // Arrange
            BindingResult bindingResult = mock(BindingResult.class);
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);

            List<FieldError> fieldErrors = new ArrayList<>();
            fieldErrors.add(new FieldError("object", "name", "Name is required"));
            fieldErrors.add(new FieldError("object", "email", "Invalid email format"));

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(fieldErrors);

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleValidationErrors(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Bad Request", response.getBody().getError());
            assertEquals("Validation failed", response.getBody().getMessage());
            assertNotNull(response.getBody().getFieldErrors());
            @SuppressWarnings("unchecked")
            List<Map<String, String>> errors =
                    (List<Map<String, String>>) response.getBody().getFieldErrors();
            assertEquals(2, errors.size());
            assertTrue(errors.stream().anyMatch(e -> "name".equals(e.get("field")) && "Name is required".equals(e.get("message"))));
            assertTrue(errors.stream().anyMatch(e -> "email".equals(e.get("field")) && "Invalid email format".equals(e.get("message"))));
        }

        @Test
        @DisplayName("Should handle validation errors with no field errors")
        void testHandleValidationErrors_NoFieldErrors() {
            // Arrange
            BindingResult bindingResult = mock(BindingResult.class);
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(new ArrayList<>());

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleValidationErrors(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Validation failed", response.getBody().getMessage());
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, String>> errors =
                    (java.util.List<java.util.Map<String, String>>) response.getBody().getFieldErrors();
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("Type Mismatch Handler Tests")
    class TypeMismatchHandlerTests {

        @Test
        @DisplayName("Should handle type mismatch for non-enum and return bad request with correct message")
        void testHandleTypeMismatch_NonEnum() {
            // Arrange
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            when(ex.getValue()).thenReturn("abc");
            when(ex.getRequiredType()).thenReturn(null);
            when(ex.getParameter()).thenReturn(mock(MethodParameter.class));
            when(ex.getParameter().getParameterName()).thenReturn("pageSize");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleTypeMismatch(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertTrue(response.getBody().getMessage().contains("Invalid value"));
            assertTrue(response.getBody().getMessage().contains("pageSize"));
            assertFalse(response.getBody().getMessage().contains("Accepted values"));
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        @Test
        @DisplayName("Should include accepted enum values in message when type mismatch is for an enum param")
        void testHandleTypeMismatch_EnumType_IncludesAcceptedValues() {
            // Arrange
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            when(ex.getValue()).thenReturn("NOT_A_STATUS");
            when(ex.getRequiredType()).thenReturn((Class) TenantStatusEnum.class);
            when(ex.getParameter()).thenReturn(mock(MethodParameter.class));
            when(ex.getParameter().getParameterName()).thenReturn("status");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleTypeMismatch(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertTrue(response.getBody().getMessage().contains("NOT_A_STATUS"));
            assertTrue(response.getBody().getMessage().contains("status"));
            assertTrue(response.getBody().getMessage().contains("Accepted values"));
            assertTrue(response.getBody().getMessage().contains("ACTIVE"));
            assertTrue(response.getBody().getMessage().contains("INACTIVE"));
        }
    }

    @Nested
    @DisplayName("ResourceNotFoundException Handler Tests")
    class ResourceNotFoundHandlerTests {

        @Test
        @DisplayName("Should handle resource not found and return 404")
        void testHandleResourceNotFound_Success() {
            // Arrange
            ResourceNotFoundException ex = new ResourceNotFoundException("Tenant not found");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleResourceNotFound(ex);

            // Assert
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(404, response.getBody().getStatus());
            assertEquals("Not Found", response.getBody().getError());
            assertEquals("Tenant not found", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("ForbiddenAccessException Handler Tests")
    class ForbiddenAccessHandlerTests {

        @Test
        @DisplayName("Should handle forbidden access and return 403")
        void testHandleForbiddenAccess_Success() {
            // Arrange
            ForbiddenAccessException ex = new ForbiddenAccessException("Access denied");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleForbiddenAccess(ex);

            // Assert
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(403, response.getBody().getStatus());
            assertEquals("Forbidden", response.getBody().getError());
            assertEquals("Access denied", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("Configuration Exception Handler Tests")
    class ConfigurationExceptionHandlerTests {

        @Test
        @DisplayName("Should handle configuration exception and return 500 with hardcoded safe message")
        void testHandleConfigurationException_Success() {
            // Arrange — handler returns a hardcoded message regardless of ex.getMessage()
            ConfigurationException ex = new ConfigurationException("Internal details that must not leak");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConfigurationException(ex);

            // Assert
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(500, response.getBody().getStatus());
            assertEquals("Internal Server Error", response.getBody().getError());
            assertEquals("Configuration processing failed", response.getBody().getMessage());
        }

        @Test
        @DisplayName("Should handle ConfigurationException with cause and return 500")
        void testHandleConfigurationException_WithCause() {
            ConfigurationException ex = new ConfigurationException(
                    "Config parse error", new RuntimeException("root cause"));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConfigurationException(ex);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals("Configuration processing failed", response.getBody().getMessage());
            assertNotNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("IllegalArgumentException Handler Tests")
    class IllegalArgumentHandlerTests {

        @Test
        @DisplayName("Should handle IllegalArgumentException and return 400")
        void testHandleIllegalArgumentException_Success() {
            // Arrange
            IllegalArgumentException ex = new IllegalArgumentException("Invalid argument provided");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleIllegalArgument(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Bad Request", response.getBody().getError());
            assertEquals("Invalid argument provided", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("IllegalStateException Handler Tests")
    class IllegalStateHandlerTests {

        @Test
        @DisplayName("Should handle IllegalStateException and return 409 conflict")
        void testHandleIllegalStateException_Success() {
            // Arrange
            IllegalStateException ex = new IllegalStateException("Tenant already exists");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConflict(ex);

            // Assert
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(409, response.getBody().getStatus());
            assertEquals("Conflict", response.getBody().getError());
            assertEquals("Tenant already exists", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("Generic Exception Handler Tests")
    class GenericExceptionHandlerTests {

        @Test
        @DisplayName("Should handle generic exception and return 500 with hardcoded safe message")
        void testHandleException_Success() {
            // Arrange — handler returns a hardcoded message regardless of ex.getMessage()
            Exception ex = new Exception("Low-level internal detail that must not leak");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleException(ex);

            // Assert
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(500, response.getBody().getStatus());
            assertEquals("Internal Server Error", response.getBody().getError());
            assertEquals("An unexpected error occurred", response.getBody().getMessage());
        }

        @Test
        @DisplayName("Should handle NullPointerException as a generic exception")
        void testHandleNullPointerException() {
            // Arrange
            NullPointerException ex = new NullPointerException("Null reference encountered");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleException(ex);

            // Assert
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(500, response.getBody().getStatus());
        }
    }

    @Nested
    @DisplayName("LocationHierarchyStructureLockedException Handler Tests")
    class LocationHierarchyStructureLockedHandlerTests {

        @Test
        @DisplayName("Should handle LocationHierarchyStructureLockedException and return 409 with message")
        void testHandleLocationHierarchyStructureLocked() {
            // Arrange
            LocationHierarchyStructureLockedException ex =
                    new LocationHierarchyStructureLockedException("LGD", 1842L);

            // Act
            ResponseEntity<ApiErrorResponseDTO> response =
                    handler.handleLocationHierarchyStructureLocked(ex);

            // Assert
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(409, response.getBody().getStatus());
            assertEquals("Conflict", response.getBody().getError());
            assertTrue(response.getBody().getMessage().contains("LGD"));
            assertTrue(response.getBody().getMessage().contains("1842"));
        }
    }

    @Nested
    @DisplayName("RuntimeException Handler Tests")
    class RuntimeExceptionHandlerTests {

        @Test
        @DisplayName("Should handle RuntimeException and return 500 with hardcoded safe message")
        void testHandleRuntimeException_Success() {
            // Arrange — handler returns a hardcoded message regardless of ex.getMessage()
            RuntimeException ex = new RuntimeException("Schema provisioning failed: connection timeout");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleRuntimeException(ex);

            // Assert
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(500, response.getBody().getStatus());
            assertEquals("Internal Server Error", response.getBody().getError());
            assertEquals("An unexpected error occurred", response.getBody().getMessage());
        }

        @Test
        @DisplayName("Should handle IllegalStateException separately from RuntimeException handler")
        void testIllegalStateException_HandledByConflictHandler_NotRuntimeHandler() {
            // IllegalStateException is handled by handleConflict (409), not handleRuntimeException
            IllegalStateException ex = new IllegalStateException("Tenant already exists");
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleConflict(ex);

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertEquals(409, response.getBody().getStatus());
        }
    }

    @Nested
    @DisplayName("InvalidConfigKeyException Handler Tests")
    class InvalidConfigKeyHandlerTests {

        @Test
        @DisplayName("Should handle invalid config key exception and return 400")
        void testHandleInvalidConfigKey_Success() {
            // Arrange
            InvalidConfigKeyException ex = new InvalidConfigKeyException("Invalid config key: UNKNOWN_KEY");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleInvalidConfigKey(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Bad Request", response.getBody().getError());
            assertEquals("Invalid config key: UNKNOWN_KEY", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("InvalidConfigValueException Handler Tests")
    class InvalidConfigValueHandlerTests {

        @Test
        @DisplayName("Should handle invalid config value exception and return 400")
        void testHandleInvalidConfigValue_Success() {
            // Arrange
            InvalidConfigValueException ex = new InvalidConfigValueException("Invalid config value format");

            // Act
            ResponseEntity<ApiErrorResponseDTO> response = handler.handleInvalidConfigValue(ex);

            // Assert
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Bad Request", response.getBody().getError());
            assertEquals("Invalid config value format", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("HttpMessageNotReadable Handler Tests")
    class HttpMessageNotReadableHandlerTests {

        @SuppressWarnings({ "rawtypes", "unchecked" })
        @Test
        @DisplayName("Should return 400 with accepted enum values when an invalid enum value is provided")
        void testHandleMessageNotReadable_InvalidEnumValue() {
            InvalidFormatException ife = mock(InvalidFormatException.class);
            when(ife.getTargetType()).thenReturn((Class) TenantStatusEnum.class);
            when(ife.getValue()).thenReturn("INVALID_STATUS");

            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            when(ex.getCause()).thenReturn(ife);

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMessageNotReadable(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertTrue(response.getBody().getMessage().contains("INVALID_STATUS"));
            assertTrue(response.getBody().getMessage().contains("TenantStatusEnum"));
            assertTrue(response.getBody().getMessage().contains("Accepted values"));
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        @Test
        @DisplayName("Should return 400 with format error message for non-enum invalid format")
        void testHandleMessageNotReadable_NonEnumInvalidFormat() {
            InvalidFormatException ife = mock(InvalidFormatException.class);
            when(ife.getTargetType()).thenReturn((Class) Integer.class);
            when(ife.getValue()).thenReturn("abc");
            when(ife.getOriginalMessage()).thenReturn("not a valid Integer value");

            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            when(ex.getCause()).thenReturn(ife);

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMessageNotReadable(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertTrue(response.getBody().getMessage().contains("abc"));
            assertTrue(response.getBody().getMessage().contains("not a valid Integer value"));
        }

        @Test
        @DisplayName("Should return 400 with generic message for malformed body")
        void testHandleMessageNotReadable_MalformedBody() {
            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            when(ex.getCause()).thenReturn(new RuntimeException("low-level parse error"));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMessageNotReadable(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Malformed or unreadable request body", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("MissingServletRequestParameter Handler Tests")
    class MissingServletRequestParameterHandlerTests {

        @Test
        @DisplayName("Should return 400 with parameter name and type in message")
        void testHandleMissingRequestParam() {
            MissingServletRequestParameterException ex =
                    new MissingServletRequestParameterException("page", "int");

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMissingRequestParam(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertTrue(response.getBody().getMessage().contains("page"));
            assertTrue(response.getBody().getMessage().contains("int"));
        }
    }

    @Nested
    @DisplayName("HttpRequestMethodNotSupported Handler Tests")
    class HttpRequestMethodNotSupportedHandlerTests {

        @Test
        @DisplayName("Should return 405 with the unsupported method name in message")
        void testHandleMethodNotSupported() {
            HttpRequestMethodNotSupportedException ex =
                    new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMethodNotSupported(ex);

            assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(405, response.getBody().getStatus());
            assertEquals("Method Not Allowed", response.getBody().getError());
            assertTrue(response.getBody().getMessage().contains("DELETE"));
        }
    }

    @Nested
    @DisplayName("HttpMediaTypeNotSupported Handler Tests")
    class HttpMediaTypeNotSupportedHandlerTests {

        @Test
        @DisplayName("Should return 415 with content type in message")
        void testHandleMediaTypeNotSupported() {
            HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                    MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<ApiErrorResponseDTO> response = handler.handleMediaTypeNotSupported(ex);

            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(415, response.getBody().getStatus());
            assertEquals("Unsupported Media Type", response.getBody().getError());
            assertTrue(response.getBody().getMessage().contains("text/plain"));
        }
    }
}
