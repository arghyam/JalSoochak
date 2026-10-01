package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
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
 * Endpoint behaviour of the meter-change webhooks: each passes the service response through, and
 * falls back to a body the chatbot flow can render when the service throws. The reasons endpoint
 * answers raw JSON, including on that fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MeterChangeWebhookController — endpoints")
class MeterChangeWebhookControllerTest {

    private static final String CONTACT = "919999900001";
    private static final RuntimeException BOOM = new IllegalStateException("downstream failure");

    @Mock
    private MeterReadingConversationService meterWorkflowService;

    private MeterChangeWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();

    @BeforeEach
    void setUp() {
        controller = new MeterChangeWebhookController(meterWorkflowService);
    }

    private static IntroRequest introRequest() {
        IntroRequest request = new IntroRequest();
        request.setContactId(CONTACT);
        return request;
    }

    private static MeterChangeRequest meterChangeRequest() {
        MeterChangeRequest request = new MeterChangeRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Test
    void meterChangePassesThroughAndFallsBack() {
        when(meterWorkflowService.meterChangeMessage(any(MeterChangeRequest.class))).thenReturn(okIntro);
        assertThat(controller.meterChange(meterChangeRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.meterChangeMessage(any(MeterChangeRequest.class))).thenThrow(BOOM);
        assertThat(controller.meterChange(meterChangeRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void meterChangeReasonsReturnsTheRawJsonBody() {
        when(meterWorkflowService.meterChangeReasons(any())).thenReturn("{\"reasons\":[]}");

        assertThat(controller.meterChangeReasons(introRequest()).getBody()).isEqualTo("{\"reasons\":[]}");
    }

    @Test
    void meterChangeReasonsFallsBackToAJsonErrorBody() {
        when(meterWorkflowService.meterChangeReasons(any())).thenThrow(BOOM);

        var response = controller.meterChangeReasons(introRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .isEqualTo("{\"success\":false,\"message\":\"Meter change reasons could not be fetched.\"}");
    }

    @Test
    void meterChangeSubmitPassesThroughAndFallsBack() {
        when(meterWorkflowService.meterChangeSubmitMessage(any())).thenReturn(okIntro);
        assertThat(controller.meterChangeSubmit(meterChangeRequest()).getBody()).isSameAs(okIntro);

        when(meterWorkflowService.meterChangeSubmitMessage(any())).thenThrow(BOOM);
        assertThat(controller.meterChangeSubmit(meterChangeRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
