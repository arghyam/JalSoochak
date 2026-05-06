package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
public class GlificReadingsAsyncService {

    private final GlificWebhookService glificWebhookService;
    private final GlificFlowResumeService glificFlowResumeService;
    private final Executor glificSyncExecutor;

    public GlificReadingsAsyncService(GlificWebhookService glificWebhookService,
                                      GlificFlowResumeService glificFlowResumeService,
                                      @Qualifier("glificSyncExecutor") Executor glificSyncExecutor) {
        this.glificWebhookService = glificWebhookService;
        this.glificFlowResumeService = glificFlowResumeService;
        this.glificSyncExecutor = glificSyncExecutor;
    }

    /**
     * Returns true if the job was accepted, false if the OCR thread pool is saturated.
     * Callers should return HTTP 429 on false.
     */
    public boolean enqueueProcessAndResume(GlificWebhookRequest request, String jobId) {
        try {
            glificSyncExecutor.execute(() -> processAndResume(request, jobId));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("OCR queue saturated, rejecting jobId={}", jobId);
            return false;
        }
    }

    private void processAndResume(GlificWebhookRequest request, String jobId) {
        String contactId = request != null ? request.getContactId() : null;
        CreateReadingResponse result;

        try {
            result = glificWebhookService.processImage(request);
        } catch (Exception e) {
            log.error("Unhandled exception while processing readings for contactId {} (jobId={}): {}",
                    contactId, jobId, e.getMessage(), e);
            result = CreateReadingResponse.builder()
                    .success(false)
                    .message("Image could not be processed.")
                    .qualityStatus("REJECTED")
                    .correlationId(contactId)
                    .build();
        }

        glificFlowResumeService.resumeReadingsFlow(contactId, jobId, result);
    }
}
