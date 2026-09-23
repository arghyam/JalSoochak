package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingWebhookAckResponse;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.service.MeterImageWorkflowService;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingConversationService;
import org.arghyam.jalsoochak.telemetry.service.ReadingsAsyncService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Chatbot webhooks that submit or correct a meter reading: the image upload, manual entry, the
 * geotag, the previous-day correction, and the prompt that asks for a reading.
 */
@RestController
@WebhookRoute
@RequestMapping("/api/v1/telemetry")
public class ReadingWebhookController {
    private static final Logger log = LoggerFactory.getLogger(ReadingWebhookController.class);
    private final MeterImageWorkflowService imageWorkflowService;
    private final MeterReadingConversationService meterWorkflowService;
    private final ReadingsAsyncService readingsAsyncService;
    private final TelemetrySubmissionAuditService telemetrySubmissionAuditService;

    public ReadingWebhookController(MeterImageWorkflowService imageWorkflowService,
                                    MeterReadingConversationService meterWorkflowService) {
        this(imageWorkflowService, meterWorkflowService, null, null);
    }

    public ReadingWebhookController(MeterImageWorkflowService imageWorkflowService,
                                    MeterReadingConversationService meterWorkflowService,
                                    ReadingsAsyncService readingsAsyncService) {
        this(imageWorkflowService, meterWorkflowService, readingsAsyncService, null);
    }

    @Autowired
    public ReadingWebhookController(MeterImageWorkflowService imageWorkflowService,
                                    MeterReadingConversationService meterWorkflowService,
                                    ReadingsAsyncService readingsAsyncService,
                                    TelemetrySubmissionAuditService telemetrySubmissionAuditService) {
        this.imageWorkflowService = imageWorkflowService;
        this.meterWorkflowService = meterWorkflowService;
        this.readingsAsyncService = readingsAsyncService;
        this.telemetrySubmissionAuditService = telemetrySubmissionAuditService;
    }

