package org.arghyam.jalsoochak.telemetry.controller.internal;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.config.TenantInterceptor;
import org.arghyam.jalsoochak.telemetry.service.ReadingBackfillService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReadingBackfillControllerTest {

    private static final String PATH = "/api/v1/telemetry/internal/readings/republish";
    private static final String TENANT_CODE = "AS";
    private static final int TENANT_ID = 22;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private ReadingBackfillService readingBackfillService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReadingBackfillController(readingBackfillService))
                .build();
    }

    private ResultActions perform(String body) throws Exception {
        return perform(TENANT_CODE, body);
    }

    /** @param tenantCode {@code null} to send no {@code X-Tenant-Code} header */
    private ResultActions perform(String tenantCode, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).contentType("application/json").content(body);
        if (tenantCode != null) {
            request.header(TenantInterceptor.TENANT_HEADER, tenantCode);
        }
        return mockMvc.perform(request);
    }

    private void knownTenant() {
        when(readingBackfillService.findTenantId(TENANT_CODE)).thenReturn(Optional.of(TENANT_ID));
    }

    @Test
    void republishesAndReturnsTheCounts() throws Exception {
        knownTenant();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, "S-1", null, ReadingChannel.PDU))
                .thenReturn(new ReadingBackfillService.Outcome(3, 1, 0));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "stateSchemeId": "S-1", "channel": "pdu"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.republishedCount").value(3))
                .andExpect(jsonPath("$.data.withheldCount").value(1))
                .andExpect(jsonPath("$.data.notSentCount").doesNotExist())
                .andExpect(jsonPath("$.data.errorCode").doesNotExist());
    }

    /** The State IT integration sends the scheme ids in snake case on POST /readings. */
    @Test
    void acceptsSnakeCaseFieldsAndDefaultsToEveryChannel() throws Exception {
        knownTenant();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, null, "C-9", null))
                .thenReturn(new ReadingBackfillService.Outcome(0, 0, 0));

        perform("""
                {"from_date": "2026-09-01", "to_date": "2026-09-30", "centre_scheme_id": "C-9"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.republishedCount").value(0));
    }

    @Test
    void looksTheTenantUpByTheTrimmedHeader() throws Exception {
        when(readingBackfillService.findTenantId("as")).thenReturn(Optional.of(TENANT_ID));
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, null))
                .thenReturn(new ReadingBackfillService.Outcome(1, 0, 0));

        perform("  as ", """
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.republishedCount").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsABlankTenantCode(String tenantCode) throws Exception {
        perform(tenantCode, """
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message").value("X-Tenant-Code must be provided"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void rejectsAMissingTenantCode() throws Exception {
        perform(null, """
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.message").value("X-Tenant-Code must be provided"));

        verifyNoInteractions(readingBackfillService);
    }

    @Test
    void answersAnUnknownTenantWithNotFound() throws Exception {
        when(readingBackfillService.findTenantId(TENANT_CODE)).thenReturn(Optional.empty());

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.data.message").value("Tenant not found"));

        verify(readingBackfillService, never()).republish(any(), any(), any(), any(), any(), any());
    }

    @Test
    void answersAFailedTenantLookupWithoutItsDetail() throws Exception {
        when(readingBackfillService.findTenantId(TENANT_CODE))
                .thenThrow(new IllegalStateException("connection refused to common_schema"));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.errorCode").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.message").value("Failed to republish readings"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BFM", "IOT", "flow"})
    void rejectsAChannelOtherThanElmOrPdu(String channel) throws Exception {
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
        knownTenant();
        when(readingBackfillService.republish(TENANT_ID, FROM, LocalDate.of(2026, 10, 1), null, null, null))
                .thenReturn(new ReadingBackfillService.Outcome(0, 0, 0));

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
        knownTenant();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, "S-404", null, null))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found"));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "stateSchemeId": "S-404"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("SCHEME_NOT_FOUND"));
    }

    /** The counts say how far the run got, so the caller knows to send the same range again. */
    @Test
    void answersARunThatStoppedPartWayWithItsCounts() throws Exception {
        knownTenant();
        when(readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, null))
                .thenReturn(new ReadingBackfillService.Outcome(4, 1, 7));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.republishedCount").value(4))
                .andExpect(jsonPath("$.data.withheldCount").value(1))
                .andExpect(jsonPath("$.data.notSentCount").value(7))
                .andExpect(jsonPath("$.data.errorCode").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.message").value(ReadingBackfillController.NOT_SENT_MESSAGE));
    }

    @Test
    void answersAFailureWithoutItsDetail() throws Exception {
        knownTenant();
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
