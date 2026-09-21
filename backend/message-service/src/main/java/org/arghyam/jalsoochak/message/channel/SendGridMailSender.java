package org.arghyam.jalsoochak.message.channel;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EmailSender} implementation that delivers transactional emails via
 * SendGrid's v3 Mail Send API using dynamic templates.
 *
 * <p>Template IDs come from the {@link SendGridSettings} this instance was built with — the
 * account that owns them. The {@code logo_image} variable is always injected from those settings —
 * it must never be included in the Kafka event payload.
 *
 * <p>PER-TENANT-PROVIDERS: a plain class, not a {@code @Component}. One instance per SendGrid
 * account — {@code SystemDefaultProviders} builds the system default from
 * {@code notification.mail.*} and {@link SendGridMailSenderFactory} builds one per configured
 * tenant — because several accounts now have to coexist in one process, which a singleton bean
 * selected by {@code @ConditionalOnProperty} could not do (O2-2). Everything below the constructor
 * is unchanged: same URL, same headers, same body, same outcomes, which is what makes the system
 * default provably today's behaviour. The three configuration checks the constructor used to make
 * moved to {@code SystemDefaultProviders}, where they still stop the context at startup; the
 * tenant path checks the same things in the factory, where a failure is a fallback rather than an
 * outage (O2-9).
 *
 * <p>Instances are immutable and stateless — the {@code WebClient} holds the only pooled resource
 * — so one can be cached and shared across sends for as long as its settings stand.
 */
@Slf4j
public class SendGridMailSender implements EmailSender {

    private static final String MAIL_SEND_PATH = "/v3/mail/send";

    private final SendGridSettings settings;
    private final WebClient webClient;

    /**
     * @param settings         the account this instance sends through
     * @param webClientBuilder the shared builder; each instance builds its own client
     */
    public SendGridMailSender(SendGridSettings settings, WebClient.Builder webClientBuilder) {
        this.settings = settings;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public void send(MailRequest request) {
        String templateId = resolveTemplateId(request.template());

        Map<String, Object> dynamicData = new HashMap<>(request.templateVariables());
        dynamicData.put("logo_image", settings.logoImageUrl() != null ? settings.logoImageUrl() : "");

        Map<String, Object> payload = Map.of(
                "from", Map.of("email", settings.fromAddress(), "name", settings.fromName()),
                "personalizations", List.of(Map.of(
                        "to", List.of(Map.of("email", request.to())),
                        "dynamic_template_data", dynamicData
                )),
                "template_id", templateId
        );

        try {
            webClient.post()
                    .uri(settings.apiUrl() + MAIL_SEND_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
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
        SendGridSettings.Templates t = settings.templates();
        return switch (template) {
            case PASSWORD_RESET         -> t.passwordReset();
            case REINVITATION           -> t.reinvitation();
            case DEFAULT_INVITATION     -> t.defaultInvitation();
            case SUPER_USER_INVITATION  -> t.superUserInvitation();
            case STATE_ADMIN_INVITATION -> t.stateAdminInvitation();
        };
    }
}
