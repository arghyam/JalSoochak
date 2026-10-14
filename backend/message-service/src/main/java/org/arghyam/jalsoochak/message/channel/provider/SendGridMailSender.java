package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

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

    /** This adapter's identity in the delivery ledger. */
    public static final String PROVIDER_ID = EmailProviderType.SENDGRID.getWireName();

    /**
     * The {@code custom_args} key our ledger reference travels under. SendGrid echoes every
     * {@code custom_args} pair on each Event Webhook event for the message, which is how a delivery
     * report finds its ledger row without a lookup.
     */
    public static final String TRACKING_REF_ARG = "ledger_ref";

    /** The response header carrying SendGrid's id for the accepted message. */
    static final String MESSAGE_ID_HEADER = "X-Message-Id";

    private static final String MESSAGES_PATH = "/v3/messages";
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(20);

    private final SendGridSettings settings;
    private final WebClient webClient;
    private final boolean statusLookupEnabled;

    /**
     * @param settings         the account this instance sends through
     * @param webClientBuilder the shared builder; each instance builds its own client
     */
    public SendGridMailSender(SendGridSettings settings, WebClient.Builder webClientBuilder) {
        this(settings, webClientBuilder, false);
    }

    /**
     * @param statusLookupEnabled whether {@link #lookupStatuses} may query the Email Activity API — a
     *                            paid add-on on SendGrid's side, so off unless the account has it
     */
    public SendGridMailSender(SendGridSettings settings, WebClient.Builder webClientBuilder,
                              boolean statusLookupEnabled) {
        this.settings = settings;
        this.webClient = webClientBuilder.build();
        this.statusLookupEnabled = statusLookupEnabled;
    }

    @Override
    public boolean supportsStatusLookup() {
        return statusLookupEnabled;
    }

    /**
     * Asks the Email Activity API about each message, one query per id: {@code msg_id} there is the
     * full {@code sg_message_id}, of which the {@code X-Message-Id} we hold is the prefix.
     *
     * <p>{@code delivered} → DELIVERED (READ once an open was recorded), {@code not_delivered} →
     * FAILED, {@code processing} → PENDING. The recipient's address in the response is never read.</p>
     */
    @Override
    public List<DeliveryReceipt> lookupStatuses(Collection<String> providerMessageIds, Instant sentFrom,
                                                Instant sentTo) {
        if (!statusLookupEnabled || providerMessageIds == null) {
            return List.of();
        }
        List<DeliveryReceipt> receipts = new ArrayList<>();
        for (String id : providerMessageIds) {
            if (id == null || id.isBlank() || !id.matches("^[A-Za-z0-9_.-]+$")) {
                continue;
            }
            try {
                URI uri = UriComponentsBuilder.fromUriString(settings.apiUrl() + MESSAGES_PATH)
                        .queryParam("limit", 1)
                        .queryParam("query", "msg_id LIKE \"" + id + "%\"")
                        .encode()
                        .build()
                        .toUri();
                JsonNode body = webClient.get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(LOOKUP_TIMEOUT);
                JsonNode messages = body == null ? null : body.path("messages");
                if (messages == null || !messages.isArray() || messages.isEmpty()) {
                    continue;
                }
                receipts.add(toReceipt(id, messages.get(0)));
            } catch (Exception e) {
                log.warn("[SendGridMailSender] status lookup failed for providerMsgId={}: {}", id, e.getMessage());
            }
        }
        return receipts;
    }

    private static DeliveryReceipt toReceipt(String id, JsonNode message) {
        String status = message.path("status").asText("");
        DeliveryState state = switch (status.toLowerCase(Locale.ROOT)) {
            case "delivered" -> message.path("opens_count").asInt(0) > 0 ? DeliveryState.READ : DeliveryState.DELIVERED;
            case "not_delivered" -> DeliveryState.FAILED;
            default -> DeliveryState.PENDING;
        };
        Instant at = null;
        String lastEvent = message.path("last_event_time").asText(null);
        if (lastEvent != null) {
            try {
                at = Instant.parse(lastEvent);
            } catch (DateTimeParseException ignored) {
                // Leave it to the ledger to stamp its own time.
            }
        }
        return new DeliveryReceipt(PROVIDER_ID, id, null, state, status.isBlank() ? null : status,
                state == DeliveryState.FAILED ? status : null, null, at, null, null);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderAcceptance send(MailRequest request) {
        String templateId = resolveTemplateId(request.template());

        Map<String, Object> dynamicData = new HashMap<>(request.templateVariables());
        dynamicData.put("logo_image", settings.logoImageUrl() != null ? settings.logoImageUrl() : "");

        Map<String, Object> personalization = new HashMap<>();
        personalization.put("to", List.of(Map.of("email", request.to())));
        personalization.put("dynamic_template_data", dynamicData);
        if (request.trackingRef() != null) {
            personalization.put("custom_args", Map.of(TRACKING_REF_ARG, request.trackingRef()));
        }

        Map<String, Object> payload = Map.of(
                "from", fromBlock(),
                "personalizations", List.of(personalization),
                "template_id", templateId
        );

        try {
            ResponseEntity<Void> response = webClient.post()
                    .uri(settings.apiUrl() + MAIL_SEND_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            String messageId = response == null ? null : response.getHeaders().getFirst(MESSAGE_ID_HEADER);
            String status = response == null ? null : String.valueOf(response.getStatusCode().value());
            log.info("[SendGridMailSender] sent template={} providerMsgId={}", request.template(),
                    messageId == null ? "none" : messageId);
            return ProviderAcceptance.of(messageId, status);
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

    /**
     * The {@code from} object, carrying {@code name} only when there is one.
     *
     * <p>A mutable map, for the reason {@code logo_image} above uses one: {@code fromName} is
     * optional both on a tenant's settings write and on {@code notification.mail.from-name}, so
     * {@link SendGridMailSenderFactory}'s platform fallback can resolve to null, and
     * {@code Map.of} rejects a null value. Omitting the key sends under the verified address alone,
     * which is what SendGrid does with a {@code from} that names no display name — the alternative
     * would be to refuse to build the sender, and a missing display name must not cost a tenant its
     * own account (O2-9).
     */
    private Map<String, String> fromBlock() {
        Map<String, String> from = new HashMap<>();
        from.put("email", settings.fromAddress());
        if (settings.fromName() != null && !settings.fromName().isBlank()) {
            from.put("name", settings.fromName());
        }
        return from;
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
