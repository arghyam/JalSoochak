package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.ReadingRejectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chatbot webhook for an operator who says the reading extracted from their image is wrong.
 *
 * <p>Deliberately outside {@code /readings/**}: that prefix belongs to the vendor ingestion routes,
 * which authenticate with {@code X-Api-Key}, and the chatbot holds only the webhook token.
 */
@RestController
@WebhookRoute
@RequestMapping("/api/v1/telemetry")
public class ReadingCorrectionWebhookController {
    private final ReadingRejectionService readingRejectionService;

    public ReadingCorrectionWebhookController(ReadingRejectionService readingRejectionService) {
        this.readingRejectionService = readingRejectionService;
    }

    /** Always 200: the flow routes on {@code success}, and the service answers every failure in that shape. */
    @PostMapping("/reject-latest-reading")
    public ResponseEntity<IntroResponse> rejectLatestReading(@RequestBody @Valid IntroRequest request) {
        return ResponseEntity.ok(readingRejectionService.rejectTodaysLatestReading(request));
    }
}
