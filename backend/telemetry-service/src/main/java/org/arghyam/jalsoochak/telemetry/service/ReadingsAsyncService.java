package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterImageWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.ConversationResumeGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

@Service
@Slf4j
public class ReadingsAsyncService {

    private final MeterImageWorkflowService imageWorkflowService;
    private final ConversationResumeGateway conversationResumeGateway;
    private final Executor whatsAppSyncExecutor;
    private final ConversationMessageService conversationMessageService;

    /** Sleeps for the given milliseconds; a seam so tests need not wait in real time. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Floor on the time between accepting an image and resuming the flow. The flow sends its "please
     * wait" message and only then parks in its wait-for-result node; the provider accepts a resume
     * that lands before that — and reports success — but the flow never sees it and sits out its full
     * timeout. 0 disables the floor.
     */
    @Value("${whatsapp.resume.min-delay-ms:4000}")
    private long minResumeDelayMs;
    private LongSupplier nanoClock = System::nanoTime;
    private Sleeper sleeper = Thread::sleep;

    public ReadingsAsyncService(MeterImageWorkflowService imageWorkflowService,
                                ConversationResumeGateway conversationResumeGateway,
                                Executor whatsAppSyncExecutor) {
        this(imageWorkflowService, conversationResumeGateway, whatsAppSyncExecutor, null);
    }

    @Autowired
    public ReadingsAsyncService(MeterImageWorkflowService imageWorkflowService,
                                ConversationResumeGateway conversationResumeGateway,
                                @Qualifier("whatsAppSyncExecutor") Executor whatsAppSyncExecutor,
                                ConversationMessageService conversationMessageService) {
        this.imageWorkflowService = imageWorkflowService;
        this.conversationResumeGateway = conversationResumeGateway;
        this.whatsAppSyncExecutor = whatsAppSyncExecutor;
        this.conversationMessageService = conversationMessageService;
    }

    /** Test seam: the resume floor, the clock it is measured on, and how the wait is spent. */
    void configureResumeTiming(long minResumeDelayMs, LongSupplier nanoClock, Sleeper sleeper) {
        this.minResumeDelayMs = minResumeDelayMs;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    public void enqueueProcessAndResume(MeterImageWebhookRequest request, String jobId) {
        long acceptedAtNanos = nanoClock.getAsLong();
        whatsAppSyncExecutor.execute(() -> processAndResume(request, jobId, acceptedAtNanos));
    }

    private void waitForResumeFloor(long acceptedAtNanos, String jobId) {
        long elapsedMs = (nanoClock.getAsLong() - acceptedAtNanos) / 1_000_000L;
        long remainingMs = minResumeDelayMs - elapsedMs;
        if (remainingMs <= 0) {
            return;
        }
        try {
            sleeper.sleep(remainingMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while holding the resume for jobId={}; resuming now", jobId);
        }
    }

    private void processAndResume(MeterImageWebhookRequest request, String jobId, long acceptedAtNanos) {
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

        attachClosingMessage(contactId, result);
        waitForResumeFloor(acceptedAtNanos, jobId);
        conversationResumeGateway.resumeReadingsFlow(contactId, jobId, result);
    }

    /** The closing line for a recorded reading, so the flow can end without a /closing call. */
    private void attachClosingMessage(String contactId, CreateReadingResponse result) {
        if (conversationMessageService == null || result == null || !result.isSuccess()) {
            return;
        }
        try {
            ClosingResponse closing = conversationMessageService.closingMessage(
                    ClosingRequest.builder().contactId(contactId).build());
            if (closing != null && closing.isSuccess()) {
                result.setClosingMessage(closing.getMessage());
            }
        } catch (Exception e) {
            log.warn("Could not attach the closing message to the reading result: {}", e.getMessage());
        }
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
