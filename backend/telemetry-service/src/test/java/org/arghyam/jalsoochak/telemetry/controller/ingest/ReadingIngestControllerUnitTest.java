package org.arghyam.jalsoochak.telemetry.controller.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.arghyam.jalsoochak.telemetry.dto.requests.CanonicalReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ResetLatestReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.MeterImageWorkflowService;
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

class ReadingIngestControllerUnitTest {

    @Test
    void canonicalReadingsReturnsOkWithCorrelationIdOnSuccess() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveReading(
                "js_valid_key",
                null,
                CanonicalReadingRequest.builder()
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
    void canonicalReadingsReturnsUnauthorizedWithoutCorrelationIdOnFailure() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveReading(
                "js_invalid_key",
                null,
                CanonicalReadingRequest.builder()
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
    void canonicalReadingsReturnsBadRequestAndSuccessFalseWhenServiceRejects() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(true),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveReading(
                "js_valid_key",
                null,
                CanonicalReadingRequest.builder()
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
    void canonicalReadingsReturnsServiceUnavailableWhenOcrTransientlyUnavailable() {
        // A transient OCR provider outage is signalled by qualityStatus=RETRY (success=false). It is not a
        // client error, so the endpoint must surface it as 503 Service Unavailable, not 400 Bad Request.
        ReadingIngestController controller = new ReadingIngestController(
                new RetryImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveReading(
                "js_valid_key",
                null,
                CanonicalReadingRequest.builder()
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
    void canonicalReadingsAcceptsPayloadWithoutPhoneNumber() throws Exception {
        // PHONE-OPTIONAL: a submission that omits phone_number must reach the service (which infers the
        // operator from the scheme) instead of being rejected by bean validation.
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
    void canonicalReadingsValidationFailureReturnsRejectedResponse() throws Exception {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
    void canonicalReadingsValidationFailureReturnsRejectedResponseWithContextPath() throws Exception {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
    void canonicalReadingsAcceptsTrailingSlashPath() throws Exception {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
    void canonicalReadingsValidationFailureOnTrailingSlashReturnsRejectedResponse() throws Exception {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                bfmReadingService
        );

        ReadingIngestController unauthenticatedController = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReadingIngestController.class);
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

    // ERROR-CODE-404: errorCodeForStatusException special-cased only 401 and 500 and let everything
    // else fall through to BAD_REQUEST, so a 404 described itself in the body as a client validation
    // error. The status line and the body disagreed, and a caller reading only the body — which is
    // what an integrator logs — could not tell "not found" from "malformed request".

    @Test
    void aResetRefusedWithNoOperatorReportsOperatorNotFoundInsteadOfBadRequest() {
        // The service answers an unknown contact and a contact belonging to another tenant with this
        // same 404, so neither confirms the contact exists elsewhere. The error code has to stay just
        // as undiscriminating: one code for both, naming the lookup, not the caller's request.
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No reading found for operator"))
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
                "js_valid_key",
                null,
                ResetLatestReadingRequest.builder().contactId("919999999999").build()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(TelemetryErrorCode.OPERATOR_NOT_FOUND, response.getBody().getData().getErrorCode());
        assertEquals("No reading found for operator", response.getBody().getData().getMessage());
    }

    @Test
    void aMissingSchemeReportsSchemeNotFound() {
        ReadingIngestController controller = new ReadingIngestController(
                new ThrowingImageWorkflowService(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "State scheme not found")),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.receiveReading(
                "js_valid_key",
                null,
                CanonicalReadingRequest.builder()
                        .readingUrl("https://example.com/meter.jpg")
                        .phoneNumber("919999999999")
                        .stateSchemeId("30178236")
                        .build()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(TelemetryErrorCode.SCHEME_NOT_FOUND, response.getBody().getData().getErrorCode());
    }

    @Test
    void aNotFoundThatNamesNeitherOperatorNorSchemeFallsBackToRequestFailed() {
        // "Reading not found" from the correlation-id correction path. There is no READING_NOT_FOUND
        // code, and inventing one would widen a contract the state IT integration already matches on —
        // so it takes the unclassified fallback rather than a code that misdescribes it.
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Reading not found"))
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(TelemetryErrorCode.REQUEST_FAILED, response.getBody().getData().getErrorCode());
    }

    @Test
    void aGenuineBadRequestStillReportsBadRequest() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmedReading must be positive"))
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(TelemetryErrorCode.BAD_REQUEST, response.getBody().getData().getErrorCode());
    }

    @Test
    void anApiKeyFailureKeepsItsOwnCodeWhateverTheStatus() {
        // The api-key reason check must keep winning over the status-based branches.
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found for API key"))
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("111"))
                        .build()
        );

        assertEquals(TelemetryErrorCode.INVALID_API_KEY, response.getBody().getData().getErrorCode());
    }

    @Test
    void canonicalReadingsMaskPhoneAtInfoAndExposeRawOnlyAtDebug() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReadingIngestController.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);

        try {
            controller.receiveReading(
                    "js_valid_key",
                    null,
                    CanonicalReadingRequest.builder()
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        BfmReadingService failing = new BfmReadingService(null, null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CreateReadingResponse updateConfirmedReading(String correlationId,
                                                                String phoneNumber,
                                                                BigDecimal confirmedReading,
                                                                Integer tenantId) {
                throw new IllegalStateException("boom");
            }
        };
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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

    /**
     * SUPPLY-PLAUSIBILITY: a correction refused on its value comes back as a REJECTED response
     * rather than an exception, and the handler has to map that to 400 itself — before this it
     * returned 200 with success=true for anything that did not throw, so an implausible correction
     * would have been reported to the caller as applied.
     */
    @Test
    void updateReadingReturnsBadRequestWhenCorrectionIsRejected() {
        BfmReadingService rejecting = new BfmReadingService(null, null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CreateReadingResponse updateConfirmedReading(String correlationId,
                                                                String phoneNumber,
                                                                BigDecimal confirmedReading,
                                                                Integer tenantId) {
                return CreateReadingResponse.builder()
                        .success(false)
                        .message("Correction rejected: this reading looks unusually high for this scheme. "
                                + "Please check the meter reading and try again.")
                        .correlationId(correlationId)
                        .meterReading(confirmedReading)
                        .lastConfirmedReading(new BigDecimal("950"))
                        .qualityStatus("REJECTED")
                        .errorCode(TelemetryErrorCode.ABNORMAL_READING)
                        .build();
            }
        };
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                rejecting
        );

        ResponseEntity<ReadingsApiResponse> response = controller.updateReading(
                "js_valid_key",
                null,
                UpdateReadingRequest.builder()
                        .correlationId("corr-123")
                        .confirmedReading(new BigDecimal("1100"))
                        .build()
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals(TelemetryErrorCode.ABNORMAL_READING, response.getBody().getData().getErrorCode());
        assertEquals("REJECTED", response.getBody().getData().getQualityStatus());
        // THRESHOLD-DISCLOSURE: the wire carries no ceiling, population or connection count. An
        // API-key holder who learns the ceiling can solve for both of its factors in two requests.
        String message = response.getBody().getData().getMessage();
        assertFalse(message.contains("75000"));
        assertFalse(message.contains("500"));
        assertFalse(message.contains("150"));
    }

    @Test
    void resetLatestReadingSetsBadRequestErrorCodeWhenContactIdMissing() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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
        BfmReadingService failing = new BfmReadingService(null, null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CreateReadingResponse resetLatestConfirmedReadingByPhone(String phoneNumber, Integer tenantId) {
                throw new IllegalStateException("boom");
            }
        };
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
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

    private static final class StubImageWorkflowService extends MeterImageWorkflowService {
        private final boolean rejected;
        private int processCanonicalReadingCount;

        private StubImageWorkflowService() {
            this(false);
        }

        private StubImageWorkflowService(boolean rejected) {
            super(null, null, null, null, null, null, null, null);
            this.rejected = rejected;
        }

        @Override
        public CreateReadingResponse processCanonicalReading(CanonicalReadingRequest request, Integer preferredTenantId) {
            processCanonicalReadingCount++;
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

    private static final class ThrowingImageWorkflowService extends MeterImageWorkflowService {
        private final ResponseStatusException failure;

        private ThrowingImageWorkflowService(ResponseStatusException failure) {
            super(null, null, null, null, null, null, null, null);
            this.failure = failure;
        }

        @Override
        public CreateReadingResponse processCanonicalReading(CanonicalReadingRequest request, Integer preferredTenantId) {
            throw failure;
        }
    }

    private static final class RetryImageWorkflowService extends MeterImageWorkflowService {
        private RetryImageWorkflowService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public CreateReadingResponse processCanonicalReading(CanonicalReadingRequest request, Integer preferredTenantId) {
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
        private final ResponseStatusException failure;
        private String lastCorrelationId;
        private String lastPhoneNumber;
        private Integer lastTenantId;
        private boolean resetCalled;
        private Integer lastResetTenantId;

        private StubBfmReadingService(boolean throwError) {
            this(throwError, null);
        }

        /** Lets a test choose the exact status and reason the service rejects with. */
        private StubBfmReadingService(ResponseStatusException failure) {
            this(true, failure);
        }

        private StubBfmReadingService(boolean throwError, ResponseStatusException failure) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
            this.throwError = throwError;
            this.failure = failure;
        }

        private ResponseStatusException rejection() {
            return failure != null ? failure : new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request");
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
                throw rejection();
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
                throw rejection();
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
                throw rejection();
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

    @Test
    void canonicalReadingsRejectsAnUnsupportedChannel() {
        StubImageWorkflowService imageWorkflow = new StubImageWorkflowService();
        ReadingIngestController controller = new ReadingIngestController(
                imageWorkflow,
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response =
                controller.receiveReading("js_valid_key", null, canonicalReadingWithChannel("BFMX"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(TelemetryErrorCode.CHANNEL_NOT_SUPPORTED, response.getBody().getData().getErrorCode());
        assertEquals("REJECTED", response.getBody().getData().getQualityStatus());
        // The allowed set is published in the message so an integrator can fix the call without
        // going back to the specification.
        assertTrue(response.getBody().getData().getMessage().contains("BFM, ELM, PDU, IOT, MAN"));
        // Refused before any processing: nothing is stored, nothing is published.
        assertEquals(0, imageWorkflow.processCanonicalReadingCount);
    }

    @Test
    void canonicalReadingsDoNotEchoTheRejectedChannelBack() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response =
                controller.receiveReading("js_valid_key", null,
                        canonicalReadingWithChannel("<script>alert(1)</script>"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().getData().getMessage().contains("script"));
    }

    @Test
    void canonicalReadingsAcceptADeclaredChannelInAnyCase() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response =
                controller.receiveReading("js_valid_key", null, canonicalReadingWithChannel("  pdu  "));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
    }

    @Test
    void canonicalReadingsAcceptASubmissionThatDeclaresNoChannel() {
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );

        assertEquals(HttpStatus.OK,
                controller.receiveReading("js_valid_key", null, canonicalReadingWithChannel(null))
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.receiveReading("js_valid_key", null, canonicalReadingWithChannel("   "))
                        .getStatusCode());
    }

    @Test
    void canonicalReadingsCheckTheApiKeyBeforeTheChannel() {
        // An unauthenticated caller must not learn which channels exist by probing the field.
        ReadingIngestController controller = new ReadingIngestController(
                new StubImageWorkflowService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response =
                controller.receiveReading("js_invalid_key", null, canonicalReadingWithChannel("BFMX"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(TelemetryErrorCode.INVALID_API_KEY, response.getBody().getData().getErrorCode());
    }

    private static CanonicalReadingRequest canonicalReadingWithChannel(String channel) {
        return CanonicalReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber("919999999999")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                .channel(channel)
                .build();
    }
}
