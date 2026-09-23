package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterImageWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.ConversationResumeGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

@Service
@Slf4j
public class ReadingsAsyncService {

    private final MeterImageWorkflowService imageWorkflowService;
    private final ConversationResumeGateway conversationResumeGateway;
    private final Executor whatsAppSyncExecutor;

    public ReadingsAsyncService(MeterImageWorkflowService imageWorkflowService,
                                ConversationResumeGateway conversationResumeGateway,
                                @Qualifier("whatsAppSyncExecutor") Executor whatsAppSyncExecutor) {
        this.imageWorkflowService = imageWorkflowService;
        this.conversationResumeGateway = conversationResumeGateway;
        this.whatsAppSyncExecutor = whatsAppSyncExecutor;
    }

    public void enqueueProcessAndResume(MeterImageWebhookRequest request, String jobId) {
        whatsAppSyncExecutor.execute(() -> processAndResume(request, jobId));
    }

    private void processAndResume(MeterImageWebhookRequest request, String jobId) {
        String contactId = request != null ? request.getContactId() : null;
        CreateReadingResponse result;

        try {
            result = imageWorkflowService.processImage(request);
            log.info("readings_whatsapp async_processed jobId={} contact={} result={}",
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
            log.info("readings_whatsapp async_error_response jobId={} contact={} result={}",
                    jobId,
                    maskPhone(contactId),
                    summarizeCreateReadingResponse(result));
        }

        conversationResumeGateway.resumeReadingsFlow(contactId, jobId, result);
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
