package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.TriggerWelcomeMessageRequest;
import org.arghyam.jalsoochak.telemetry.service.ConversationMessageService;
import org.arghyam.jalsoochak.telemetry.service.WelcomeMessageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chatbot webhooks that open and close a conversation, and the onboarding welcome message.
 */
@RestController
@WebhookRoute
@RequestMapping("/api/v1/telemetry")
public class ConversationWebhookController {
    private static final Logger log = LoggerFactory.getLogger(ConversationWebhookController.class);
    private final ConversationMessageService messageService;
    private final WelcomeMessageService welcomeMessageService;

    public ConversationWebhookController(ConversationMessageService messageService,
                                         WelcomeMessageService welcomeMessageService) {
        this.messageService = messageService;
        this.welcomeMessageService = welcomeMessageService;
    }

    @PostMapping("/intro")
    public ResponseEntity<IntroResponse> sendIntro(@RequestBody @Valid IntroRequest introRequest) {
        try {
            IntroResponse response = messageService.introMessage(introRequest);
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
            ClosingResponse response = messageService.closingMessage(closingRequest);
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
}
