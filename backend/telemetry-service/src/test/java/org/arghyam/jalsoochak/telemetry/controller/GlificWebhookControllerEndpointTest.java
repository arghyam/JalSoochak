package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.TriggerWelcomeMessageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.service.GlificReadingsAsyncService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.arghyam.jalsoochak.telemetry.service.WelcomeMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoint behaviour of the Glific webhook controller.
 *
 * <p>Every endpoint wraps its service call in a catch-all that returns a Glific-renderable body
 * instead of a stack trace — an unhandled 500 would strand the operator mid-flow. These tests cover
 * both the pass-through and that fallback for each route.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificWebhookController — endpoints")
class GlificWebhookControllerEndpointTest {

    private static final String CONTACT = "919999900001";
    private static final RuntimeException BOOM = new IllegalStateException("downstream failure");

    @Mock
    private GlificWebhookService glificWebhookService;
    @Mock
    private GlificReadingsAsyncService glificReadingsAsyncService;
    @Mock
    private WelcomeMessageService welcomeMessageService;
    @Mock
    private TelemetrySubmissionAuditService auditService;

    private GlificWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();
    private final CreateReadingResponse okReading =
            CreateReadingResponse.builder().success(true).message("recorded").build();

    @BeforeEach
    void setUp() {
        controller = new GlificWebhookController(
                glificWebhookService, glificReadingsAsyncService, welcomeMessageService, auditService);
        // any() rather than anyString(): the controller passes a null contactId straight through when
        // the webhook body is missing, and the real audit service handles that.
        when(auditService.captureForContact(any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", 7L, 1, LocalDate.of(2026, 3, 1)));
    }

    private static IntroRequest introRequest() {
        IntroRequest request = new IntroRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Nested
    @DisplayName("POST /readings/glific")
    class ReadingsWebhook {

        private GlificWebhookRequest request() {
            GlificWebhookRequest request = new GlificWebhookRequest();
            request.setContactId(CONTACT);
            return request;
        }

        @Test
        void acksImmediatelyAndHandsTheWorkToTheAsyncService() {
            var response = controller.receive(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().isSuccess()).isTrue();
            assertThat(response.getBody().getStatus()).isEqualTo("accepted");
            assertThat(response.getBody().getJobId()).isNotBlank();
            verify(glificReadingsAsyncService).enqueueProcessAndResume(any(), anyString());
            verify(glificWebhookService, never()).processImage(any());
        }

        @Test
        void processesSynchronouslyWhenNoAsyncServiceIsWired() {
            var syncOnly = new GlificWebhookController(glificWebhookService);
            when(glificWebhookService.processImage(any())).thenReturn(okReading);

            var response = syncOnly.receive(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(glificWebhookService).processImage(any());
        }

        @Test
        void stillAcksWhenSynchronousProcessingReportsFailure() {
            var syncOnly = new GlificWebhookController(glificWebhookService);
            when(glificWebhookService.processImage(any()))
                    .thenReturn(CreateReadingResponse.builder().success(false).message("rejected").build());

            assertThat(syncOnly.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void stillAcksWhenSynchronousProcessingReturnsNothing() {
            var syncOnly = new GlificWebhookController(glificWebhookService);
            when(glificWebhookService.processImage(any())).thenReturn(null);

            assertThat(syncOnly.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void returnsAnErrorAckWhenEnqueueingFails() {
            org.mockito.Mockito.doThrow(BOOM)
                    .when(glificReadingsAsyncService).enqueueProcessAndResume(any(), anyString());

            var response = controller.receive(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getStatus()).isEqualTo("error");
            assertThat(response.getBody().getJobId()).isNull();
        }

        @Test
        void toleratesAMissingRequestBody() {
            var response = controller.receive(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void worksWithTheTwoArgumentConstructor() {
            var twoArg = new GlificWebhookController(glificWebhookService, glificReadingsAsyncService);

            assertThat(twoArg.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("message endpoints")
    class MessageEndpoints {

        @Test
        void introReturnsTheServiceResponse() {
            when(glificWebhookService.introMessage(any())).thenReturn(okIntro);

            assertThat(controller.sendIntro(introRequest()).getBody()).isSameAs(okIntro);
        }

        @Test
        void introFallsBackOnFailure() {
            when(glificWebhookService.introMessage(any())).thenThrow(BOOM);

            var response = controller.sendIntro(introRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getMessage()).isEqualTo("Something went wrong. Please try again.");
        }

        @Test
        void closingReturnsTheServiceResponse() {
            ClosingResponse ok = ClosingResponse.builder().success(true).build();
            when(glificWebhookService.closingMessage(any())).thenReturn(ok);

            ClosingRequest request = new ClosingRequest();
            request.setContactId(CONTACT);
            assertThat(controller.closingMessage(request).getBody()).isSameAs(ok);
        }

        @Test
        void closingFallsBackOnFailure() {
            when(glificWebhookService.closingMessage(any())).thenThrow(BOOM);

            ClosingRequest request = new ClosingRequest();
            request.setContactId(CONTACT);
            var response = controller.closingMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("selection endpoints")
    class SelectionEndpoints {

        @Test
        void languageSelectionPassesThroughAndFallsBack() {
            when(glificWebhookService.languageSelectionMessage(any())).thenReturn(okIntro);
            assertThat(controller.languageSelection(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.languageSelectionMessage(any())).thenThrow(BOOM);
            assertThat(controller.languageSelection(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void selectedLanguagePassesThroughAndFallsBack() {
            SelectedLanguageRequest request = new SelectedLanguageRequest();
            request.setContactId(CONTACT);

            when(glificWebhookService.selectedLanguageMessage(any())).thenReturn(okIntro);
            assertThat(controller.selectedLanguage(request).getBody()).isSameAs(okIntro);

            when(glificWebhookService.selectedLanguageMessage(any())).thenThrow(BOOM);
            assertThat(controller.selectedLanguage(request).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void channelSelectionPassesThroughAndFallsBack() {
            when(glificWebhookService.channelSelectionMessage(any())).thenReturn(okIntro);
            assertThat(controller.channelSelection(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.channelSelectionMessage(any())).thenThrow(BOOM);
            assertThat(controller.channelSelection(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void selectedChannelPassesThroughAndFallsBack() {
            SelectedChannelRequest request = new SelectedChannelRequest();
            request.setContactId(CONTACT);

            when(glificWebhookService.selectedChannelMessage(any())).thenReturn(okIntro);
            assertThat(controller.selectedChannel(request).getBody()).isSameAs(okIntro);

            when(glificWebhookService.selectedChannelMessage(any())).thenThrow(BOOM);
            assertThat(controller.selectedChannel(request).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void schemesPassesThroughAndFallsBack() {
            when(glificWebhookService.schemeSelectionMessage(any())).thenReturn(okIntro);
            assertThat(controller.schemes(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.schemeSelectionMessage(any())).thenThrow(BOOM);
            assertThat(controller.schemes(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void selectedSchemePassesThroughAndFallsBack() {
            SelectedSchemeRequest request = new SelectedSchemeRequest();
            request.setContactId(CONTACT);

            when(glificWebhookService.selectedSchemeMessage(any())).thenReturn(okIntro);
            assertThat(controller.selectedScheme(request).getBody()).isSameAs(okIntro);

            when(glificWebhookService.selectedSchemeMessage(any())).thenThrow(BOOM);
            var response = controller.selectedScheme(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage()).isEqualTo("Scheme selection could not be saved.");
        }

        @Test
        void itemSelectionPassesThroughAndFallsBack() {
            when(glificWebhookService.itemSelectionMessage(any())).thenReturn(okIntro);
            assertThat(controller.itemSelection(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.itemSelectionMessage(any())).thenThrow(BOOM);
            assertThat(controller.itemSelection(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void selectedItemPassesThroughAndFallsBack() {
            SelectedItemRequest request = new SelectedItemRequest();
            request.setContactId(CONTACT);
            SelectionResponse ok = SelectionResponse.builder().success(true).build();

            when(glificWebhookService.selectedItemMessage(any())).thenReturn(ok);
            assertThat(controller.selectedItem(request).getBody()).isSameAs(ok);

            when(glificWebhookService.selectedItemMessage(any())).thenThrow(BOOM);
            assertThat(controller.selectedItem(request).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("meter and issue-report endpoints")
    class MeterEndpoints {

        private MeterChangeRequest meterChangeRequest() {
            MeterChangeRequest request = new MeterChangeRequest();
            request.setContactId(CONTACT);
            return request;
        }

        private IssueReportRequest issueReportRequest() {
            IssueReportRequest request = new IssueReportRequest();
            request.setContactId(CONTACT);
            return request;
        }

        @Test
        void meterChangePassesThroughAndFallsBack() {
            when(glificWebhookService.meterChangeMessage(any(MeterChangeRequest.class))).thenReturn(okIntro);
            assertThat(controller.meterChange(meterChangeRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.meterChangeMessage(any(MeterChangeRequest.class))).thenThrow(BOOM);
            assertThat(controller.meterChange(meterChangeRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void meterChangeSubmitPassesThroughAndFallsBack() {
            when(glificWebhookService.meterChangeSubmitMessage(any())).thenReturn(okIntro);
            assertThat(controller.meterChangeSubmit(meterChangeRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.meterChangeSubmitMessage(any())).thenThrow(BOOM);
            assertThat(controller.meterChangeSubmit(meterChangeRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void takeMeterReadingPassesThroughAndFallsBack() {
            when(glificWebhookService.takeMeterReadingMessage(any())).thenReturn(okIntro);
            assertThat(controller.takeMeterReading(meterChangeRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.takeMeterReadingMessage(any())).thenThrow(BOOM);
            assertThat(controller.takeMeterReading(meterChangeRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void issueReportPromptPassesThroughAndFallsBack() {
            when(glificWebhookService.issueReportPromptMessage(any())).thenReturn(okIntro);
            assertThat(controller.issueReportPrompt(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.issueReportPromptMessage(any())).thenThrow(BOOM);
            assertThat(controller.issueReportPrompt(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void issueReportSubmitPassesThroughAndFallsBack() {
            when(glificWebhookService.issueReportSubmitMessage(any())).thenReturn(okIntro);
            assertThat(controller.issueReportSubmit(issueReportRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.issueReportSubmitMessage(any())).thenThrow(BOOM);
            assertThat(controller.issueReportSubmit(issueReportRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void issueReportTelemetryPromptPassesThroughAndFallsBack() {
            when(glificWebhookService.issueReportTelemetryPromptMessage(any())).thenReturn(okIntro);
            assertThat(controller.issueReportTelemetryPrompt(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.issueReportTelemetryPromptMessage(any())).thenThrow(BOOM);
            assertThat(controller.issueReportTelemetryPrompt(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void issueReportTelemetrySubmitPassesThroughAndFallsBack() {
            when(glificWebhookService.issueReportTelemetrySubmitMessage(any())).thenReturn(okIntro);
            assertThat(controller.issueReportTelemetrySubmit(issueReportRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.issueReportTelemetrySubmitMessage(any())).thenThrow(BOOM);
            assertThat(controller.issueReportTelemetrySubmit(issueReportRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void othersPromptPassesThroughAndFallsBack() {
            when(glificWebhookService.othersPromptMessage(any())).thenReturn(okIntro);
            assertThat(controller.othersPrompt(introRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.othersPromptMessage(any())).thenThrow(BOOM);
            assertThat(controller.othersPrompt(introRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void othersSubmittedPassesThroughAndFallsBack() {
            when(glificWebhookService.othersSubmittedMessage(any())).thenReturn(okIntro);
            assertThat(controller.othersSubmitted(issueReportRequest()).getBody()).isSameAs(okIntro);

            when(glificWebhookService.othersSubmittedMessage(any())).thenThrow(BOOM);
            assertThat(controller.othersSubmitted(issueReportRequest()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("raw JSON reason endpoints")
    class ReasonEndpoints {

        @Test
        void telemetryReasonsReturnsTheRawJsonBody() {
            when(glificWebhookService.issueReportTelemetryReasons(any())).thenReturn("{\"reasons\":[]}");

            var response = controller.issueReportTelemetryReasons(introRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("{\"reasons\":[]}");
        }

        @Test
        void telemetryReasonsFallsBackToAJsonErrorBody() {
            when(glificWebhookService.issueReportTelemetryReasons(any())).thenThrow(BOOM);

            var response = controller.issueReportTelemetryReasons(introRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody())
                    .isEqualTo("{\"success\":false,\"message\":\"Supply outage reasons could not be fetched.\"}");
        }

        @Test
        void meterChangeReasonsReturnsTheRawJsonBody() {
            when(glificWebhookService.meterChangeReasons(any())).thenReturn("{\"reasons\":[]}");

            assertThat(controller.meterChangeReasons(introRequest()).getBody()).isEqualTo("{\"reasons\":[]}");
        }

        @Test
        void meterChangeReasonsFallsBackToAJsonErrorBody() {
            when(glificWebhookService.meterChangeReasons(any())).thenThrow(BOOM);

            var response = controller.meterChangeReasons(introRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody())
                    .isEqualTo("{\"success\":false,\"message\":\"Meter change reasons could not be fetched.\"}");
        }
    }

    @Nested
    @DisplayName("reading endpoints")
    class ReadingEndpoints {

        @Test
        void manualReadingPassesThroughTheServiceResponse() {
            ManualReadingRequest request = new ManualReadingRequest();
            request.setContactId(CONTACT);
            when(glificWebhookService.manualReadingMessage(any())).thenReturn(okReading);

            assertThat(controller.manualReading(request).getBody()).isSameAs(okReading);
        }

        @Test
        void manualReadingLogsAFailedResponseWithoutChangingTheStatus() {
            ManualReadingRequest request = new ManualReadingRequest();
            request.setContactId(CONTACT);
            when(glificWebhookService.manualReadingMessage(any()))
                    .thenReturn(CreateReadingResponse.builder().success(false).message("too low").build());

            assertThat(controller.manualReading(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void manualReadingFallsBackWithARejectedEnvelope() {
            ManualReadingRequest request = new ManualReadingRequest();
            request.setContactId(CONTACT);
            when(glificWebhookService.manualReadingMessage(any())).thenThrow(BOOM);

            var response = controller.manualReading(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getQualityStatus()).isEqualTo("REJECTED");
            assertThat(response.getBody().getCorrelationId()).isEqualTo(CONTACT);
        }

        @Test
        void locationPassesThroughAndFallsBack() {
            LocationReadingRequest request = new LocationReadingRequest();
            when(glificWebhookService.locationReadingMessage(any())).thenReturn(okReading);
            assertThat(controller.location(request).getBody()).isSameAs(okReading);

            when(glificWebhookService.locationReadingMessage(any())).thenThrow(BOOM);
            var response = controller.location(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage()).isEqualTo("Location could not be saved.");
            assertThat(response.getBody().getQualityStatus()).isEqualTo("REJECTED");
        }

        @Test
        void updatePreviousReadingPassesThroughAndFallsBack() {
            UpdatedPreviousReadingRequest request = new UpdatedPreviousReadingRequest();
            request.setContactId(CONTACT);

            when(glificWebhookService.updatePreviousReadingMessage(any())).thenReturn(okReading);
            assertThat(controller.updatedPreviousReading(request).getBody()).isSameAs(okReading);

            when(glificWebhookService.updatePreviousReadingMessage(any())).thenThrow(BOOM);
            var response = controller.updatedPreviousReading(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage()).isEqualTo("Previous reading could not be updated.");
        }

        @Test
        void updatePreviousReadingLogsANullServiceResponse() {
            UpdatedPreviousReadingRequest request = new UpdatedPreviousReadingRequest();
            request.setContactId(CONTACT);
            when(glificWebhookService.updatePreviousReadingMessage(any())).thenReturn(null);

            assertThat(controller.updatedPreviousReading(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("POST /trigger-welcome-message")
    class TriggerWelcome {

        @Test
        void sendsTheWelcomeMessageForTheResolvedPhone() {
            TriggerWelcomeMessageRequest request = new TriggerWelcomeMessageRequest();
            request.setPhoneNumber(CONTACT);
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean())).thenReturn(okIntro);

            assertThat(controller.triggerWelcomeMessage(request).getBody()).isSameAs(okIntro);
            verify(welcomeMessageService).triggerWelcomeMessage(eq(CONTACT), eq(false));
        }

        @Test
        void passesTheSingleTenantFlagThrough() {
            TriggerWelcomeMessageRequest request = new TriggerWelcomeMessageRequest();
            request.setPhoneNumber(CONTACT);
            request.setIsSingleTenant(true);
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean())).thenReturn(okIntro);

            controller.triggerWelcomeMessage(request);

            verify(welcomeMessageService).triggerWelcomeMessage(eq(CONTACT), eq(true));
        }

        @Test
        void returnsBadRequestWhenTheWelcomeMessageCannotBePrepared() {
            TriggerWelcomeMessageRequest request = new TriggerWelcomeMessageRequest();
            request.setPhoneNumber(CONTACT);
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean())).thenThrow(BOOM);

            var response = controller.triggerWelcomeMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getCorrelationId()).isEqualTo(CONTACT);
            assertThat(response.getBody().getMessage()).isEqualTo("Welcome message could not be prepared.");
        }

        @Test
        void returnsBadRequestForAMissingRequestBody() {
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean()))
                    .thenThrow(new IllegalArgumentException("phoneNumber/contactId is required"));

            ResponseEntity<IntroResponse> response = controller.triggerWelcomeMessage(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getCorrelationId()).isNull();
        }
    }
}
