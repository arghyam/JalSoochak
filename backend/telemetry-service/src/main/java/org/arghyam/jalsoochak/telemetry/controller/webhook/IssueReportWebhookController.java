package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.service.GlificMeterWorkflowService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chatbot webhooks for reporting a supply outage, a telemetry issue, or a free-text issue.
 */
@RestController
@WebhookRoute
@RequestMapping("/api/v1/telemetry")
public class IssueReportWebhookController {
    private static final Logger log = LoggerFactory.getLogger(IssueReportWebhookController.class);
    private final GlificMeterWorkflowService meterWorkflowService;

    public IssueReportWebhookController(GlificMeterWorkflowService meterWorkflowService) {
        this.meterWorkflowService = meterWorkflowService;
    }

    @PostMapping("/issue-report")
    public ResponseEntity<IntroResponse> issueReportPrompt(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = meterWorkflowService.issueReportPromptMessage(request);
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
            IntroResponse response = meterWorkflowService.issueReportSubmitMessage(request);
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
            IntroResponse response = meterWorkflowService.issueReportTelemetryPromptMessage(request);
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
            IntroResponse response = meterWorkflowService.issueReportTelemetrySubmitMessage(request);
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
            String response = meterWorkflowService.issueReportTelemetryReasons(request);
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

    @PostMapping("/others")
    public ResponseEntity<IntroResponse> othersPrompt(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = meterWorkflowService.othersPromptMessage(request);
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
            IntroResponse response = meterWorkflowService.othersSubmittedMessage(request);
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
}
