package org.arghyam.jalsoochak.message.channel;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.arghyam.jalsoochak.message.exception.PermanentMailException;
import org.arghyam.jalsoochak.message.exception.TransientMailException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * {@link EmailSender} implementation that delivers transactional emails via
 * SendGrid's v3 Mail Send API using dynamic templates.
 *
 * <p>Template IDs are read from {@code notification.mail.sendgrid.templates.*}.
 * The {@code logo_image} variable is always injected from config — it must never
 * be included in the Kafka event payload.
 *
 * <p>Activated when {@code notification.mail.provider=sendgrid} (default).
 */
@Component
@ConditionalOnProperty(name = "notification.mail.provider", havingValue = "sendgrid", matchIfMissing = true)
@Slf4j
public class SendGridMailSender implements EmailSender {

    private static final String MAIL_SEND_PATH = "/v3/mail/send";

    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * Hard ceiling on a single send. {@code block()} parks the Kafka listener thread, and that
     * thread is the only one draining {@code common-topic} — without a bound, one hung TLS
     * connection stalls every notification behind it and eventually breaches
     * {@code max.poll.interval.ms}, evicting the consumer from its group.
     *
     * <p>Kept comfortably under that 600s budget with room for the retry ladder on top.
     */
    private Duration sendTimeout = Duration.ofSeconds(20);

    private final MailProperties mailProperties;
    private final WebClient webClient;

    @Value("${notification.mail.sendgrid.api-url:https://api.sendgrid.com}")
    private String apiUrl;

    public SendGridMailSender(MailProperties mailProperties, WebClient.Builder webClientBuilder) {
        if (mailProperties.sendgrid() == null) {
            throw new IllegalStateException(
                    "Missing SendGrid configuration: notification.mail.sendgrid must be configured when provider=sendgrid");
        }
        
        MailProperties.SendGrid sendgrid = mailProperties.sendgrid();
        if (sendgrid.apiKey() == null || sendgrid.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Missing SendGrid API key: set SENDGRID_API_KEY environment variable when provider=sendgrid");
        }
        if (sendgrid.templates() == null) {
            throw new IllegalStateException(
                    "Missing SendGrid templates: notification.mail.sendgrid.templates must be configured when provider=sendgrid");
        }
        this.mailProperties = mailProperties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public void send(MailRequest request) {
        String templateId = resolveTemplateId(request.template());

        Map<String, Object> dynamicData = new HashMap<>(request.templateVariables());
        dynamicData.put("logo_image", mailProperties.logoImageUrl() != null ? mailProperties.logoImageUrl() : "");

        Map<String, Object> payload = Map.of(
                "from", Map.of("email", mailProperties.fromAddress(), "name", mailProperties.fromName()),
                "personalizations", List.of(Map.of(
                        "to", List.of(Map.of("email", request.to())),
                        "dynamic_template_data", dynamicData
                )),
                "template_id", templateId
        );

        try {
            webClient.post()
                    .uri(apiUrl + MAIL_SEND_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + mailProperties.sendgrid().apiKey())
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(sendTimeout)
                    .block();

            log.info("[SendGridMailSender] sent template={}", request.template());
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            String detail = "SendGrid returned HTTP " + status + ": " + e.getResponseBodyAsString();
            log.error("[SendGridMailSender] failure template={}: HTTP {} {}",
                    request.template(), status, e.getResponseBodyAsString(), e);
            // 429 and 5xx are the provider asking us to come back later — nothing was accepted.
            // Every other 4xx (bad key, malformed payload, refused address) fails identically on replay.
            if (status == TOO_MANY_REQUESTS || e.getStatusCode().is5xxServerError()) {
                throw new TransientMailException(detail, e);
            }
            throw new PermanentMailException(detail, e);
        } catch (WebClientRequestException e) {
            // The request never completed against SendGrid — DNS, connection refused, reset mid-flight.
            log.error("[SendGridMailSender] failure template={}: request never completed: {}",
                    request.template(), e.getMessage(), e);
            throw new TransientMailException(
                    "SendGridMailSender could not reach SendGrid for " + request.template(), e);
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                // Ambiguous: SendGrid may have accepted the payload before we gave up at sendTimeout.
                // Retrying could deliver a second copy, so this is permanent by choice, not by nature.
                log.error("[SendGridMailSender] failure template={}: timed out after {}s, treating as"
                                + " non-retryable to avoid a duplicate send",
                        request.template(), sendTimeout.toSeconds(), e);
                throw new PermanentMailException(
                        "SendGrid send timed out after " + sendTimeout.toSeconds() + "s for " + request.template(), e);
            }
            log.error("[SendGridMailSender] failure template={}: {}", request.template(), e.getMessage(), e);
            throw new PermanentMailException("SendGridMailSender failure for " + request.template(), e);
        }
    }

    /**
     * Reactor wraps the checked {@link TimeoutException} raised by {@code timeout()} before it
     * surfaces from {@code block()}, so the cause chain is what identifies it.
     */
    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private String resolveTemplateId(MailTemplate template) {
        MailProperties.Templates t = mailProperties.sendgrid().templates();
        return switch (template) {
            case PASSWORD_RESET         -> t.passwordReset();
            case REINVITATION           -> t.reinvitation();
            case DEFAULT_INVITATION     -> t.defaultInvitation();
            case SUPER_USER_INVITATION  -> t.superUserInvitation();
            case STATE_ADMIN_INVITATION -> t.stateAdminInvitation();
        };
    }
}
