package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingWebhookAckResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.TriggerWelcomeMessageRequest;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.GlificReadingsAsyncService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.arghyam.jalsoochak.telemetry.service.WelcomeMessageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/telemetry")
public class GlificWebhookController {
    private static final Logger log = LoggerFactory.getLogger(GlificWebhookController.class);
    private final GlificWebhookService glificWebhookService;
    private final GlificReadingsAsyncService glificReadingsAsyncService;
    private final WelcomeMessageService welcomeMessageService;
    private final TelemetrySubmissionAuditService telemetrySubmissionAuditService;

    public GlificWebhookController(GlificWebhookService glificWebhookService) {
        this(glificWebhookService, null, null, null);
    }

    public GlificWebhookController(GlificWebhookService glificWebhookService,
                                   GlificReadingsAsyncService glificReadingsAsyncService) {
        this(glificWebhookService, glificReadingsAsyncService, null, null);
    }

    @Autowired
    public GlificWebhookController(GlificWebhookService glificWebhookService,
                                   GlificReadingsAsyncService glificReadingsAsyncService,
                                   WelcomeMessageService welcomeMessageService,
                                   TelemetrySubmissionAuditService telemetrySubmissionAuditService) {
        this.glificWebhookService = glificWebhookService;
        this.glificReadingsAsyncService = glificReadingsAsyncService;
        this.welcomeMessageService = welcomeMessageService;
        this.telemetrySubmissionAuditService = telemetrySubmissionAuditService;
    }

