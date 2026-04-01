package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
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
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
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

@RestController
@RequestMapping("/api/v1/webhook")
public class GlificWebhookController {
    private static final Logger log = LoggerFactory.getLogger(GlificWebhookController.class);
    private final GlificWebhookService glificWebhookService;

    public GlificWebhookController(GlificWebhookService glificWebhookService) {
        this.glificWebhookService = glificWebhookService;
    }

    @PostMapping(
            value = "/glific",
            consumes = "application/json",
            produces = "application/json"
    )
    public ResponseEntity<CreateReadingResponse> receive(@RequestBody GlificWebhookRequest glificWebhookRequest) {
        try {
            CreateReadingResponse response = glificWebhookService.processImage(glificWebhookRequest);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String safeContactId = glificWebhookRequest != null ? glificWebhookRequest.getContactId() : null;
            log.error("Error processing webhook: {}", e.getMessage(), e);
            log.debug("Error processing webhook for contactId {}: {}", safeContactId, e.getMessage());

            CreateReadingResponse errorResponse = CreateReadingResponse.builder()
                    .correlationId(safeContactId)
                    .meterReading(null)
                    .qualityStatus("REJECTED")
                    .qualityConfidence(null)
                    .lastConfirmedReading(null)
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

    @PostMapping("/meterChange")
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

    @PostMapping("/issueReport")
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

    @PostMapping("/issueReport/submit")
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

    @PostMapping("/issueReport/telemetry")
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

    @PostMapping("/issueReport/telemetry/submit")
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
            value = "/telemetry/issueReport",
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
            value = "/telemetry/meterChange",
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

    @PostMapping("/telemetry/meterChange/submit")
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

    @PostMapping("/takemeterreading")
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

    @PostMapping("/manualReading")
    public ResponseEntity<CreateReadingResponse> manualReading(@RequestBody @Valid ManualReadingRequest request) {
        try {
            CreateReadingResponse response = glificWebhookService.manualReadingMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving manual reading: {}", e.getMessage(), e);
            log.debug("Error saving manual reading for contactId {}: {}", request.getContactId(), e.getMessage());
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

    @PostMapping("/updatedPreviousReading")
    public ResponseEntity<CreateReadingResponse> updatedPreviousReading(@RequestBody @Valid UpdatedPreviousReadingRequest request) {
        try {
            CreateReadingResponse response = glificWebhookService.updatePreviousReadingMessage(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating previous day reading: {}", e.getMessage(), e);
            log.debug("Error updating previous day reading for contactId {}: {}", request.getContactId(), e.getMessage());
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

}
