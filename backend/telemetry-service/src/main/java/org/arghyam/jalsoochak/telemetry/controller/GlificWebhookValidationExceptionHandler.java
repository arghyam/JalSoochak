package org.arghyam.jalsoochak.telemetry.controller;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Bean-validation failures on the Glific webhook endpoints, answered in the
 * {@code {success, message}} shape those endpoints contract for.
 *
 * <p><strong>Why this class has to exist.</strong> {@link MethodArgumentNotValidException} is raised
 * by the argument resolver <em>before</em> the handler body runs, so the {@code try/catch} inside
 * every {@link GlificWebhookController} method can never see it. Without an advice the rejection
 * falls through to Boot's default error shape ({@code {timestamp,status,error,path}}) — still a
 * {@code 400}, but not the envelope a Glific flow node reads. The sibling
 * {@link TelemetryValidationExceptionHandler} cannot cover this because it is scoped to
 * {@code SingleTenantTelemetryController} and answers in the readings envelope instead.
 *
 * <p><strong>Deliberately narrow.</strong> Only {@code MethodArgumentNotValidException} is handled.
 * This advice applies to all 26 endpoints on {@link GlificWebhookController}, so handling more
 * exception types here (a malformed body, say) would change the response shape on endpoints this
 * change never set out to touch. Anything else keeps its existing behaviour.
 *
 * <p>Today the only constraint that can trigger this is the 255-character cap on
 * {@code IssueReportRequest.issueReason}. Character validation for that field deliberately does
 * <em>not</em> come through here: it stays in the service layer so the operator gets a localised
 * WhatsApp reply and the flow continues, rather than a {@code 400} that stalls it. An over-length
 * reason is abuse rather than a typo, so it earns the hard status and needs no localised copy.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = GlificWebhookController.class)
public class GlificWebhookValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<IntroResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = validationMessage(ex);
        log.warn("Glific webhook request rejected by validation: {}", sanitizeLogMessage(message));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                IntroResponse.builder()
                        .success(false)
                        .message(message)
                        .build()
        );
    }

    private String validationMessage(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("; "));
        return message.isBlank() ? "Validation failed" : message;
    }

    /** Keeps a rejected message on one log line; constraint messages are static, but be safe. */
    private String sanitizeLogMessage(String message) {
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
