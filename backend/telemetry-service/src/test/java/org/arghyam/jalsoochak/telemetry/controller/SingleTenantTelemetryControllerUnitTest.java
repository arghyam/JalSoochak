package org.arghyam.jalsoochak.telemetry.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ResetLatestReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        assertNull(response.getBody().getData().getCorrelationId());
    }

    @Test
    void assamReadingsValidationFailureReturnsRejectedResponse() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null))
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.message").value(containsString("must not be blank")));
    }

    @Test
    void assamReadingsValidationFailureReturnsRejectedResponseWithContextPath() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null))
                .build();

        mockMvc.perform(post("/jalsoochak/api/v1/telemetry/readings")
                        .contextPath("/jalsoochak")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "state_scheme_id": "30178236"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.message").value(containsString("must not be blank")));
    }

    @Test
    void assamReadingsAcceptsTrailingSlashPath() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null))
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
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null))
                .build();

        mockMvc.perform(post("/api/v1/telemetry/readings/")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "reading_url": "https://example.com/meter.jpg",
                                  "state_scheme_id": "30178236"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.message").value(containsString("must not be blank")));
    }

    @Test
    void updateReadingsAcceptsTrailingSlashPath() throws Exception {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.of(22)),
                new StubBfmReadingService(false)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TelemetryValidationExceptionHandler(null))
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
    void resetLatestReadingIsPublicAndDoesNotRequireApiKey() {
        SingleTenantTelemetryController controller = new SingleTenantTelemetryController(
                new StubGlificWebhookService(),
                new StubTelemetryApiKeyService(Optional.empty()),
                new StubBfmReadingService(false)
        );

        ResponseEntity<ReadingsApiResponse> response = controller.resetLatestReading(
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

    private static final class StubTelemetryApiKeyService extends TelemetryApiKeyService {
        private final Optional<Integer> tenantId;

        private StubTelemetryApiKeyService(Optional<Integer> tenantId) {
            super(null);
            this.tenantId = tenantId;
        }

        @Override
        public Optional<Integer> resolveTenantIdFromRawApiKey(String rawApiKey) {
            return tenantId;
        }
    }

    private static final class StubBfmReadingService extends BfmReadingService {
        private final boolean throwError;

        private StubBfmReadingService(boolean throwError) {
            super(null, null, null, null, null, null, null);
            this.throwError = throwError;
        }

        @Override
        public CreateReadingResponse updateConfirmedReading(String correlationId, String phoneNumber, BigDecimal confirmedReading) {
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
        public CreateReadingResponse resetLatestConfirmedReadingByPhone(String phoneNumber) {
            if (throwError) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request");
            }
            return CreateReadingResponse.builder()
                    .success(true)
                    .message("Latest reading reset successfully")
                    .correlationId(phoneNumber)
                    .qualityStatus("CONFIRMED")
                    .build();
        }
    }
}
