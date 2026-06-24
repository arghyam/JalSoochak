package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

@Service
@Slf4j
public class GlificReadingsAsyncService {

    private final GlificWebhookService glificWebhookService;
    private final GlificFlowResumeService glificFlowResumeService;
    private final Executor glificSyncExecutor;
    private final GlificReadingFailurePolicyService glificReadingFailurePolicyService;

    public GlificReadingsAsyncService(GlificWebhookService glificWebhookService,
                                      GlificFlowResumeService glificFlowResumeService,
                                      @Qualifier("glificSyncExecutor") Executor glificSyncExecutor,
                                      GlificReadingFailurePolicyService glificReadingFailurePolicyService) {
        this.glificWebhookService = glificWebhookService;
        this.glificFlowResumeService = glificFlowResumeService;
        this.glificSyncExecutor = glificSyncExecutor;
        this.glificReadingFailurePolicyService = glificReadingFailurePolicyService;
    }

    public void enqueueProcessAndResume(GlificWebhookRequest request, String jobId) {
        glificSyncExecutor.execute(() -> processAndResume(request, jobId));
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

        if (glificReadingFailurePolicyService != null) {
            result = glificReadingFailurePolicyService.applyToGlificReadingResult(contactId, result);
        }
        glificFlowResumeService.resumeReadingsFlow(contactId, jobId, result);
    }
}
