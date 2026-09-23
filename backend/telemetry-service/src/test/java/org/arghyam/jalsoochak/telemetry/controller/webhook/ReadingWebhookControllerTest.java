package org.arghyam.jalsoochak.telemetry.controller.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterImageWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.MeterImageWorkflowService;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingConversationService;
import org.arghyam.jalsoochak.telemetry.service.ReadingsAsyncService;
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
import org.springframework.web.bind.annotation.PostMapping;

import java.time.LocalDate;
import java.util.List;

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
    private MeterImageWorkflowService imageWorkflowService;
    @Mock
    private MeterReadingConversationService meterWorkflowService;
    @Mock
    private ReadingsAsyncService readingsAsyncService;
    @Mock
    private TelemetrySubmissionAuditService auditService;

    private ReadingWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();
    private final CreateReadingResponse okReading =
            CreateReadingResponse.builder().success(true).message("recorded").build();

    @BeforeEach
    void setUp() {
        controller = new ReadingWebhookController(
                imageWorkflowService, meterWorkflowService, readingsAsyncService, auditService);
        // any() rather than anyString(): the controller passes a null contactId straight through when
        // the webhook body is missing, and the real audit service handles that.
        when(auditService.captureForContact(any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", 7L, 1, LocalDate.of(2026, 3, 1)));
    }

    @Nested
    @DisplayName("POST /readings/whatsapp")
    class ReadingsWebhook {

        private MeterImageWebhookRequest request() {
            MeterImageWebhookRequest request = new MeterImageWebhookRequest();
            request.setContactId(CONTACT);
            return request;
        }

        @Test
        void acksImmediatelyAndHandsTheWorkToTheAsyncService() {
            MeterImageWebhookRequest request = request();

            var response = controller.receive(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().isSuccess()).isTrue();
            assertThat(response.getBody().getStatus()).isEqualTo("accepted");
            assertThat(response.getBody().getJobId()).isNotBlank();
            verify(readingsAsyncService)
                    .enqueueProcessAndResume(same(request), eq(response.getBody().getJobId()));
            verify(imageWorkflowService, never()).processImage(any());
        }

        @Test
        void processesSynchronouslyWhenNoAsyncServiceIsWired() {
            var syncOnly = new ReadingWebhookController(imageWorkflowService, meterWorkflowService);
            when(imageWorkflowService.processImage(any())).thenReturn(okReading);

            var response = syncOnly.receive(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(imageWorkflowService).processImage(any());
        }

        @Test
        void stillAcksWhenSynchronousProcessingReportsFailure() {
            var syncOnly = new ReadingWebhookController(imageWorkflowService, meterWorkflowService);
            when(imageWorkflowService.processImage(any()))
                    .thenReturn(CreateReadingResponse.builder().success(false).message("rejected").build());

            assertThat(syncOnly.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void stillAcksWhenSynchronousProcessingReturnsNothing() {
            var syncOnly = new ReadingWebhookController(imageWorkflowService, meterWorkflowService);
            when(imageWorkflowService.processImage(any())).thenReturn(null);

            assertThat(syncOnly.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void returnsAnErrorAckWhenEnqueueingFails() {
            org.mockito.Mockito.doThrow(BOOM)
                    .when(readingsAsyncService).enqueueProcessAndResume(any(), anyString());

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
        void worksWithoutAnAuditService() {
            var noAudit = new ReadingWebhookController(
                    imageWorkflowService, meterWorkflowService, readingsAsyncService);

            assertThat(noAudit.receive(request()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void logsAMaskedEntrypointAndExposesTheRawContactIdOnlyAtDebug() {
            List<ILoggingEvent> events = captureLogs(() -> controller.receive(MeterImageWebhookRequest.builder()
                    .contactId("919999912345")
                    .mediaId("media-123")
                    .build()));

            assertThat(events)
                    .filteredOn(event -> event.getLevel() == Level.INFO)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("an entrypoint log for the image webhook, with no raw contactId at INFO")
                    .anyMatch(line -> line.contains("readings/whatsapp received") && line.contains("hasMediaId=true"))
                    .anyMatch(line -> line.contains("reading_submission api=/api/v1/telemetry/readings/whatsapp "))
                    .noneMatch(line -> line.contains("readings/glific"))
                    .noneMatch(line -> line.contains("919999912345"));
            assertThat(events)
                    .filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("the raw contactId, available at DEBUG only")
                    .anyMatch(line -> line.contains("919999912345"));
        }
    }

    /**
     * The path the chatbot flow calls today. It must keep behaving exactly like
     * {@code /readings/whatsapp} until the flow's webhook node is repointed, and must log the path it
     * was reached on — the legacy path showing zero traffic is what clears it for removal.
     */
    @Nested
    @DisplayName("POST /readings/glific — deprecated alias of /readings/whatsapp")
    @SuppressWarnings("removal")
    class LegacyReadingsWebhook {

        @Test
        void acksAndHandsTheWorkToTheAsyncServiceLikeTheCanonicalPath() {
            MeterImageWebhookRequest request = new MeterImageWebhookRequest();
            request.setContactId(CONTACT);

            var response = controller.receiveLegacy(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().isSuccess()).isTrue();
            assertThat(response.getBody().getStatus()).isEqualTo("accepted");
            verify(readingsAsyncService)
                    .enqueueProcessAndResume(same(request), eq(response.getBody().getJobId()));
            verify(imageWorkflowService, never()).processImage(any());
        }

        @Test
        void logsTheLegacyPathSoItsRemainingTrafficIsVisible() {
            List<ILoggingEvent> events = captureLogs(() -> controller.receiveLegacy(MeterImageWebhookRequest.builder()
                    .contactId("919999912345")
                    .mediaId("media-123")
                    .build()));

            assertThat(events)
                    .filteredOn(event -> event.getLevel() == Level.INFO)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(line -> line.contains("readings/glific received") && line.contains("hasMediaId=true"))
                    .anyMatch(line -> line.contains("reading_submission api=/api/v1/telemetry/readings/glific "))
                    .noneMatch(line -> line.contains("readings/whatsapp"))
                    .noneMatch(line -> line.contains("919999912345"));
        }

        @Test
        void acceptsAndProducesWhatTheCanonicalPathDoes() throws NoSuchMethodException {
            PostMapping canonical = ReadingWebhookController.class
                    .getMethod("receive", MeterImageWebhookRequest.class).getAnnotation(PostMapping.class);
            PostMapping legacy = ReadingWebhookController.class
                    .getMethod("receiveLegacy", MeterImageWebhookRequest.class).getAnnotation(PostMapping.class);

            assertThat(legacy.consumes()).containsExactly(canonical.consumes());
            assertThat(legacy.produces()).containsExactly(canonical.produces());
        }

        @Test
        void isDeprecatedForRemovalSoTheApiDocsFlagIt() throws NoSuchMethodException {
            Deprecated deprecated = ReadingWebhookController.class
                    .getMethod("receiveLegacy", MeterImageWebhookRequest.class).getAnnotation(Deprecated.class);

            assertThat(deprecated).isNotNull();
            assertThat(deprecated.forRemoval()).isTrue();
        }
    }

    @Nested
    @DisplayName("reading endpoints")
    class ReadingEndpoints {

        @Test
        void takeMeterReadingPassesThroughAndFallsBack() {
            MeterChangeRequest request = new MeterChangeRequest();
            request.setContactId(CONTACT);

            when(meterWorkflowService.takeMeterReadingMessage(any())).thenReturn(okIntro);
            assertThat(controller.takeMeterReading(request).getBody()).isSameAs(okIntro);

            when(meterWorkflowService.takeMeterReadingMessage(any())).thenThrow(BOOM);
            assertThat(controller.takeMeterReading(request).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void manualReadingPassesThroughTheServiceResponse() {
            ManualReadingRequest request = new ManualReadingRequest();
            request.setContactId(CONTACT);
            when(meterWorkflowService.manualReadingMessage(any())).thenReturn(okReading);

            assertThat(controller.manualReading(request).getBody()).isSameAs(okReading);
        }

        @Test
        void manualReadingLogsAFailedResponseWithoutChangingTheStatus() {
            ManualReadingRequest request = new ManualReadingRequest();
            request.setContactId(CONTACT);
            when(meterWorkflowService.manualReadingMessage(any()))
                    .thenReturn(CreateReadingResponse.builder().success(false).message("too low").build());

            assertThat(controller.manualReading(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void manualReadingFallsBackWithARejectedEnvelope() {
            ManualReadingRequest request = new ManualReadingRequest();
            request.setContactId(CONTACT);
            when(meterWorkflowService.manualReadingMessage(any())).thenThrow(BOOM);

            var response = controller.manualReading(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getQualityStatus()).isEqualTo("REJECTED");
            assertThat(response.getBody().getCorrelationId()).isEqualTo(CONTACT);
        }

        @Test
        void locationPassesThroughAndFallsBack() {
            LocationReadingRequest request = new LocationReadingRequest();
            when(meterWorkflowService.locationReadingMessage(any())).thenReturn(okReading);
            assertThat(controller.location(request).getBody()).isSameAs(okReading);

            when(meterWorkflowService.locationReadingMessage(any())).thenThrow(BOOM);
            var response = controller.location(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage()).isEqualTo("Location could not be saved.");
            assertThat(response.getBody().getQualityStatus()).isEqualTo("REJECTED");
        }

        @Test
        void updatePreviousReadingPassesThroughAndFallsBack() {
            UpdatedPreviousReadingRequest request = new UpdatedPreviousReadingRequest();
            request.setContactId(CONTACT);

            when(meterWorkflowService.updatePreviousReadingMessage(any())).thenReturn(okReading);
            assertThat(controller.updatedPreviousReading(request).getBody()).isSameAs(okReading);

            when(meterWorkflowService.updatePreviousReadingMessage(any())).thenThrow(BOOM);
            var response = controller.updatedPreviousReading(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage()).isEqualTo("Previous reading could not be updated.");
        }

        @Test
        void updatePreviousReadingLogsANullServiceResponse() {
            UpdatedPreviousReadingRequest request = new UpdatedPreviousReadingRequest();
            request.setContactId(CONTACT);
            when(meterWorkflowService.updatePreviousReadingMessage(any())).thenReturn(null);

            assertThat(controller.updatedPreviousReading(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    /** Runs {@code action} with the controller's logger at DEBUG and returns what it logged. */
    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ReadingWebhookController.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
        return appender.list;
    }
}
