package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Endpoint behaviour of the issue-report webhooks: each passes the service response through, and
 * falls back to a body the chatbot flow can render when the service throws. The reasons endpoint
 * answers raw JSON, including on that fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IssueReportWebhookController — endpoints")
class IssueReportWebhookControllerTest {

    private static final String CONTACT = "919999900001";
    private static final RuntimeException BOOM = new IllegalStateException("downstream failure");

    @Mock
    private MeterReadingConversationService meterWorkflowService;

    private IssueReportWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();

    @BeforeEach
    void setUp() {
        controller = new IssueReportWebhookController(meterWorkflowService);
    }

    private static IntroRequest introRequest() {
        IntroRequest request = new IntroRequest();
        request.setContactId(CONTACT);
        return request;
    }

    private static IssueReportRequest issueReportRequest() {
        IssueReportRequest request = new IssueReportRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Test
    void issueReportPromptPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportPromptMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportPrompt(introRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportPromptMessage(any())).thenThrow(BOOM);
        assertThat(controller.issueReportPrompt(introRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void issueReportSubmitPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportSubmitMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportSubmit(issueReportRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportSubmitMessage(any())).thenThrow(BOOM);
        assertThat(controller.issueReportSubmit(issueReportRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void issueReportTelemetryPromptPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportTelemetryPromptMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportTelemetryPrompt(introRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportTelemetryPromptMessage(any())).thenThrow(BOOM);
        assertThat(controller.issueReportTelemetryPrompt(introRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void issueReportTelemetrySubmitPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportTelemetrySubmitMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportTelemetrySubmit(issueReportRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportTelemetrySubmitMessage(any())).thenThrow(BOOM);
        assertThat(controller.issueReportTelemetrySubmit(issueReportRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void telemetryReasonsReturnsTheRawJsonBody() {
        when(meterWorkflowService.issueReportTelemetryReasons(any())).thenReturn("{\"reasons\":[]}");

        var response = controller.issueReportTelemetryReasons(introRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"reasons\":[]}");
    }

    @Test
    void telemetryReasonsFallsBackToAJsonErrorBody() {
        when(meterWorkflowService.issueReportTelemetryReasons(any())).thenThrow(BOOM);

        var response = controller.issueReportTelemetryReasons(introRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .isEqualTo("{\"success\":false,\"message\":\"Supply outage reasons could not be fetched.\"}");
    }

    @Test
    void othersPromptPassesThroughAndFallsBack() {
        when(meterWorkflowService.othersPromptMessage(any())).thenReturn(okIntro);
        assertThat(controller.othersPrompt(introRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.othersPromptMessage(any())).thenThrow(BOOM);
        assertThat(controller.othersPrompt(introRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void othersSubmittedPassesThroughAndFallsBack() {
        when(meterWorkflowService.othersSubmittedMessage(any())).thenReturn(okIntro);
        assertThat(controller.othersSubmitted(issueReportRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.othersSubmittedMessage(any())).thenThrow(BOOM);
        assertThat(controller.othersSubmitted(issueReportRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
