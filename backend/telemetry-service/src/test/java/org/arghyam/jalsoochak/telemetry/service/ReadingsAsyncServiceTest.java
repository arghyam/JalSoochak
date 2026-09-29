package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.MeterImageWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.ConversationResumeGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The async hand-off behind the chatbot readings webhook: the controller acks immediately, this
 * service does the work on the {@code whatsAppSyncExecutor} and then resumes the operator's flow —
 * including when processing blew up, so the operator is never left waiting on a dead flow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReadingsAsyncService")
class ReadingsAsyncServiceTest {

    private static final String CONTACT = "919999900001";
    private static final String JOB_ID = "job-1";

    @Mock
    private MeterImageWorkflowService imageWorkflowService;
    @Mock
    private ConversationResumeGateway conversationResumeGateway;

    /** Runs submitted work inline so the test observes the completed side effects. */
    private final Executor inlineExecutor = Runnable::run;

    private ReadingsAsyncService service() {
        return new ReadingsAsyncService(imageWorkflowService, conversationResumeGateway, inlineExecutor);
    }

    private static MeterImageWebhookRequest request() {
        MeterImageWebhookRequest request = new MeterImageWebhookRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Test
    void submitsTheWorkToTheConfiguredExecutorRatherThanRunningItInline() {
        Executor neverRuns = command -> { /* deliberately drops the task */ };
        new ReadingsAsyncService(imageWorkflowService, conversationResumeGateway, neverRuns)
                .enqueueProcessAndResume(request(), JOB_ID);

        verify(imageWorkflowService, org.mockito.Mockito.never()).processImage(any());
    }

    @Test
    void resumesTheFlowWithTheProcessingResult() {
        CreateReadingResponse processed = CreateReadingResponse.builder()
                .success(true)
                .qualityStatus("ACCEPTED")
                .correlationId("corr-1")
                .meterReading(new BigDecimal("1234"))
                .message("Reading recorded.")
                .build();
        when(imageWorkflowService.processImage(any())).thenReturn(processed);

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(conversationResumeGateway).resumeReadingsFlow(CONTACT, JOB_ID, processed);
    }

    @Test
    void resumesTheFlowWithARejectionWhenProcessingThrows() {
        when(imageWorkflowService.processImage(any())).thenThrow(new IllegalStateException("model down"));

        service().enqueueProcessAndResume(request(), JOB_ID);

        ArgumentCaptor<CreateReadingResponse> resumed = ArgumentCaptor.forClass(CreateReadingResponse.class);
        verify(conversationResumeGateway).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), resumed.capture());

        CreateReadingResponse fallback = resumed.getValue();
        assertThat(fallback.isSuccess()).isFalse();
        assertThat(fallback.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(fallback.getMessage()).isEqualTo("Image could not be processed.");
        assertThat(fallback.getCorrelationId()).isEqualTo(CONTACT);
    }

