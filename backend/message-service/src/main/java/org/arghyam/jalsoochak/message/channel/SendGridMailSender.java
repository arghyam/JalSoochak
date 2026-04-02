package org.arghyam.jalsoochak.message.channel;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
@ConditionalOnProperty(name = "notification.mail.provider", havingValue = "sendgrid")
@Slf4j
public class SendGridMailSender implements EmailSender {

    private static final String MAIL_SEND_PATH = "/v3/mail/send";

    private final MailProperties mailProperties;
    private final WebClient webClient;

    @Value("${notification.mail.sendgrid.api-url:https://api.sendgrid.com}")
    private String apiUrl;

    public SendGridMailSender(MailProperties mailProperties, WebClient.Builder webClientBuilder) {
        this.mailProperties = mailProperties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public void send(MailRequest request) {
        String templateId = resolveTemplateId(request.template());

        Map<String, Object> dynamicData = new HashMap<>(request.templateVariables());
        dynamicData.put("logo_image", mailProperties.logoImageUrl());

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
                    .block();

            log.info("[SendGridMailSender] sent template={}", request.template());
        } catch (WebClientResponseException e) {
            log.error("[SendGridMailSender] failure template={}: HTTP {} {}",
                    request.template(), e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException(
                    "SendGrid returned HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            log.error("[SendGridMailSender] failure template={}: {}", request.template(), e.getMessage(), e);
            throw new RuntimeException("SendGridMailSender failure for " + request.template(), e);
        }
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
