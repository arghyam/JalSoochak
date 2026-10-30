package org.arghyam.jalsoochak.telemetry.controller.internal;

import org.arghyam.jalsoochak.telemetry.config.TenantInterceptor;
import org.arghyam.jalsoochak.telemetry.service.QuarantinedReadingReleaseService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class QuarantineReleaseControllerTest {

    private static final String PATH = "/api/v1/telemetry/internal/readings/quarantine/release";
    private static final String TENANT_CODE = "AS";
    private static final int TENANT_ID = 22;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private QuarantinedReadingReleaseService releaseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new QuarantineReleaseController(releaseService))
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
        when(releaseService.findTenantId(TENANT_CODE)).thenReturn(Optional.of(TENANT_ID));
    }

    @Test
    void releasesAndReturnsTheCounts() throws Exception {
        knownTenant();
        when(releaseService.release(TENANT_ID, FROM, TO, "S-1", null, false))
                .thenReturn(new QuarantinedReadingReleaseService.Outcome(3, 1, 0));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "stateSchemeId": "S-1"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.releasedCount").value(3))
                .andExpect(jsonPath("$.data.stillQuarantinedCount").value(1))
                .andExpect(jsonPath("$.data.notSentCount").doesNotExist())
                .andExpect(jsonPath("$.data.errorCode").doesNotExist());
    }

    @Test
    void passesADryRunThroughAndSaysSo() throws Exception {
        knownTenant();
        when(releaseService.release(TENANT_ID, FROM, TO, null, null, true))
                .thenReturn(new QuarantinedReadingReleaseService.Outcome(5, 2, 0));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "dryRun": true}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.releasedCount").value(5))
                .andExpect(jsonPath("$.data.stillQuarantinedCount").value(2));
    }

    /** The State IT integration sends the scheme ids in snake case on POST /readings. */
    @Test
    void acceptsSnakeCaseFields() throws Exception {
        knownTenant();
        when(releaseService.release(TENANT_ID, FROM, TO, null, "C-9", true))
                .thenReturn(new QuarantinedReadingReleaseService.Outcome(0, 0, 0));

        perform("""
                {"from_date": "2026-09-01", "to_date": "2026-09-30", "centre_scheme_id": "C-9", "dry_run": true}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.releasedCount").value(0));
    }

    @Test
    void looksTheTenantUpByTheTrimmedHeader() throws Exception {
        when(releaseService.findTenantId("as")).thenReturn(Optional.of(TENANT_ID));
        when(releaseService.release(TENANT_ID, FROM, TO, null, null, false))
                .thenReturn(new QuarantinedReadingReleaseService.Outcome(1, 0, 0));

        perform("  as ", """
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.releasedCount").value(1));
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

        verifyNoInteractions(releaseService);
    }

    @Test
    void rejectsAMissingTenantCode() throws Exception {
        perform(null, """
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("X-Tenant-Code must be provided"));

        verifyNoInteractions(releaseService);
    }

    @Test
    void answersAnUnknownTenantWithNotFound() throws Exception {
        when(releaseService.findTenantId(TENANT_CODE)).thenReturn(Optional.empty());

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.data.message").value("Tenant not found"));

        verify(releaseService, never()).release(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void acceptsARangeOfThirtyOneDays() throws Exception {
        knownTenant();
        when(releaseService.release(TENANT_ID, FROM, LocalDate.of(2026, 10, 1), null, null, false))
                .thenReturn(new QuarantinedReadingReleaseService.Outcome(0, 0, 0));

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

        verifyNoInteractions(releaseService);
    }

    @Test
    void rejectsAnEndBeforeTheStart() throws Exception {
        perform("""
                {"fromDate": "2026-09-30", "toDate": "2026-09-01"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("toDate must not be before fromDate"));

        verifyNoInteractions(releaseService);
    }

    @Test
    void rejectsMissingDates() throws Exception {
        perform("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("fromDate must be provided; toDate must be provided"));

        verifyNoInteractions(releaseService);
    }

    @Test
    void rejectsAnUnparseableDate() throws Exception {
        perform("""
                {"fromDate": "2026-13-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));

        verifyNoInteractions(releaseService);
    }

    @Test
    void answersAnUnknownSchemeWithNotFound() throws Exception {
        knownTenant();
        when(releaseService.release(TENANT_ID, FROM, TO, "S-404", null, false))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found"));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30", "stateSchemeId": "S-404"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("SCHEME_NOT_FOUND"));
    }

    /** The counts say how far the run got, so the caller knows to release the same range again. */
    @Test
    void answersARunThatStoppedPartWayWithItsCounts() throws Exception {
        knownTenant();
        when(releaseService.release(TENANT_ID, FROM, TO, null, null, false))
                .thenReturn(new QuarantinedReadingReleaseService.Outcome(4, 1, 7));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.releasedCount").value(4))
                .andExpect(jsonPath("$.data.stillQuarantinedCount").value(1))
                .andExpect(jsonPath("$.data.notSentCount").value(7))
                .andExpect(jsonPath("$.data.errorCode").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.message").value(QuarantineReleaseController.NOT_SENT_MESSAGE));
    }

    @Test
    void answersAFailureWithoutItsDetail() throws Exception {
        knownTenant();
        when(releaseService.release(any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Flow reading 7 not found in tenant_as"));

        perform("""
                {"fromDate": "2026-09-01", "toDate": "2026-09-30"}
                """)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.errorCode").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.message").value("Failed to release quarantined readings"));
    }
}