    @Test
    void toleratesANullResultFromTheImageWorkflow() {
        when(imageWorkflowService.processImage(any())).thenReturn(null);

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(conversationResumeGateway).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), isNull());
    }

    @Test
    void toleratesANullRequest() {
        when(imageWorkflowService.processImage(isNull())).thenReturn(null);

        service().enqueueProcessAndResume(null, JOB_ID);

        verify(conversationResumeGateway).resumeReadingsFlow(isNull(), eq(JOB_ID), isNull());
    }

    @Test
    void stillResumesTheFlowWhenTheFailureResponseIsBuiltFromANullContact() {
        when(imageWorkflowService.processImage(isNull())).thenThrow(new IllegalStateException("boom"));

        service().enqueueProcessAndResume(null, JOB_ID);

        ArgumentCaptor<CreateReadingResponse> resumed = ArgumentCaptor.forClass(CreateReadingResponse.class);
        verify(conversationResumeGateway).resumeReadingsFlow(isNull(), eq(JOB_ID), resumed.capture());
        assertThat(resumed.getValue().isSuccess()).isFalse();
    }

    @Test
    void handlesAResponseWhoseOptionalFieldsAreAllUnset() {
        // The log summariser touches every getter; an all-null response must not break the resume.
        when(imageWorkflowService.processImage(any()))
                .thenReturn(CreateReadingResponse.builder().build());

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(conversationResumeGateway).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), any());
    }

    @Test
    void handlesAResponseCarryingNewlinesInItsMessage() {
        // Log-forging guard: newlines in model output must not break out of the log line.
        when(imageWorkflowService.processImage(any())).thenReturn(CreateReadingResponse.builder()
                .success(true)
                .message("line one\nline two\r\nline three")
                .qualityStatus("ACCEPTED\n")
                .correlationId("corr\n1")
                .build());

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(conversationResumeGateway).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), any());
    }

    /**
     * The flow sends "We have received your image" and only then parks in its wait-for-result node.
     * The provider accepts a resume that arrives before that — and reports success — but the flow
     * never sees it and waits out its full timeout. A floor on the time between accepting the image
     * and resuming keeps a fast result from overtaking the flow.
     */
    @Test
    void holdsAFastResultUntilTheMinimumDelayHasPassed() {
        long[] now = {1_000_000_000L};
        java.util.List<Long> sleptMs = new java.util.ArrayList<>();
        ReadingsAsyncService service = service();
        service.configureResumeTiming(4_000L, () -> now[0], ms -> {
            sleptMs.add(ms);
            now[0] += ms * 1_000_000L;
        });
        org.mockito.Mockito.when(imageWorkflowService.processImage(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    now[0] += 1_000L * 1_000_000L; // processing took 1 s
                    return org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse.builder()
                            .success(true).message("ok").build();
                });

        service.enqueueProcessAndResume(request(), JOB_ID);

        org.assertj.core.api.Assertions.assertThat(sleptMs).containsExactly(3_000L);
        org.mockito.Mockito.verify(conversationResumeGateway)
                .resumeReadingsFlow(org.mockito.ArgumentMatchers.eq(CONTACT), org.mockito.ArgumentMatchers.eq(JOB_ID),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotWaitWhenProcessingAlreadyTookLongerThanTheMinimum() {
        long[] now = {0L};
        java.util.List<Long> sleptMs = new java.util.ArrayList<>();
        ReadingsAsyncService service = service();
        service.configureResumeTiming(4_000L, () -> now[0], sleptMs::add);
        org.mockito.Mockito.when(imageWorkflowService.processImage(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    now[0] += 9_000L * 1_000_000L;
                    return org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse.builder()
                            .success(true).message("ok").build();
                });

        service.enqueueProcessAndResume(request(), JOB_ID);

        org.assertj.core.api.Assertions.assertThat(sleptMs).isEmpty();
    }

    @Test
    void attachesTheClosingLineToASuccessfulResult() {
        ConversationMessageService messageService = org.mockito.Mockito.mock(ConversationMessageService.class);
        when(messageService.closingMessage(any())).thenReturn(
                org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse.builder()
                        .success(true).message("Thank you.").build());
        when(imageWorkflowService.processImage(any()))
                .thenReturn(CreateReadingResponse.builder().success(true).message("ok").build());

        new ReadingsAsyncService(imageWorkflowService, conversationResumeGateway, inlineExecutor, messageService)
                .enqueueProcessAndResume(request(), JOB_ID);

        ArgumentCaptor<CreateReadingResponse> sent = ArgumentCaptor.forClass(CreateReadingResponse.class);
        verify(conversationResumeGateway).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), sent.capture());
        assertThat(sent.getValue().getClosingMessage()).isEqualTo("Thank you.");
    }

    @Test
    void leavesAFailedResultWithoutAClosingLine() {
        ConversationMessageService messageService = org.mockito.Mockito.mock(ConversationMessageService.class);
        when(imageWorkflowService.processImage(any()))
                .thenReturn(CreateReadingResponse.builder().success(false).message("unreadable").build());

        new ReadingsAsyncService(imageWorkflowService, conversationResumeGateway, inlineExecutor, messageService)
                .enqueueProcessAndResume(request(), JOB_ID);

        ArgumentCaptor<CreateReadingResponse> sent = ArgumentCaptor.forClass(CreateReadingResponse.class);
        verify(conversationResumeGateway).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), sent.capture());
        assertThat(sent.getValue().getClosingMessage()).isNull();
        org.mockito.Mockito.verifyNoInteractions(messageService);
    }
}
