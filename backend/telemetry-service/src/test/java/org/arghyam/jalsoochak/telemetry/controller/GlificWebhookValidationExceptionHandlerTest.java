package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 255-character cap reaching the wire as a {@code 400} in the Glific {@code {success,message}}
 * envelope, and the paths that must NOT become a {@code 400}.
 *
 * <p>Driven through {@code standaloneSetup} rather than {@code @WebMvcTest} — this service has no
 * test {@code application.properties} and its {@code application.yml} carries unresolved
 * placeholders, so booting a Spring context fails. Same reasoning as
 * {@link MultiFormatReadingControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlificWebhookValidationExceptionHandlerTest {

    private static final String SUBMIT = "/api/v1/telemetry/issue-report/submit";
    private static final String TELEMETRY_SUBMIT = "/api/v1/telemetry/issue-report/telemetry/submit";
    private static final String OTHERS_SUBMIT = "/api/v1/telemetry/others/submitted";

    @Mock
    private GlificWebhookService webhookService;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new GlificWebhookController(webhookService))
                .setControllerAdvice(new GlificWebhookValidationExceptionHandler())
                .build();
    }

    private static String body(String key, String value) {
        return "{\"contactId\":\"12345\",\"" + key + "\":\"" + value + "\"}";
    }

    @Test
    void theAuditedTenThousandCharacterReasonIsRejectedWith400AndNeverReachesTheService() throws Exception {
        mockMvc().perform(post(SUBMIT)
                        .contentType("application/json")
                        .content(body("issueReason", "a".repeat(10_000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("issueReason must not exceed 255 characters"));

        verify(webhookService, never()).issueReportSubmitMessage(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issueReason", "reason", "issue", "results", "message"})
    void noJsonAliasIsAWayAroundTheCap(String alias) throws Exception {
        // The DTO binds five names for this field. A cap enforced on only the canonical one would
        // be trivially bypassed, and "results" is the name Glific actually sends.
        mockMvc().perform(post(SUBMIT)
                        .contentType("application/json")
                        .content(body(alias, "a".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(webhookService, never()).issueReportSubmitMessage(any());
    }

    @Test
    void theCapAlsoCoversTheOtherTwoEndpointsSharingTheDto() throws Exception {
        mockMvc().perform(post(TELEMETRY_SUBMIT)
                        .contentType("application/json")
                        .content(body("issueReason", "a".repeat(256))))
                .andExpect(status().isBadRequest());

        mockMvc().perform(post(OTHERS_SUBMIT)
                        .contentType("application/json")
                        .content(body("issueReason", "a".repeat(256))))
                .andExpect(status().isBadRequest());

        verify(webhookService, never()).issueReportTelemetrySubmitMessage(any());
        verify(webhookService, never()).othersSubmittedMessage(any());
    }

    @Test
    void aReasonAtExactlyTheCapPassesThroughToTheService() throws Exception {
        when(webhookService.issueReportSubmitMessage(any()))
                .thenReturn(IntroResponse.builder().success(true).message("saved").build());

        mockMvc().perform(post(SUBMIT)
                        .contentType("application/json")
                        .content(body("issueReason", "a".repeat(IssueReportRequest.MAX_ISSUE_REASON_LENGTH))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(webhookService).issueReportSubmitMessage(any());
    }

    @Test
    void theAllLowercaseIssuereasonKeyIsNotBoundAtAllSoItIsNotA400() throws Exception {
        // Documents a correction to the audit's reproduction steps: the report names the field
        // "issuereason", but no Jackson case-insensitivity is configured and the DTO ignores
        // unknown properties, so that key is silently discarded and issueReason stays null. The
        // 200 the auditors observed proves a null reason is tolerated, not a 10,000-char one.
        when(webhookService.issueReportSubmitMessage(any()))
                .thenReturn(IntroResponse.builder().success(false).message("Issue reason is required.").build());

        mockMvc().perform(post(SUBMIT)
                        .contentType("application/json")
                        .content(body("issuereason", "a".repeat(10_000))))
                .andExpect(status().isOk());

        verify(webhookService).issueReportSubmitMessage(any());
    }

    @Test
    void aReasonWithDisallowedCharactersIsNotA400BecauseThatCheckStaysInTheService() throws Exception {
        // Character validation deliberately does not come through this advice — it must stay a
        // localised 200 so a real operator typo does not stall the Glific flow.
        when(webhookService.issueReportSubmitMessage(any()))
                .thenReturn(IntroResponse.builder()
                        .success(false)
                        .message("Issue reason can only contain letters, numbers, and spaces.")
                        .build());

        mockMvc().perform(post(SUBMIT)
                        .contentType("application/json")
                        .content("{\"contactId\":\"12345\",\"issueReason\":\"<script>alert(1)</script>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        verify(webhookService).issueReportSubmitMessage(any());
    }
}
