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

    public GlificReadingsAsyncService(GlificWebhookService glificWebhookService,
                                      GlificFlowResumeService glificFlowResumeService,
                                      @Qualifier("glificSyncExecutor") Executor glificSyncExecutor) {
        this.glificWebhookService = glificWebhookService;
        this.glificFlowResumeService = glificFlowResumeService;
        this.glificSyncExecutor = glificSyncExecutor;
    }

    public void enqueueProcessAndResume(GlificWebhookRequest request, String jobId) {
        glificSyncExecutor.execute(() -> processAndResume(request, jobId));
    }

    private void processAndResume(GlificWebhookRequest request, String jobId) {
        String contactId = request != null ? request.getContactId() : null;
        CreateReadingResponse result;

        try {
            result = glificWebhookService.processImage(request);
            log.info("readings_glific async_processed jobId={} contact={} result={}",
                    jobId,
                    maskPhone(contactId),
                    summarizeCreateReadingResponse(result));
        } catch (Exception e) {
            log.error("Unhandled exception while processing readings for contactId {} (jobId={}): {}",
                    maskPhone(contactId), jobId, e.getMessage(), e);
            if (log.isDebugEnabled()) {
                log.debug("Unhandled exception while processing readings rawContactId={} jobId={}",
                        sanitizeLogValue(contactId), jobId);
            }
            result = CreateReadingResponse.builder()
                    .success(false)
                    .message("Image could not be processed.")
                    .qualityStatus("REJECTED")
                    .correlationId(contactId)
                    .build();
            log.info("readings_glific async_error_response jobId={} contact={} result={}",
                    jobId,
                    maskPhone(contactId),
                    summarizeCreateReadingResponse(result));
        }

        glificFlowResumeService.resumeReadingsFlow(contactId, jobId, result);
    }

    private String summarizeCreateReadingResponse(CreateReadingResponse response) {
        if (response == null) {
            return "null";
        }
        return String.format(
                "{success=%s,qualityStatus=%s,correlationId=%s,meterReading=%s,qualityConfidence=%s,lastConfirmedReading=%s,message=\"%s\"}",
                response.isSuccess(),
                sanitizeLogValue(response.getQualityStatus()),
                sanitizeLogValue(response.getCorrelationId()),
                response.getMeterReading(),
                response.getQualityConfidence(),
                response.getLastConfirmedReading(),
                sanitizeLogMessage(response.getMessage())
        );
    }

    private String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return "n/a";
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    private String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "n/a";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