    @PostMapping(
            value = "/readings/glific",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<ReadingWebhookAckResponse> receive(@RequestBody GlificWebhookRequest glificWebhookRequest) {
        try {
            String jobId = UUID.randomUUID().toString();
            String status = "ACCEPTED";
            String message = "Reading request accepted for asynchronous processing.";
            if (glificReadingsAsyncService != null) {
                glificReadingsAsyncService.enqueueProcessAndResume(glificWebhookRequest, jobId);
            } else {
                CreateReadingResponse response = glificWebhookService.processImage(glificWebhookRequest);
                status = isSuccessful(response) ? "SUCCESS" : "FAILED";
                message = response != null ? response.getMessage() : "Reading request processed.";
            }
            logReadingSubmission(
                    "/api/v1/telemetry/readings/glific",
                    glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null,
                    status,
                    message
            );

            return ResponseEntity.ok(
                    ReadingWebhookAckResponse.builder()
                            .success(true)
                            .status("accepted")
                            .jobId(jobId)
                            .message("Reading request accepted for asynchronous processing.")
                            .build()
            );
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

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }

    }

    @PostMapping("/intro")
    public ResponseEntity<IntroResponse> sendIntro(@RequestBody @Valid IntroRequest introRequest) {
        try {
            IntroResponse response = glificWebhookService.introMessage(introRequest);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error sending intro message: {}", e.getMessage(), e);
            log.debug("Error sending intro message for contactId {}: {}", introRequest.getContactId(), e.getMessage());

            IntroResponse fallbackResponse = IntroResponse.builder()
                    .success(false)
                    .message("Something went wrong. Please try again.")
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(fallbackResponse);
        }
    }

    @PostMapping("/closing")
    public ResponseEntity<ClosingResponse> closingMessage(@RequestBody @Valid ClosingRequest closingRequest) {
        try {
            ClosingResponse response = glificWebhookService.closingMessage(closingRequest);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error sending closing message: {}", e.getMessage(), e);
            log.debug("Error sending closing message for contactId {}: {}", closingRequest.getContactId(), e.getMessage());

            ClosingResponse fallbackResponse = ClosingResponse.builder()
                    .success(false)
                    .message("Something went wrong. Please try again.")
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(fallbackResponse);
        }
    }

    @PostMapping("/language/selection")
    public ResponseEntity<IntroResponse> languageSelection(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.languageSelectionMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing language selection: {}", e.getMessage(), e);
            log.debug("Error preparing language selection for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Language selection could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/selected/language")
    public ResponseEntity<IntroResponse> selectedLanguage(@RequestBody @Valid SelectedLanguageRequest request) {
        try {
            IntroResponse response = glificWebhookService.selectedLanguageMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing selected language: {}", e.getMessage(), e);
            log.debug("Error processing selected language for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Language selection could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/channel/selection")
    public ResponseEntity<IntroResponse> channelSelection(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.channelSelectionMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing channel selection: {}", e.getMessage(), e);
            log.debug("Error preparing channel selection for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Channel selection could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/selected/channel")
    public ResponseEntity<IntroResponse> selectedChannel(@RequestBody @Valid SelectedChannelRequest request) {
        try {
            IntroResponse response = glificWebhookService.selectedChannelMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing selected channel: {}", e.getMessage(), e);
            log.debug("Error processing selected channel for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Channel selection could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/schemes")
    public ResponseEntity<IntroResponse> schemes(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.schemeSelectionMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing scheme selection: {}", e.getMessage(), e);
            log.debug("Error preparing scheme selection for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Scheme selection could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/trigger-welcome-message")
    public ResponseEntity<IntroResponse> triggerWelcomeMessage(
            @RequestBody TriggerWelcomeMessageRequest request) {
        try {
            String phone = request != null ? request.resolvePhoneNumber() : "";
            boolean isSingleTenant = request != null && request.resolveSingleTenant();
            IntroResponse response = welcomeMessageService.triggerWelcomeMessage(phone, isSingleTenant);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String safeContactId = request != null ? request.resolvePhoneNumber() : null;
            log.error("Error preparing welcome message: {}", e.getMessage(), e);
            log.debug("Error preparing welcome message for contactId {}: {}", safeContactId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    IntroResponse.builder()
                            .success(false)
                            .correlationId(safeContactId)
                            .message("Welcome message could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/scheme/selected")
    public ResponseEntity<IntroResponse> selectedScheme(@RequestBody @Valid SelectedSchemeRequest request) {
        try {
            IntroResponse response = glificWebhookService.selectedSchemeMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing selected scheme: {}", e.getMessage(), e);
            log.debug("Error processing selected scheme for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Scheme selection could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/item/selection")
    public ResponseEntity<IntroResponse> itemSelection(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.itemSelectionMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing item selection: {}", e.getMessage(), e);
            log.debug("Error preparing item selection for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Item selection could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/selected/item")
    public ResponseEntity<SelectionResponse> selectedItem(@RequestBody @Valid SelectedItemRequest request) {
        try {
            SelectionResponse response = glificWebhookService.selectedItemMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing selected item: {}", e.getMessage(), e);
            log.debug("Error processing selected item for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    SelectionResponse.builder()
                            .success(false)
                            .selected(null)
                            .message("Item selection could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/meter-change")
    public ResponseEntity<IntroResponse> meterChange(@RequestBody @Valid MeterChangeRequest request) {
        try {
            IntroResponse response = glificWebhookService.meterChangeMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing meter change reasons: {}", e.getMessage(), e);
            log.debug("Error preparing meter change reasons for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Meter change reasons could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/issue-report")
    public ResponseEntity<IntroResponse> issueReportPrompt(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.issueReportPromptMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing issue report prompt: {}", e.getMessage(), e);
            log.debug("Error preparing issue report prompt for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Issue report prompt could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/issue-report/submit")
    public ResponseEntity<IntroResponse> issueReportSubmit(@RequestBody @Valid IssueReportRequest request) {
        try {
            IntroResponse response = glificWebhookService.issueReportSubmitMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving issue report: {}", e.getMessage(), e);
            log.debug("Error saving issue report for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Issue report could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/issue-report/telemetry")
    public ResponseEntity<IntroResponse> issueReportTelemetryPrompt(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.issueReportTelemetryPromptMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing telemetry issue report prompt: {}", e.getMessage(), e);
            log.debug("Error preparing telemetry issue report prompt for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Issue report prompt could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/issue-report/telemetry/submit")
    public ResponseEntity<IntroResponse> issueReportTelemetrySubmit(@RequestBody @Valid IssueReportRequest request) {
        try {
            IntroResponse response = glificWebhookService.issueReportTelemetrySubmitMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving telemetry issue report: {}", e.getMessage(), e);
            log.debug("Error saving telemetry issue report for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Issue report could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping(
            value = "/meter/issue-report",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<String> issueReportTelemetryReasons(@RequestBody @Valid IntroRequest request) {
        try {
            String response = glificWebhookService.issueReportTelemetryReasons(request);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Error fetching telemetry issue report reasons: {}", e.getMessage(), e);
            log.debug("Error fetching telemetry issue report reasons for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"message\":\"Supply outage reasons could not be fetched.\"}");
        }
    }

    @PostMapping(
            value = "/meter/meter-change",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<String> meterChangeReasons(@RequestBody @Valid IntroRequest request) {
        try {
            String response = glificWebhookService.meterChangeReasons(request);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Error fetching meter change reasons: {}", e.getMessage(), e);
            log.debug("Error fetching meter change reasons for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"message\":\"Meter change reasons could not be fetched.\"}");
        }
    }

    @PostMapping("/meter/meter-change/submit")
    public ResponseEntity<IntroResponse> meterChangeSubmit(@RequestBody @Valid MeterChangeRequest request) {
        try {
            IntroResponse response = glificWebhookService.meterChangeSubmitMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving meter change reason: {}", e.getMessage(), e);
            log.debug("Error saving meter change reason for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Meter change reason could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/others")
    public ResponseEntity<IntroResponse> othersPrompt(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = glificWebhookService.othersPromptMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error preparing others prompt: {}", e.getMessage(), e);
            log.debug("Error preparing others prompt for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Others prompt could not be prepared.")
                            .build()
            );
        }
    }

    @PostMapping("/others/submitted")
    public ResponseEntity<IntroResponse> othersSubmitted(@RequestBody @Valid IssueReportRequest request) {
        try {
            IntroResponse response = glificWebhookService.othersSubmittedMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving others issue report: {}", e.getMessage(), e);
            log.debug("Error saving others issue report for contactId {}: {}", request.getContactId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    IntroResponse.builder()
                            .success(false)
                            .message("Issue report could not be saved.")
                            .build()
            );
        }
    }

    @PostMapping("/take-meter-reading")
    public ResponseEntity<IntroResponse> takeMeterReading(@RequestBody @Valid MeterChangeRequest request) {
        try {
            IntroResponse response = glificWebhookService.takeMeterReadingMessage(request);
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
            CreateReadingResponse response = glificWebhookService.manualReadingMessage(request);
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
            CreateReadingResponse response = glificWebhookService.locationReadingMessage(request);
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
            CreateReadingResponse response = glificWebhookService.updatePreviousReadingMessage(request);
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
                        : new TelemetrySubmissionAuditService.SubmissionAuditSnapshot("unknown", null, 0, java.time.LocalDate.now());

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
