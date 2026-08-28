package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
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
 * The async hand-off behind the Glific readings webhook: the controller acks immediately, this
 * service does the work on the {@code glificSyncExecutor} and then resumes the operator's flow —
 * including when processing blew up, so the operator is never left waiting on a dead flow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificReadingsAsyncService")
class GlificReadingsAsyncServiceTest {

    private static final String CONTACT = "919999900001";
    private static final String JOB_ID = "job-1";

    @Mock
    private GlificWebhookService glificWebhookService;
    @Mock
    private GlificFlowResumeService glificFlowResumeService;

    /** Runs submitted work inline so the test observes the completed side effects. */
    private final Executor inlineExecutor = Runnable::run;

    private GlificReadingsAsyncService service() {
        return new GlificReadingsAsyncService(glificWebhookService, glificFlowResumeService, inlineExecutor);
    }

    private static GlificWebhookRequest request() {
        GlificWebhookRequest request = new GlificWebhookRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Test
    void submitsTheWorkToTheConfiguredExecutorRatherThanRunningItInline() {
        Executor neverRuns = command -> { /* deliberately drops the task */ };
        new GlificReadingsAsyncService(glificWebhookService, glificFlowResumeService, neverRuns)
                .enqueueProcessAndResume(request(), JOB_ID);

        verify(glificWebhookService, org.mockito.Mockito.never()).processImage(any());
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
        when(glificWebhookService.processImage(any())).thenReturn(processed);

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(glificFlowResumeService).resumeReadingsFlow(CONTACT, JOB_ID, processed);
    }

    @Test
    void resumesTheFlowWithARejectionWhenProcessingThrows() {
        when(glificWebhookService.processImage(any())).thenThrow(new IllegalStateException("model down"));

        service().enqueueProcessAndResume(request(), JOB_ID);

        ArgumentCaptor<CreateReadingResponse> resumed = ArgumentCaptor.forClass(CreateReadingResponse.class);
        verify(glificFlowResumeService).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), resumed.capture());

        CreateReadingResponse fallback = resumed.getValue();
        assertThat(fallback.isSuccess()).isFalse();
        assertThat(fallback.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(fallback.getMessage()).isEqualTo("Image could not be processed.");
        assertThat(fallback.getCorrelationId()).isEqualTo(CONTACT);
    }

    @Test
    void toleratesANullResultFromTheWebhookService() {
        when(glificWebhookService.processImage(any())).thenReturn(null);

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(glificFlowResumeService).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), isNull());
    }

    @Test
    void toleratesANullRequest() {
        when(glificWebhookService.processImage(isNull())).thenReturn(null);

        service().enqueueProcessAndResume(null, JOB_ID);

        verify(glificFlowResumeService).resumeReadingsFlow(isNull(), eq(JOB_ID), isNull());
    }

    @Test
    void stillResumesTheFlowWhenTheFailureResponseIsBuiltFromANullContact() {
        when(glificWebhookService.processImage(isNull())).thenThrow(new IllegalStateException("boom"));

        service().enqueueProcessAndResume(null, JOB_ID);

        ArgumentCaptor<CreateReadingResponse> resumed = ArgumentCaptor.forClass(CreateReadingResponse.class);
        verify(glificFlowResumeService).resumeReadingsFlow(isNull(), eq(JOB_ID), resumed.capture());
        assertThat(resumed.getValue().isSuccess()).isFalse();
    }

    @Test
    void handlesAResponseWhoseOptionalFieldsAreAllUnset() {
        // The log summariser touches every getter; an all-null response must not break the resume.
        when(glificWebhookService.processImage(any()))
                .thenReturn(CreateReadingResponse.builder().build());

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(glificFlowResumeService).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), any());
    }

    @Test
    void handlesAResponseCarryingNewlinesInItsMessage() {
        // Log-forging guard: newlines in model output must not break out of the log line.
        when(glificWebhookService.processImage(any())).thenReturn(CreateReadingResponse.builder()
                .success(true)
                .message("line one\nline two\r\nline three")
                .qualityStatus("ACCEPTED\n")
                .correlationId("corr\n1")
                .build());

        service().enqueueProcessAndResume(request(), JOB_ID);

        verify(glificFlowResumeService).resumeReadingsFlow(eq(CONTACT), eq(JOB_ID), any());
    }
}
