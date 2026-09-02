package org.arghyam.jalsoochak.telemetry.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ResetLatestReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.arghyam.jalsoochak.telemetry.validation.ReadingUrlTestValidation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SingleTenantTelemetryControllerUnitTest {

    @Test
    void assamReadingsReturnsOkWithCorrelationIdOnSuccess() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveAssamReading(
                "js_valid_key",
                null,
                AssamReadingRequest.builder()
                        .readingUrl("https://example.com/meter.jpg")
                        .confirmedReading(new BigDecimal("123.4"))
                        .stateSchemeId("30178236")
                        .centreSchemeId("30244993")
                        .phoneNumber("919999999999")
                        .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertNotNull(response.getBody().getData());
        assertEquals("assam-reading-ok", response.getBody().getData().getMessage());
        assertEquals("corr-hidden", response.getBody().getData().getCorrelationId());
    }

    @Test
    void assamReadingsReturnsUnauthorizedWithoutCorrelationIdOnFailure() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveAssamReading(
                "js_invalid_key",
                null,
                AssamReadingRequest.builder()
                        .readingUrl("https://example.com/meter.jpg")
                        .phoneNumber("919999999999")
                        .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                        .build()
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
        assertNotNull(response.getBody().getData());
        assertNull(response.getBody().getData().getCorrelationId());
    }

    @Test
    void assamReadingsReturnsBadRequestAndSuccessFalseWhenServiceRejects() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(true),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveAssamReading(
                "js_valid_key",
                null,
                AssamReadingRequest.builder()
                        .readingUrl("https://example.com/meter.jpg")
                        .phoneNumber("919999999999")
                        .stateSchemeId("30178236")
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
        assertNotNull(response.getBody().getData());
        assertEquals("REJECTED", response.getBody().getData().getQualityStatus());
        assertEquals(TelemetryErrorCode.OPERATOR_NOT_MAPPED_TO_SCHEME, response.getBody().getData().getErrorCode());
        assertNull(response.getBody().getData().getCorrelationId());
    }

    @Test
    void assamReadingsReturnsServiceUnavailableWhenOcrTransientlyUnavailable() {
        // A transient FlowVision outage is signalled by qualityStatus=RETRY (success=false). It is not a
        // client error, so the endpoint must surface it as 503 Service Unavailable, not 400 Bad Request.
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new RetryGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveAssamReading(
                "js_valid_key",
                null,
                AssamReadingRequest.builder()
                        .readingUrl("https://example.com/meter.jpg")
                        .phoneNumber("919999999999")
                        .stateSchemeId("30178236")
                        .build()
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
        assertNotNull(response.getBody().getData());
        assertEquals("RETRY", response.getBody().getData().getQualityStatus());
        assertEquals("Meter reading service is temporarily unavailable. Please try again shortly.",
                response.getBody().getData().getMessage());
        assertNull(response.getBody().getData().getErrorCode());
        assertNull(response.getBody().getData().getCorrelationId());
    }

    @Test
    void assamReadingsAcceptsPayloadWithoutPhoneNumber() throws Exception {
        // PHONE-OPTIONAL: a submission that omits phone_number must reach the service (which infers the
        // operator from the scheme) instead of being rejected by bean validation.
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null, null))
                .build();

        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "state_scheme_id": "30178236"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void assamReadingsValidationFailureReturnsRejectedResponse() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null, null))
                .build();

        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "phone_number": "919999999999"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message")
                        .value(containsString("Either stateSchemeId or centreSchemeId must be provided")));
    }

    @Test
    void assamReadingsValidationFailureReturnsRejectedResponseWithContextPath() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null, null))
                .build();

        mockMvc.perform(post("/jalsoochak/api/v1/telemetry/readings")
                        .contextPath("/jalsoochak")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "phone_number": "919999999999"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message")
                        .value(containsString("Either stateSchemeId or centreSchemeId must be provided")));
    }

    @Test
    void assamReadingsAcceptsTrailingSlashPath() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null, null))
                .build();

        mockMvc.perform(post("/api/v1/telemetry/readings/")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "state_scheme_id": "30178236",
                                  "phone_number": "919999999999"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void assamReadingsValidationFailureOnTrailingSlashReturnsRejectedResponse() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null, null))
                .build();

        mockMvc.perform(post("/api/v1/telemetry/readings/")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "phone_number": "919999999999"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message")
                        .value(containsString("Either stateSchemeId or centreSchemeId must be provided")));
    }

    @Test
    void updateReadingsAcceptsTrailingSlashPath() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null, null))
                .build();

        mockMvc.perform(put("/api/v1/telemetry/readings/")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "correlation_id": "corr-123",
                                  "phone_number": "919999999999",
                                  "confirmed_reading": 111
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.correlationId").value("corr-123"));
    }

    @Test
    void updateReadingsReturnsBadRequestWhenConfirmedReadingMissing() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .imageId("img-1")
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
        assertNull(response.getBody().getData().getCorrelationId());
    }

    @Test
    void updateReadingsReturnsUnauthorizedWhenApiKeyInvalid() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_invalid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
        assertNull(response.getBody().getData().getCorrelationId());
    }

    @Test
    void updateReadingsUsesPhoneNumberWhenCorrelationIdMissing() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .phoneNumber("919999999999")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertEquals("919999999999", response.getBody().getData().getCorrelationId());
    }

    @Test
    void updateReadingsUsesCorrelationIdWhenProvided() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .phoneNumber("919999999999")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertEquals("corr-123", response.getBody().getData().getCorrelationId());
    }

    @Test
    void resetLatestReadingRejectsARequestWithNoApiKey() {
        // Regression test for the reported finding: this route used to process an unauthenticated
        // request and destroy the reading, returning 200 whether or not a key was supplied.
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                null,
                null,
                ResetLatestReadingRequest.builder()
                        .contactId("919999999999")
                        .build()
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(TelemetryErrorCode.INVALID_API_KEY, response.getBody().getData().getErrorCode());
        assertFalse(bfmReadingService.resetWasCalled(), "the reset must not run for an unauthenticated caller");
    }

    @Test
    void resetLatestReadingRejectsAnInvalidApiKey() {
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                "js_invalid_key",
                null,
                ResetLatestReadingRequest.builder()
                        .contactId("919999999999")
                        .build()
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse(bfmReadingService.resetWasCalled());
    }

    @Test
    void resetLatestReadingAcceptsAValidApiKeyAndScopesTheResetToItsTenant() {
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                "js_valid_key",
                null,
                ResetLatestReadingRequest.builder()
                        .contactId("919999999999")
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertEquals("Latest reading reset successfully", response.getBody().getData().getMessage());
        assertEquals("CONFIRMED", response.getBody().getData().getQualityStatus());
        assertEquals(22, bfmReadingService.lastResetTenantId);
    }

    @Test
    void resetLatestReadingTrustsTheTenantAlreadyResolvedByTheFilter() {
        // The filter authenticates first and publishes the tenant; the handler must not re-reject a
        // request the filter already accepted, and must not hash the key a second time.
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        StubTelemetryApiKeyService apiKeyService = new StubTelemetryApiKeyService(Optional.empty());
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                apiKeyService,
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                "js_valid_key",
                31,
                ResetLatestReadingRequest.builder()
                        .contactId("919999999999")
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(31, bfmReadingService.lastResetTenantId);
        assertEquals(0, apiKeyService.resolveCount, "the key was already resolved by the filter");
    }

    @Test
    void resetLatestReadingAuditsTheDestroyedValueAndTheRefusals() {
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                bfmReadingService
        );

        SingleTenantTelemetryController unauthenticatedController = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SingleTenantTelemetryController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.resetLatestReading("js_valid_key", null,
                    ResetLatestReadingRequest.builder().contactId("919999999999").build());
            unauthenticatedController.resetLatestReading(null, null,
                    ResetLatestReadingRequest.builder().contactId("919999999999").build());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String accepted = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("reading_reset") && m.contains("status=SUCCESS"))
                .findFirst()
                .orElse(null);
        assertNotNull(accepted, "an accepted reset must be audited");
        assertTrue(accepted.contains("tenantId=22"));
        assertTrue(accepted.contains("previousReading=1450"), "the audit line must record what was destroyed");
        assertTrue(accepted.contains("phone=****9999"));
        assertFalse(accepted.contains("919999999999"), "raw phone numbers must not reach INFO logs");

        assertTrue(appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .anyMatch(m -> m.startsWith("reading_reset") && m.contains("status=REJECTED")),
                "a refused reset must be audited too, so probing is detectable");
    }

    @Test
    void assamReadingsMaskPhoneAtInfoAndExposeRawOnlyAtDebug() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SingleTenantTelemetryController.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);

        try {
            controller.receiveAssamReading(
                    "js_valid_key",
                    null,
                    AssamReadingRequest.builder()
                            .readingUrl("https://example.com/meter.jpg")
                            .confirmedReading(new BigDecimal("123.4"))
                            .stateSchemeId("30178236")
                            .phoneNumber("919999912345")
                            .build()
            );
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        boolean rawPhoneAtInfo = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .anyMatch(event -> event.getFormattedMessage().contains("919999912345"));
        assertFalse(rawPhoneAtInfo, "Raw phone number must never appear in INFO logs");

        boolean maskedPhoneAtInfo = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .anyMatch(event -> event.getFormattedMessage().contains("****2345"));
        assertTrue(maskedPhoneAtInfo, "Masked phone number should appear in INFO logs");

        boolean rawPhoneAtDebug = appender.list.stream()
                .filter(event -> event.getLevel() == Level.DEBUG)
                .anyMatch(event -> event.getFormattedMessage().contains("919999912345"));
        assertTrue(rawPhoneAtDebug, "Raw phone number should be available at DEBUG level");
    }

    @Test
    void statusExceptionErrorCodeMappingsArePinned() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        assertEquals(
                TelemetryErrorCode.INVALID_API_KEY,
                ReflectionTestUtils.invokeMethod(
                        controller,
                        "errorCodeForStatusException",
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized")
                )
        );
        assertEquals(
                TelemetryErrorCode.SERVER_ERROR,
                ReflectionTestUtils.invokeMethod(
                        controller,
                        "errorCodeForStatusException",
                        new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed")
                )
        );
        assertEquals(
                TelemetryErrorCode.INVALID_API_KEY,
                ReflectionTestUtils.invokeMethod(
                        controller,
                        "errorCodeForStatusException",
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid API key")
                )
        );
        assertEquals(
                TelemetryErrorCode.BAD_REQUEST,
                ReflectionTestUtils.invokeMethod(
                        controller,
                        "errorCodeForStatusException",
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload")
                )
        );
        assertEquals(
                TelemetryErrorCode.REQUEST_FAILED,
                ReflectionTestUtils.invokeMethod(
                        controller,
                        "errorCodeForStatusException",
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, " ")
                )
        );
    }

    @Test
    void updateReadingSetsInvalidApiKeyErrorCodeWhenApiKeyInvalid() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_invalid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .phoneNumber("919999999999")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(TelemetryErrorCode.INVALID_API_KEY, response.getBody().getData().getErrorCode());
    }

    @Test
    void updateReadingSetsBadRequestErrorCodeWhenBothIdentifiersMissing() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .imageId("img-1")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(TelemetryErrorCode.BAD_REQUEST, response.getBody().getData().getErrorCode());
        assertEquals(
                "Either correlationId or phoneNumber must be provided",
                response.getBody().getData().getMessage()
        );
    }

    @Test
    void updateReadingSucceedsWithCorrelationIdAndNoPhoneNumber() {
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertEquals("corr-123", response.getBody().getData().getCorrelationId());
        assertEquals("corr-123", bfmReadingService.lastCorrelationId);
        assertNull(bfmReadingService.lastPhoneNumber);
    }

    @Test
    void updateReadingPassesApiKeyTenantIdToService() {
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(22, bfmReadingService.lastTenantId);
    }

    @Test
    void updateReadingStillAcceptsPhoneNumberWithoutCorrelationId() {
        StubBfmReadingService bfmReadingService = new StubBfmReadingService(false);
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                bfmReadingService
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .phoneNumber("919999999999")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(bfmReadingService.lastCorrelationId);
        assertEquals("919999999999", bfmReadingService.lastPhoneNumber);
    }

    @Test
    void updateReadingSetsBadRequestErrorCodeWhenConfirmedReadingMissing() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .phoneNumber("919999999999")
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(TelemetryErrorCode.BAD_REQUEST, response.getBody().getData().getErrorCode());
    }

    @Test
    void updateReadingSetsBadRequestErrorCodeWhenServiceRejects() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(true)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .phoneNumber("919999999999")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(TelemetryErrorCode.BAD_REQUEST, response.getBody().getData().getErrorCode());
    }

    @Test
    void updateReadingSetsProcessingFailedErrorCodeOnUnexpectedError() {
        BfmReadingService failing = new BfmReadingService(null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CreateReadingResponse updateConfirmedReading(String correlationId,
                                                                String phoneNumber,
                                                                BigDecimal confirmedReading,
                                                                Integer tenantId) {
                throw new IllegalStateException("boom");
            }
        };
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                failing
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .phoneNumber("919999999999")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(TelemetryErrorCode.PROCESSING_FAILED, response.getBody().getData().getErrorCode());
    }

    @Test
    void resetLatestReadingSetsBadRequestErrorCodeWhenContactIdMissing() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                "js_valid_key",
                null,
                ResetLatestReadingRequest.builder().build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(TelemetryErrorCode.BAD_REQUEST, response.getBody().getData().getErrorCode());
    }

    @Test
    void resetLatestReadingSetsProcessingFailedErrorCodeOnUnexpectedError() {
        BfmReadingService failing = new BfmReadingService(null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CreateReadingResponse resetLatestConfirmedReadingByPhone(String phoneNumber, Integer tenantId) {
                throw new IllegalStateException("boom");
            }
        };
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                failing
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                "js_valid_key",
                null,
                ResetLatestReadingRequest.builder().contactId("919999999999").build()
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(TelemetryErrorCode.PROCESSING_FAILED, response.getBody().getData().getErrorCode());
    }

    private static final class StubGlificWebhookService extends GlificWebhookService {
        private final boolean rejected;

        private StubGlificWebhookService() {
            this(false);
        }

        private StubGlificWebhookService(boolean rejected) {
            super(null, null, null, null);
            this.rejected = rejected;
        }

        @Override
        public CreateReadingResponse processAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
            if (rejected) {
                return CreateReadingResponse.builder()
                        .success(false)
                        .qualityStatus("REJECTED")
                        .errorCode(TelemetryErrorCode.OPERATOR_NOT_MAPPED_TO_SCHEME)
                        .message("Operator is not mapped to the provided state or centre scheme")
                        .correlationId("corr-rejected")
                        .build();
            }
            return CreateReadingResponse.builder()
                    .success(true)
                    .message("assam-reading-ok")
                    .correlationId("corr-hidden")
                    .build();
        }
    }

    private static final class RetryGlificWebhookService extends GlificWebhookService {
        private RetryGlificWebhookService() {
            super(null, null, null, null);
        }

        @Override
        public CreateReadingResponse processAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
            return CreateReadingResponse.builder()
                    .success(false)
                    .qualityStatus("RETRY")
                    .message("Meter reading service is temporarily unavailable. Please try again shortly.")
                    .correlationId("corr-retry")
                    .build();
        }
    }

    private static final class StubTelemetryApiKeyService extends TelemetryApiKeyService {
        private final Optional<Integer> tenantId;
        private int resolveCount;

        private StubTelemetryApiKeyService(Optional<Integer> tenantId) {
            super(null);
            this.tenantId = tenantId;
        }

        @Override
        public Optional<Integer> resolveTenantIdFromRawApiKey(String rawApiKey) {
            resolveCount++;
            return tenantId;
        }
    }

    private static final class StubBfmReadingService extends BfmReadingService {
        private final boolean throwError;
        private String lastCorrelationId;
        private String lastPhoneNumber;
        private Integer lastTenantId;
        private boolean resetCalled;
        private Integer lastResetTenantId;

        private StubBfmReadingService(boolean throwError) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.throwError = throwError;
        }

        @Override
        public CreateReadingResponse updateConfirmedReading(String correlationId,
                                                            String phoneNumber,
                                                            BigDecimal confirmedReading,
                                                            Integer tenantId) {
            this.lastCorrelationId = correlationId;
            this.lastPhoneNumber = phoneNumber;
            this.lastTenantId = tenantId;
            if (throwError) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request");
            }
            return CreateReadingResponse.builder()
                    .success(true)
                    .message("Reading updated successfully")
                    .correlationId(correlationId != null ? correlationId : phoneNumber)
                    .meterReading(confirmedReading)
                    .qualityStatus("CONFIRMED")
                    .build();
        }

        @Override
        public CreateReadingResponse updateConfirmedReading(String correlationId, BigDecimal confirmedReading) {
            if (throwError) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request");
            }
            return CreateReadingResponse.builder()
                    .success(true)
                    .message("Reading updated successfully")
                    .correlationId(correlationId)
                    .meterReading(confirmedReading)
                    .qualityStatus("CONFIRMED")
                    .build();
        }

        @Override
        public CreateReadingResponse resetLatestConfirmedReadingByPhone(String phoneNumber, Integer tenantId) {
            this.resetCalled = true;
            this.lastResetTenantId = tenantId;
            if (throwError) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request");
            }
            return CreateReadingResponse.builder()
                    .success(true)
                    .message("Latest reading reset successfully")
                    // A real correlationId is a UUID, never the phone number: the audit assertions
                    // check that no raw phone reaches an INFO line, and echoing it here would hide
                    // exactly the leak they exist to catch.
                    .correlationId("corr-reset-1")
                    .meterReading(BigDecimal.ZERO)
                    .lastConfirmedReading(new BigDecimal("1450"))
                    .qualityStatus("CONFIRMED")
                    .build();
        }

        private boolean resetWasCalled() {
            return resetCalled;
        }
    }
}
