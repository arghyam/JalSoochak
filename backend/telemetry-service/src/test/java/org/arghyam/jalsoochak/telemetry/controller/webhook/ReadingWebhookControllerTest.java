package org.arghyam.jalsoochak.telemetry.controller.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.GlificReadingsAsyncService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoint behaviour of the reading webhooks.
 *
 * <p>Every endpoint wraps its service call in a catch-all that returns a body the chatbot flow can
 * render instead of a stack trace — an unhandled 500 would strand the operator mid-flow. These tests
 * cover both the pass-through and that fallback for each route.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReadingWebhookController — endpoints")
class ReadingWebhookControllerTest {

    private static final String CONTACT = "919999900001";
    private static final RuntimeException BOOM = new IllegalStateException("downstream failure");

    @Mock
    private GlificWebhookService glificWebhookService;
    @Mock
    private GlificReadingsAsyncService glificReadingsAsyncService;
    @Mock
    private TelemetrySubmissionAuditService auditService;

    private ReadingWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();
    private final CreateReadingResponse okReading =
            CreateReadingResponse.builder().success(true).message("recorded").build();

    @BeforeEach
    void setUp() {
        controller = new ReadingWebhookController(glificWebhookService, glificReadingsAsyncService, auditService);
        // any() rather than anyString(): the controller passes a null contactId straight through when
        // the webhook body is missing, and the real audit service handles that.
        when(auditService.captureForContact(any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", 7L, 1, LocalDate.of(2026, 3, 1)));
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
            GlificWebhookRequest request = request();

            var response = controller.receive(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().isSuccess()).isTrue();
            assertThat(response.getBody().getStatus()).isEqualTo("accepted");
            assertThat(response.getBody().getJobId()).isNotBlank();
            verify(glificReadingsAsyncService)
                    .enqueueProcessAndResume(same(request), eq(response.getBody().getJobId()));
            verify(glificWebhookService, never()).processImage(any());
        }

        @Test
        void processesSynchronouslyWhenNoAsyncServiceIsWired() {
            var syncOnly = new ReadingWebhookController(glificWebhookService);
            when(glificWebhookService.processImage(any())).thenReturn(okReading);

            var response = syncOnly.receive(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(glificWebhookService).processImage(any());
        }

        @Test
        void stillAcksWhenSynchronousProcessingReportsFailure() {
            var syncOnly = new ReadingWebhookController(glificWebhookService);
            when(glificWebhookService.processImage(any()))
                    .thenReturn(CreateReadingResponse.builder().success(false).message("rejected").build());

            assertThat(syncOnly.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void stillAcksWhenSynchronousProcessingReturnsNothing() {
            var syncOnly = new ReadingWebhookController(glificWebhookService);
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
            var twoArg = new ReadingWebhookController(glificWebhookService, glificReadingsAsyncService);

            assertThat(twoArg.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void logsAMaskedEntrypointAndExposesTheRawContactIdOnlyAtDebug() {
            Logger logger = (Logger) LoggerFactory.getLogger(ReadingWebhookController.class);
            Level originalLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);

            try {
                controller.receive(GlificWebhookRequest.builder()
                        .contactId("919999912345")
                        .mediaId("media-123")
                        .build());
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(originalLevel);
            }

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.INFO)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("an entrypoint log for the image webhook, with no raw contactId at INFO")
                    .anyMatch(line -> line.contains("readings/glific received") && line.contains("hasMediaId=true"))
                    .noneMatch(line -> line.contains("919999912345"));
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("the raw contactId, available at DEBUG only")
                    .anyMatch(line -> line.contains("919999912345"));
        }
    }

    @Nested
    @DisplayName("reading endpoints")
    class ReadingEndpoints {

        @Test
        void takeMeterReadingPassesThroughAndFallsBack() {
            MeterChangeRequest request = new MeterChangeRequest();
            request.setContactId(CONTACT);

            when(glificWebhookService.takeMeterReadingMessage(any())).thenReturn(okIntro);
            assertThat(controller.takeMeterReading(request).getBody()).isSameAs(okIntro);

            when(glificWebhookService.takeMeterReadingMessage(any())).thenThrow(BOOM);
            assertThat(controller.takeMeterReading(request).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

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
}
