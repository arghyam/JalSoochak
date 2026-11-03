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
        var fallback = controller.issueReportPrompt(introRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().getMessage()).isEqualTo("Issue report prompt could not be prepared.");
    }

    @Test
    void issueReasonTypesPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReasonTypesMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReasonTypes(introRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReasonTypesMessage(any())).thenThrow(BOOM);
        var fallback = controller.issueReasonTypes(introRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().isSuccess()).isFalse();
        assertThat(fallback.getBody().getMessage()).isEqualTo("Issue reason types could not be prepared.");
    }

    @Test
    void issueReportSubmitPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportSubmitMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportSubmit(issueReportRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportSubmitMessage(any())).thenThrow(BOOM);
        var fallback = controller.issueReportSubmit(issueReportRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().getMessage()).isEqualTo("Issue report could not be saved.");
    }

    @Test
    void issueReportTelemetryPromptPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportTelemetryPromptMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportTelemetryPrompt(introRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportTelemetryPromptMessage(any())).thenThrow(BOOM);
        var fallback = controller.issueReportTelemetryPrompt(introRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().getMessage()).isEqualTo("Issue report prompt could not be prepared.");
    }

    @Test
    void issueReportTelemetrySubmitPassesThroughAndFallsBack() {
        when(meterWorkflowService.issueReportTelemetrySubmitMessage(any())).thenReturn(okIntro);
        assertThat(controller.issueReportTelemetrySubmit(issueReportRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.issueReportTelemetrySubmitMessage(any())).thenThrow(BOOM);
        var fallback = controller.issueReportTelemetrySubmit(issueReportRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().getMessage()).isEqualTo("Issue report could not be saved.");
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

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isEqualTo("{\"success\":false,\"message\":\"Supply outage reasons could not be fetched.\"}");
    }

    @Test
    void othersPromptPassesThroughAndFallsBack() {
        when(meterWorkflowService.othersPromptMessage(any())).thenReturn(okIntro);
        assertThat(controller.othersPrompt(introRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.othersPromptMessage(any())).thenThrow(BOOM);
        var fallback = controller.othersPrompt(introRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().getMessage()).isEqualTo("Others prompt could not be prepared.");
    }

    @Test
    void othersSubmittedPassesThroughAndFallsBack() {
        when(meterWorkflowService.othersSubmittedMessage(any())).thenReturn(okIntro);
        assertThat(controller.othersSubmitted(issueReportRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.othersSubmittedMessage(any())).thenThrow(BOOM);
        var fallback = controller.othersSubmitted(issueReportRequest());
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fallback.getBody().getMessage()).isEqualTo("Issue report could not be saved.");
    }
}