    @PostMapping(
            value = "/readings/glific",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingWebhookAckResponse> receive(@RequestBody GlificWebhookRequest glificWebhookRequest) {
        log.info("POST /api/v1/telemetry/readings/glific received request={}",
                summarizeGlificWebhookRequest(glificWebhookRequest));
        logRawContactIdAtDebug("/api/v1/telemetry/readings/glific",
                glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null);
        try {
            String jobId = UUID.randomUUID().toString();
            String status = "ACCEPTED";
            String message = "Reading request accepted for asynchronous processing.";
            if (readingsAsyncService != null) {
                readingsAsyncService.enqueueProcessAndResume(glificWebhookRequest, jobId);
                log.info("readings_glific queued jobId={} contact={} responseMode=async",
                        jobId,
                        maskPhone(glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null));
            } else {
                CreateReadingResponse response = imageWorkflowService.processImage(glificWebhookRequest);
                status = isSuccessful(response) ? "SUCCESS" : "FAILED";
                message = response != null ? response.getMessage() : "Reading request processed.";
                log.info("readings_glific processed jobId={} contact={} response={}",
                        jobId,
                        maskPhone(glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null),
                        summarizeCreateReadingResponse(response));
            }
            logReadingSubmission(
                    "/api/v1/telemetry/readings/glific",
                    glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null,
                    status,
                    message
            );

            ReadingWebhookAckResponse ackResponse = ReadingWebhookAckResponse.builder()
                    .success(true)
                    .status("accepted")
                    .jobId(jobId)
                    .message("Reading request accepted for asynchronous processing.")
                    .build();
            log.info("readings_glific ack_response contact={} response={}",
                    maskPhone(glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null),
                    summarizeReadingWebhookAckResponse(ackResponse));

            return ResponseEntity.ok(ackResponse);
        } catch (Exception e) {
            String safeContactId = glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null;
            log.error("Error processing webhook: {}", e.getMessage(), e);
            log.debug("Error processing webhook for contactId {}: {}", safeContactId, e.getMessage());
            logReadingSubmission("/api/v1/telemetry/readings/glific", safeContactId, "FAILED", e.getMessage());

            ReadingWebhookAckResponse errorResponse = ReadingWebhookAckResponse.builder()
                    .success(false)
                    .status("error")
                    .jobId(null)
                    .message("Unable to accept webhook request.")
                    .build();
            log.info("readings_glific error_response contact={} response={}",
                    maskPhone(safeContactId),
                    summarizeReadingWebhookAckResponse(errorResponse));

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }

    }

    @PostMapping("/take-meter-reading")
    public ResponseEntity<IntroResponse> takeMeterReading(@RequestBody @Valid MeterChangeRequest request) {
        try {
            IntroResponse response = meterWorkflowService.takeMeterReadingMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing take meter reading prompt: {}", e.getMessage(), e);
            log.debug("Error preparing take meter reading prompt for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Take meter reading prompt could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/manual-reading")
    public ResponseEntity<CreateReadingResponse> manualReading(@RequestBody @Valid ManualReadingRequest request) {
        try {
            CreateReadingResponse response = meterWorkflowService.manualReadingMessage(request);
            logReadingSubmission(
                    "/api/v1/telemetry/manual-reading",
                    request != null ? request.getContactId() : null,
                    isSuccessful(response) ? "SUCCESS" : "FAILED",
                    response != null ? response.getMessage() : "Manual reading processed."
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving manual reading: {}", e.getMessage(), e);
            log.debug("Error saving manual reading for contactId {}: {}", request.getContactId(), e.getMessage());
            logReadingSubmission(
                    "/api/v1/telemetry/manual-reading",
                    request != null ? request.getContactId() : null,
                    "FAILED",
                    e.getMessage()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    CreateReadingResponse.builder()
                            .success(false)
                            .message("Manual reading could not be saved.")
                            .qualityStatus("REJECTED")
                            .correlationId(request.getContactId())
                            .build()
            );
        }
    }

    @PostMapping("/location")
    public ResponseEntity<CreateReadingResponse> location(@RequestBody @Valid LocationReadingRequest request) {
        try {
            CreateReadingResponse response = meterWorkflowService.locationReadingMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String safeContactId = request != null ? request.resolveContactId() : null;
            log.error("Error saving location: {}", e.getMessage(), e);
            log.debug("Error saving location for contactId {}: {}", safeContactId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    CreateReadingResponse.builder()
                            .success(false)
                            .message("Location could not be saved.")
                            .qualityStatus("REJECTED")
                            .correlationId(safeContactId)
                            .build()
            );
        }
    }

    @PostMapping("/update-previous-reading")
    public ResponseEntity<CreateReadingResponse> updatedPreviousReading(@RequestBody @Valid UpdatedPreviousReadingRequest request) {
        try {
            CreateReadingResponse response = meterWorkflowService.updatePreviousReadingMessage(request);
            logReadingSubmission(
                    "/api/v1/telemetry/update-previous-reading",
                    request != null ? request.getContactId() : null,
                    isSuccessful(response) ? "SUCCESS" : "FAILED",
                    response != null ? response.getMessage() : "Previous reading update processed."
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating previous day reading: {}", e.getMessage(), e);
            log.debug("Error updating previous day reading for contactId {}: {}", request.getContactId(), e.getMessage());
            logReadingSubmission(
                    "/api/v1/telemetry/update-previous-reading",
                    request != null ? request.getContactId() : null,
                    "FAILED",
                    e.getMessage()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    CreateReadingResponse.builder()
                            .success(false)
                            .message("Previous reading could not be updated.")
                            .qualityStatus("REJECTED")
                            .correlationId(request.getContactId())
                            .build()
            );
        }
    }

    private void logReadingSubmission(String api, String contactId, String status, String message) {
        TelemetrySubmissionAuditService.SubmissionAuditSnapshot audit =
                telemetrySubmissionAuditService != null
                        ? telemetrySubmissionAuditService.captureForContact(contactId)
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, LocalDate.now(ReadingTime.ZONE));

        log.info(
                "reading_submission api={} status={} phone={} schemeId={} dailyUniqueUserCount={} date={} message=\"{}\"",
                api,
                status,
                audit.maskedPhone(),
                audit.schemeId(),
                audit.dailyUniqueUserCount(),
                audit.date(),
                sanitizeLogMessage(message)
        );
        if (log.isDebugEnabled()) {
            log.debug(
                    "reading_submission_detail api={} status={} rawPhone={} schemeId={} date={}",
                    api,
                    status,
                    sanitizeLogValue(contactId),
                    audit.schemeId(),
                    audit.date()
            );
        }
    }

    private String summarizeGlificWebhookRequest(GlificWebhookRequest request) {
        if (request == null) {
            return "null";
        }
        return String.format(
                "{contactId=%s,messageType=%s,hasMediaId=%s,hasMediaUrl=%s,correlationId=%s,hasConfirmedReading=%s,isMeterReplaced=%s}",
                maskPhone(request.getContactId()),
                sanitizeLogValue(request.getMessageType()),
                request.getMediaId() != null && !request.getMediaId().isBlank(),
                request.getMediaUrl() != null && !request.getMediaUrl().isBlank(),
                sanitizeLogValue(request.getCorrelationId()),
                request.getConfirmedReading() != null && !request.getConfirmedReading().isBlank(),
                request.getIsMeterReplaced()
        );
    }

    private void logRawContactIdAtDebug(String api, String contactId) {
        // Raw phone numbers / contact ids are PII and must never appear in INFO/WARN/ERROR logs. Expose
        // them only at DEBUG so an operator can correlate a masked entry back to the actual number.
        if (log.isDebugEnabled()) {
            log.debug("{} received rawContactId={}", api, sanitizeLogValue(contactId));
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

    private String summarizeReadingWebhookAckResponse(ReadingWebhookAckResponse response) {
        if (response == null) {
            return "null";
        }
        return String.format(
                "{success=%s,status=%s,jobId=%s,message=\"%s\"}",
                response.isSuccess(),
                sanitizeLogValue(response.getStatus()),
                sanitizeLogValue(response.getJobId()),
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

    private boolean isSuccessful(CreateReadingResponse response) {
        return response != null
                && response.isSuccess()
                && !"REJECTED".equalsIgnoreCase(response.getQualityStatus());
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "n/a";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
