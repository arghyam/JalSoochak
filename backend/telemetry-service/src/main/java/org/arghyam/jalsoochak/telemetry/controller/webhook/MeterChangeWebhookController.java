package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingConversationService;
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
 * Chatbot webhooks for reporting a meter replacement.
 */
@RestController
@WebhookRoute
@RequestMapping("/api/v1/telemetry")
public class MeterChangeWebhookController {
    private static final Logger log = LoggerFactory.getLogger(MeterChangeWebhookController.class);
    private final MeterReadingConversationService meterWorkflowService;

    public MeterChangeWebhookController(MeterReadingConversationService meterWorkflowService) {
        this.meterWorkflowService = meterWorkflowService;
    }

    @PostMapping("/meter-change")
    public ResponseEntity<IntroResponse> meterChange(@RequestBody @Valid MeterChangeRequest request) {
        try {
            IntroResponse response = meterWorkflowService.meterChangeMessage(request);
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

    @PostMapping(
            value = "/meter/meter-change",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<String> meterChangeReasons(@RequestBody @Valid IntroRequest request) {
        try {
            String response = meterWorkflowService.meterChangeReasons(request);
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
            IntroResponse response = meterWorkflowService.meterChangeSubmitMessage(request);
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
}
