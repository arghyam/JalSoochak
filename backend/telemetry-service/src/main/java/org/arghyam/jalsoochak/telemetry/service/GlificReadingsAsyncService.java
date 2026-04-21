package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class GlificReadingsAsyncService {

    private final Executor glificSyncExecutor;
    private final GlificImageWorkflowService glificImageWorkflowService;
    private final GlificFlowResumeService glificFlowResumeService;

    public GlificReadingsAsyncService(@Qualifier("glificSyncExecutor") Executor glificSyncExecutor,
                                      GlificImageWorkflowService glificImageWorkflowService,
                                      GlificFlowResumeService glificFlowResumeService) {
        this.glificSyncExecutor = glificSyncExecutor;
        this.glificImageWorkflowService = glificImageWorkflowService;
        this.glificFlowResumeService = glificFlowResumeService;
    }

    public String enqueue(GlificWebhookRequest request) {
        String jobId = UUID.randomUUID().toString();
        glificSyncExecutor.execute(() -> processAndResume(jobId, request));
        return jobId;
    }

    private void processAndResume(String jobId, GlificWebhookRequest request) {
        String contactId = request != null ? request.getContactId() : null;
        try {
            CreateReadingResponse response = glificImageWorkflowService.processImage(request);
            glificFlowResumeService.resumeReadingsFlow(contactId, jobId, response);
        } catch (Exception e) {
            log.error("Async processing failed for jobId {} contactId {}", jobId, contactId, e);
            try {
                CreateReadingResponse fallbackResponse = CreateReadingResponse.builder()
                        .success(false)
                        .correlationId(contactId)
                        .qualityStatus("REJECTED")
                        .message("Image could not be processed.")
                        .build();
                glificFlowResumeService.resumeReadingsFlow(contactId, jobId, fallbackResponse);
            } catch (Exception resumeError) {
                log.error("Failed to resume flow after async processing error for jobId {} contactId {}",
                        jobId, contactId, resumeError);
            }
        }
    }
}
