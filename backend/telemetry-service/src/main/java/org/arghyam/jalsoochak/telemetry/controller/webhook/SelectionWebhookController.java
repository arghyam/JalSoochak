package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.service.GlificSelectionService;
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
 * Chatbot webhooks for the operator's language, channel, scheme and item selections.
 */
@RestController
@WebhookRoute
@RequestMapping("/api/v1/telemetry")
public class SelectionWebhookController {
    private static final Logger log = LoggerFactory.getLogger(SelectionWebhookController.class);
    private final GlificSelectionService selectionService;

    public SelectionWebhookController(GlificSelectionService selectionService) {
        this.selectionService = selectionService;
    }

    @PostMapping("/language/selection")
    public ResponseEntity<IntroResponse> languageSelection(@RequestBody @Valid IntroRequest request) {
        try {
            IntroResponse response = selectionService.languageSelectionMessage(request);
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
            IntroResponse response = selectionService.selectedLanguageMessage(request);
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
            IntroResponse response = selectionService.channelSelectionMessage(request);
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
            IntroResponse response = selectionService.selectedChannelMessage(request);
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
            IntroResponse response = selectionService.schemeSelectionMessage(request);
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

    @PostMapping("/scheme/selected")
    public ResponseEntity<IntroResponse> selectedScheme(@RequestBody @Valid SelectedSchemeRequest request) {
        try {
            IntroResponse response = selectionService.selectedSchemeMessage(request);
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
            IntroResponse response = selectionService.itemSelectionMessage(request);
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
            SelectionResponse response = selectionService.selectedItemMessage(request);
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
}
