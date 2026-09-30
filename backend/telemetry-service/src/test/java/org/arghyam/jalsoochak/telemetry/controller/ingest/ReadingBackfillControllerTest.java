package org.arghyam.jalsoochak.telemetry.controller.ingest;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.config.TelemetryApiKeyAuthFilter;
import org.arghyam.jalsoochak.telemetry.service.ReadingBackfillService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReadingBackfillControllerTest {

    private static final String PATH = "/api/v1/telemetry/readings/republish";
    private static final String API_KEY = "js_valid_key";
    private static final int TENANT_ID = 22;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private ReadingBackfillService readingBackfillService;
    @Mock
    private TelemetryApiKeyService telemetryApiKeyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReadingBackfillController(readingBackfillService, telemetryApiKeyService))
                .build();
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(request(body));
    }

    private static MockHttpServletRequestBuilder request(String body) {
        return post(PATH)
                .header(TelemetryApiKeyAuthFilter.API_KEY_HEADER, API_KEY)
                .contentType("application/json")
                .content(body);
    }

    private void authenticated() {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey(API_KEY)).thenReturn(Optional.of(TENANT_ID));
    }

    @Test
    void republishesAndReturnsTheCounts() throws Exception {
        authenticated();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, "S-1", null, ReadingChannel.PDU))
                .thenReturn(new ReadingBackfillService.Outcome(3, 1));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "stateSchemeId": "S-1", "channel": "pdu"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.republishedCount").value(3))
                .andExpect(jsonPath("$.data.withheldCount").value(1))
                .andExpect(jsonPath("$.data.errorCode").doesNotExist());
    }

    /** The State IT integration sends the scheme ids in snake case on POST /readings. */
    @Test
    void acceptsSnakeCaseFieldsAndDefaultsToEveryChannel() throws Exception {
        authenticated();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, null, "C-9", null))
                .thenReturn(new ReadingBackfillService.Outcome(0, 0));

        perform("""
                {"from_date": "2026-09-01", "to_date": "2026-09-30", "centre_scheme_id": "C-9"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.republishedCount").value(0));
    }

    @Test
    void usesTheTenantTheFilterAuthenticated() throws Exception {
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, null))
                .thenReturn(new ReadingBackfillService.Outcome(1, 0));

        mockMvc.perform(request("""
                        {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                        """).requestAttr(TelemetryApiKeyAuthFilter.TENANT_ID_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isOk());

        verifyNoInteractions(telemetryApiKeyService);
    }

    @Test
    void rejectsAnUnknownApiKey() throws Exception {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey(API_KEY)).thenReturn(Optional.empty());

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_API_KEY"));

        verifyNoInteractions(readingBackfillService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BFM", "IOT", "flow"})
    void rejectsAChannelOtherThanElmOrPdu(String channel) throws Exception {
        authenticated();

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "channel": "%s"}
                """.formatted(channel))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("CHANNEL_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.data.message").value("Unsupported channel. Allowed values are: ELM, PDU"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void acceptsARangeOfThirtyOneDays() throws Exception {
        authenticated();
        when(readingBackfillService.republish(TENANT_ID, FROM, LocalDate.of(2026, 10, 1), null, null, null))
                .thenReturn(new ReadingBackfillService.Outcome(0, 0));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-10-01"}
                """)
                .andExpect(status().isOk());
    }

    @Test
    void rejectsARangeOfMoreThanThirtyOneDays() throws Exception {
        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-10-02"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message").value("The date range can't be more than 31 days"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void rejectsAnEndBeforeTheStart() throws Exception {
        perform("""
                {"fromDate": "2026-09-30", "toDate": "2026-09-01"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("toDate must not be before fromDate"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void rejectsMissingDates() throws Exception {
        perform("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message").value("fromDate must be provided; toDate must be provided"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void rejectsAnUnparseableDate() throws Exception {
        perform("""
                {"fromDate": "2026-13-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void answersAnUnknownSchemeWithNotFound() throws Exception {
        authenticated();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, "S-404", null, null))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found"));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "stateSchemeId": "S-404"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("SCHEME_NOT_FOUND"));
    }

    @Test
    void answersAFailureWithoutItsDetail() throws Exception {
        authenticated();
        when(readingBackfillService.republish(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Flow reading 7 not found in tenant_as"));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.errorCode").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.message").value("Failed to republish readings"));
    }
}
