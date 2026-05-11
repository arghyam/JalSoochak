package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void updateReadingsReturnsBadRequestWhenCorrelationIdMissing() {
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

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
        assertEquals("correlationId must be provided", response.getBody().getData().getMessage());
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
            super(null, null, null, null, null);
            this.throwError = throwError;
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
    }
}
