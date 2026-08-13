package org.arghyam.jalsoochak.telemetry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.ingest.CanonicalReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapperRegistry;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MultiFormatReadingControllerTest {

    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String CANONICAL_BODY = """
            {
              "reading_url": "https://example.com/meter.jpg",
              "confirmed_reading": 123.4,
              "state_scheme_id": "30178236",
              "centre_scheme_id": "30244993",
              "phone_number": "919999999999",
              "reading_date_time": "2026-04-23T07:38:22.031Z"
            }
            """;

    private MockMvc mockMvc(StubTelemetryApiKeyService apiKeyService, StubWebhook webhook) {
        ReadingRequestMapperRegistry registry = new ReadingRequestMapperRegistry(
                List.of(new CanonicalReadingRequestMapper(OBJECT_MAPPER), new StateXMapper()));
        MultiFormatReadingController controller =
                new MultiFormatReadingController(registry, apiKeyService, webhook, VALIDATOR);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void canonicalFormatHappyPathReturns200() throws Exception {
        StubWebhook webhook = new StubWebhook();
        webhook.response = CreateReadingResponse.builder()
                .success(true).message("ok").correlationId("corr-1").build();

        mockMvc(new StubTelemetryApiKeyService(Optional.of(22)), webhook)
                .perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.correlationId").value("corr-1"));

        assertEquals(22, webhook.capturedTenant);
        assertEquals("919999999999", webhook.captured.getPhoneNumber());
        assertEquals("30178236", webhook.captured.getStateSchemeId());
    }

    @Test
    void customFormatIsMappedToCanonicalThenProcessed() throws Exception {
        StubWebhook webhook = new StubWebhook();
        webhook.response = CreateReadingResponse.builder()
                .success(true).message("ok").correlationId("corr-x").build();

        // A completely different wire shape from an imaginary "stateX" IT system.
        String stateXBody = """
                { "msisdn": "918888888888", "scheme": "SX-42", "value": 500.0 }
                """;

        mockMvc(new StubTelemetryApiKeyService(Optional.of(7)), webhook)
                .perform(post("/api/v1/telemetry/readings/formats/stateX")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(stateXBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Proves the core pipeline received a canonical request without any change to core code.
        assertEquals("918888888888", webhook.captured.getPhoneNumber());
        assertEquals("SX-42", webhook.captured.getStateSchemeId());
    }

    @Test
    void unknownFormatReturns400() throws Exception {
        mockMvc(new StubTelemetryApiKeyService(Optional.of(1)), new StubWebhook())
                .perform(post("/api/v1/telemetry/readings/formats/martian")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void invalidApiKeyReturns401() throws Exception {
        mockMvc(new StubTelemetryApiKeyService(Optional.empty()), new StubWebhook())
                .perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "bad")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_API_KEY"));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        // Missing both scheme id and reading -> violates the DTO @AssertTrue constraints.
        String invalidBody = """
                { "phone_number": "919999999999" }
                """;

        mockMvc(new StubTelemetryApiKeyService(Optional.of(1)), new StubWebhook())
                .perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectedProcessingReturns400() throws Exception {
        StubWebhook webhook = new StubWebhook();
        webhook.response = CreateReadingResponse.builder()
                .success(false).qualityStatus("REJECTED").message("nope").correlationId("c").build();

        mockMvc(new StubTelemetryApiKeyService(Optional.of(1)), webhook)
                .perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void transientRetryReturns503() throws Exception {
        StubWebhook webhook = new StubWebhook();
        webhook.response = CreateReadingResponse.builder()
                .success(false).qualityStatus("RETRY").message("try later").correlationId("c").build();

        mockMvc(new StubTelemetryApiKeyService(Optional.of(1)), webhook)
                .perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc(new StubTelemetryApiKeyService(Optional.of(1)), new StubWebhook())
                .perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));
    }

    // ---- test doubles ----

    /** Example mapper for a non-conforming state's payload; the whole point of the seam. */
    private static final class StateXMapper implements ReadingRequestMapper {
        @Override
        public String format() {
            return "stateX";
        }

        @Override
        public AssamReadingRequest map(JsonNode rawBody) {
            return AssamReadingRequest.builder()
                    .phoneNumber(rawBody.path("msisdn").asText(null))
                    .stateSchemeId(rawBody.path("scheme").asText(null))
                    .confirmedReading(rawBody.has("value") ? rawBody.get("value").decimalValue() : null)
                    .build();
        }
    }

    private static final class StubWebhook extends GlificWebhookService {
        private AssamReadingRequest captured;
        private Integer capturedTenant;
        private CreateReadingResponse response;

        private StubWebhook() {
            super(null, null, null, null);
        }

        @Override
        public CreateReadingResponse processAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
            this.captured = request;
            this.capturedTenant = preferredTenantId;
            return response;
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
}
