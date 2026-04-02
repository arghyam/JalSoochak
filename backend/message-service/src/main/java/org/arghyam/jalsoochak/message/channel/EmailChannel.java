package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.NotificationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Email channel powered by <strong>SendGrid</strong>.
 * <p>
 * Uses the SendGrid v3 Mail Send API.
 * <p>
 * Configure credentials in application.yml:
 * <pre>
 *   notification.channel.email.sendgrid-api-key=YOUR_SENDGRID_API_KEY
 *   notification.channel.email.from-address=noreply@yourdomain.com
 *   notification.channel.email.from-name=Water Management Platform
 * </pre>
 */
/**
 * @deprecated Superseded by {@link org.arghyam.jalsoochak.message.channel.SendGridMailSender}.
 * Kept for reference; excluded from the Spring context by default.
 * Activate with {@code notification.mail.provider=legacy-email} only for temporary rollback.
 */
@Component
@ConditionalOnProperty(name = "notification.mail.provider", havingValue = "legacy-email", matchIfMissing = false)
@Slf4j
public class EmailChannel implements NotificationChannel {

    private static final String SENDGRID_API_URL = "https://api.sendgrid.com/v3/mail/send";

    private final WebClient webClient;

    @Value("${notification.channel.email.sendgrid-api-key:}")
    private String sendGridApiKey;

    @Value("${notification.channel.email.from-address:noreply@example.com}")
    private String fromAddress;

    @Value("${notification.channel.email.from-name:Water Management Platform}")
    private String fromName;

    public EmailChannel(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public boolean send(NotificationRequest request) {
        if (sendGridApiKey == null || sendGridApiKey.isBlank()) {
            log.warn("[EMAIL] SendGrid API key not configured. Skipping email delivery.");
            return false;
        }

        try {
            log.info("[EMAIL] Sending email via SendGrid to {}", request.getRecipient());

            // Build SendGrid v3 payload
            Map<String, Object> payload = Map.of(
                    "personalizations", List.of(
                            Map.of("to", List.of(Map.of("email", request.getRecipient())))
                    ),
                    "from", Map.of("email", fromAddress, "name", fromName),
                    "subject", request.getSubject() != null ? request.getSubject() : "(no subject)",
                    "content", List.of(
                            Map.of("type", "text/plain", "value", request.getBody() != null ? request.getBody() : "")
                    )
            );

            webClient.post()
                    .uri(SENDGRID_API_URL)
                    .header("Authorization", "Bearer " + sendGridApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("[EMAIL] Email delivered successfully via SendGrid");
            return true;
        } catch (Exception ex) {
            log.error("[EMAIL] Failed to deliver email via SendGrid: {}", ex.getMessage(), ex);
            return false;
        }
    }
}
