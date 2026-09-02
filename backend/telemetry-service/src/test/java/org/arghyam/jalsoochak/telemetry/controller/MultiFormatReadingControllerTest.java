package org.arghyam.jalsoochak.telemetry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.ingest.CanonicalReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapperRegistry;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.arghyam.jalsoochak.telemetry.validation.ReadingUrlTestValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MultiFormatReadingControllerTest {

    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();
    // The reading-url constraint takes its policy through the constructor, so it needs the same
    // factory wiring the container provides — see ReadingUrlTestValidation.
    private static final Validator VALIDATOR = ReadingUrlTestValidation.validator();

    private static final String CANONICAL_BODY = """
            {
              "reading_url": "https://example.com/meter.jpg",
              "confirmed_reading": 123.4,
              "state_scheme_id": "30178236",
              "centre_scheme_id": "30244993",
              "phone_number": "91XXXXXXXXXX",
              "reading_date_time": "2026-04-23T07:38:22.031Z"
            }
            """;

    @Mock
    private GlificWebhookService webhook;
    @Mock
    private TelemetryApiKeyService apiKeyService;

    private MockMvc mockMvc() {
        ReadingRequestMapperRegistry registry = new ReadingRequestMapperRegistry(List.of(
                new CanonicalReadingRequestMapper(OBJECT_MAPPER),
                new StateXMapper(),
                new ThrowingMapper(),
                new NullReturningMapper()));
        MultiFormatReadingController controller =
                new MultiFormatReadingController(registry, apiKeyService, webhook, VALIDATOR);
        return MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .build();
    }

    @Test
    void canonicalFormatHappyPathReturns200() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(22));
        when(webhook.processAssamReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(true).message("ok").correlationId("corr-1").build());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.correlationId").value("corr-1"));

        ArgumentCaptor<AssamReadingRequest> requestCaptor = ArgumentCaptor.forClass(AssamReadingRequest.class);
        ArgumentCaptor<Integer> tenantCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(webhook).processAssamReading(requestCaptor.capture(), tenantCaptor.capture());
        assertEquals(22, tenantCaptor.getValue());
        assertEquals("91XXXXXXXXXX", requestCaptor.getValue().getPhoneNumber());
        assertEquals("30178236", requestCaptor.getValue().getStateSchemeId());
    }

    @Test
    void customFormatIsMappedToCanonicalThenProcessed() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(7));
        when(webhook.processAssamReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(true).message("ok").correlationId("corr-x").build());

        // A completely different wire shape from an imaginary "stateX" IT system.
        String stateXBody = """
                { "msisdn": "91YYYYYYYYYY", "scheme": "SX-42", "value": 500.0 }
                """;

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/stateX")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(stateXBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Proves the core pipeline received a canonical request without any change to core code.
        ArgumentCaptor<AssamReadingRequest> requestCaptor = ArgumentCaptor.forClass(AssamReadingRequest.class);
        ArgumentCaptor<Integer> tenantCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(webhook).processAssamReading(requestCaptor.capture(), tenantCaptor.capture());
        assertEquals(7, tenantCaptor.getValue());
        assertEquals("91YYYYYYYYYY", requestCaptor.getValue().getPhoneNumber());
        assertEquals("SX-42", requestCaptor.getValue().getStateSchemeId());
    }

    @Test
    void unknownFormatForAuthenticatedRequestReturns400() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/martian")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));

        verifyNoInteractions(webhook);
    }

    @Test
    void unauthenticatedRequestReturns401RegardlessOfFormat() throws Exception {
        // Auth is checked before format, so even an unknown format returns 401 (never leaks 400).
        when(apiKeyService.resolveTenantIdFromRawApiKey("bad")).thenReturn(Optional.empty());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/martian")
                        .header("X-Api-Key", "bad")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_API_KEY"));

        verifyNoInteractions(webhook);
    }

    @Test
    void invalidApiKeyReturns401() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("bad")).thenReturn(Optional.empty());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "bad")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_API_KEY"));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));

        // Missing both scheme id and reading -> violates the DTO @AssertTrue constraints.
        String invalidBody = """
                { "phone_number": "91XXXXXXXXXX" }
                """;

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"));

        verifyNoInteractions(webhook);
    }

    @Test
    void mapperThatThrowsReturns400MalformedRequest() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/throwing")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));

        verifyNoInteractions(webhook);
    }

    @Test
    void mapperThatReturnsNullReturns400MalformedRequest() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/nullmap")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));

        verifyNoInteractions(webhook);
    }

    @Test
    void rejectedProcessingReturns400() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));
        when(webhook.processAssamReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(false).qualityStatus("REJECTED").message("nope").correlationId("c").build());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void transientRetryReturns503() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));
        when(webhook.processAssamReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(false).qualityStatus("RETRY").message("try later").correlationId("c").build());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        // The body fails to parse before the handler body runs, so no collaborator is invoked.
        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));

        verifyNoInteractions(webhook, apiKeyService);
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

    /** Mapper that fails while translating the payload (exercises the catch branch). */
    private static final class ThrowingMapper implements ReadingRequestMapper {
        @Override
        public String format() {
            return "throwing";
        }

        @Override
        public AssamReadingRequest map(JsonNode rawBody) {
            throw new IllegalArgumentException("bad payload");
        }
    }

    /** Mapper that yields no reading (exercises the null-result branch). */
    private static final class NullReturningMapper implements ReadingRequestMapper {
        @Override
        public String format() {
            return "nullmap";
        }

        @Override
        public AssamReadingRequest map(JsonNode rawBody) {
            return null;
        }
    }
}
