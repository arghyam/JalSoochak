package org.arghyam.jalsoochak.telemetry.controller.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.dto.requests.CanonicalReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.ingest.CanonicalReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapperRegistry;
import org.arghyam.jalsoochak.telemetry.service.MeterImageWorkflowService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.arghyam.jalsoochak.telemetry.validation.ReadingUrlTestValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
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
    private MeterImageWorkflowService imageWorkflowService;
    @Mock
    private TelemetryApiKeyService apiKeyService;

    private MockMvc mockMvc() {
        ReadingRequestMapperRegistry registry = new ReadingRequestMapperRegistry(List.of(
                new CanonicalReadingRequestMapper(OBJECT_MAPPER),
                new StateXMapper(),
                new ThrowingMapper(),
                new NullReturningMapper()));
        MultiFormatReadingController controller =
                new MultiFormatReadingController(registry, apiKeyService, imageWorkflowService, VALIDATOR);
        return MockMvcBuilders.standaloneSetup(controller)
                .setValidator(ReadingUrlTestValidation.springValidator())
                .build();
    }

    @Test
    void canonicalFormatHappyPathReturns200() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(22));
        when(imageWorkflowService.processCanonicalReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(true).message("ok").correlationId("corr-1").build());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.correlationId").value("corr-1"));

        ArgumentCaptor<CanonicalReadingRequest> requestCaptor = ArgumentCaptor.forClass(CanonicalReadingRequest.class);
        ArgumentCaptor<Integer> tenantCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(imageWorkflowService).processCanonicalReading(requestCaptor.capture(), tenantCaptor.capture());
        assertEquals(22, tenantCaptor.getValue());
        assertEquals("91XXXXXXXXXX", requestCaptor.getValue().getPhoneNumber());
        assertEquals("30178236", requestCaptor.getValue().getStateSchemeId());
    }

    @Test
    void customFormatIsMappedToCanonicalThenProcessed() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(7));
        when(imageWorkflowService.processCanonicalReading(any(), any())).thenReturn(CreateReadingResponse.builder()
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
        ArgumentCaptor<CanonicalReadingRequest> requestCaptor = ArgumentCaptor.forClass(CanonicalReadingRequest.class);
        ArgumentCaptor<Integer> tenantCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(imageWorkflowService).processCanonicalReading(requestCaptor.capture(), tenantCaptor.capture());
        assertEquals(7, tenantCaptor.getValue());
        assertEquals("91YYYYYYYYYY", requestCaptor.getValue().getPhoneNumber());
        assertEquals("SX-42", requestCaptor.getValue().getStateSchemeId());
    }

    @Test
    void implausibleSupplyRejectionReachesTheWireAs400AbnormalReading() throws Exception {
        // SUPPLY-PLAUSIBILITY: the contract an integrating client branches on. ABNORMAL_READING is
        // deliberately vaguer than the internal IMPLAUSIBLE_WATER_SUPPLY anomaly it comes from.
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(22));
        when(imageWorkflowService.processCanonicalReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(false)
                .qualityStatus("REJECTED")
                .errorCode(TelemetryErrorCode.ABNORMAL_READING)
                .message("Reading rejected: this reading looks unusually high for this scheme. "
                        + "Please check the meter reading and try again.")
                .meterReading(new BigDecimal("1100"))
                .lastConfirmedReading(new BigDecimal("900"))
                .build());

        String body = mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("ABNORMAL_READING"))
                .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                .andReturn().getResponse().getContentAsString();

        // THRESHOLD-DISCLOSURE: nothing in the body names the ceiling (75000), the population (500),
        // the connection count (100) or the per-person limit (150). Two submissions and any of those
        // would give away the rest.
        assertEquals(false, body.contains("75000"));
        assertEquals(false, body.contains("\"population\""));
        assertEquals(false, body.contains("fhtc"));
        assertEquals(false, body.contains("implausible"));
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

        verifyNoInteractions(imageWorkflowService);
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

        verifyNoInteractions(imageWorkflowService);
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

        verifyNoInteractions(imageWorkflowService);
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

        verifyNoInteractions(imageWorkflowService);
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

        verifyNoInteractions(imageWorkflowService);
    }

    @Test
    void rejectedProcessingReturns400() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(1));
        when(imageWorkflowService.processCanonicalReading(any(), any())).thenReturn(CreateReadingResponse.builder()
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
        when(imageWorkflowService.processCanonicalReading(any(), any())).thenReturn(CreateReadingResponse.builder()
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

        verifyNoInteractions(imageWorkflowService, apiKeyService);
    }

    // ---- test doubles ----

    /** Example mapper for a non-conforming state's payload; the whole point of the seam. */
    private static final class StateXMapper implements ReadingRequestMapper {
        @Override
        public String format() {
            return "stateX";
        }

        @Override
        public CanonicalReadingRequest map(JsonNode rawBody) {
            return CanonicalReadingRequest.builder()
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
        public CanonicalReadingRequest map(JsonNode rawBody) {
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
        public CanonicalReadingRequest map(JsonNode rawBody) {
            return null;
        }
    }

    private static final String CANONICAL_BODY_WITH_CHANNEL = """
            {
              "reading_url": "https://example.com/meter.jpg",
              "confirmed_reading": 123.4,
              "state_scheme_id": "30178236",
              "phone_number": "91XXXXXXXXXX",
              "channel": "%s"
            }
            """;

    @Test
    void unsupportedChannelIsRejectedWithItsOwnErrorCode() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(22));

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY_WITH_CHANNEL.formatted("BFMX")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("CHANNEL_NOT_SUPPORTED"));

        verifyNoInteractions(imageWorkflowService);
    }

    @Test
    void aDeclaredChannelIsAcceptedInAnyCase() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(22));
        when(imageWorkflowService.processCanonicalReading(any(), any())).thenReturn(CreateReadingResponse.builder()
                .success(true).message("ok").correlationId("corr-1").build());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(CANONICAL_BODY_WITH_CHANNEL.formatted("elm")))
                .andExpect(status().isOk());

        ArgumentCaptor<CanonicalReadingRequest> captor = ArgumentCaptor.forClass(CanonicalReadingRequest.class);
        verify(imageWorkflowService).processCanonicalReading(captor.capture(), any());
        assertEquals("elm", captor.getValue().getChannel());
    }

    @Test
    void channelIsCheckedAfterTheApiKeySoAnUnauthenticatedCallerLearnsNothing() throws Exception {
        when(apiKeyService.resolveTenantIdFromRawApiKey("bad")).thenReturn(Optional.empty());

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "bad")
                        .contentType("application/json")
                        .content(CANONICAL_BODY_WITH_CHANNEL.formatted("BFMX")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_API_KEY"));

        verifyNoInteractions(imageWorkflowService);
    }

    @Test
    void structuralValidationStillWinsOverTheChannelCheck() throws Exception {
        // Both endpoints must agree on precedence. On the canonical endpoint @Valid runs before the
        // method body, so a structurally invalid payload can never reach the channel check; this
        // endpoint validates by hand, so the same order is pinned here.
        when(apiKeyService.resolveTenantIdFromRawApiKey("valid")).thenReturn(Optional.of(22));

        String noSchemeAndBadChannel = """
                {
                  "confirmed_reading": 123.4,
                  "phone_number": "91XXXXXXXXXX",
                  "channel": "BFMX"
                }
                """;

        mockMvc().perform(post("/api/v1/telemetry/readings/formats/canonical")
                        .header("X-Api-Key", "valid")
                        .contentType("application/json")
                        .content(noSchemeAndBadChannel))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"));

        verifyNoInteractions(imageWorkflowService);
    }
}
