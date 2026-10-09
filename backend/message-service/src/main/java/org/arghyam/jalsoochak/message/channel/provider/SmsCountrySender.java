package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Sends SMS OTPs via the SMSCountry REST API.
 *
 * <p>Endpoint: {@code POST {baseUrl}/Accounts/{authKey}/SMSes/}</p>
 * <p>Auth: HTTP Basic Auth using authKey:authToken, Base64-encoded.</p>
 * <p>The message text is the account's DLT-approved {@link OtpMessageTemplate}, which defaults to
 * "Your OTP for Jalsoochak login is {otp}. Do not share this OTP. Valid for {expiryMinutes}
 * minutes."</p>
 *
 * <p>PER-TENANT-PROVIDERS: a plain class, not a {@code @Component}. One instance per SMSCountry
 * account — {@code SystemDefaultProviders} builds the system default from {@code smscountry.*}
 * and
 * {@link SmsCountrySenderFactory} builds one per configured tenant — because several accounts now
 * have to coexist in one process, which a singleton bean selected by
 * {@code @ConditionalOnProperty} could not do (O2-2). Everything below the constructor is
 * unchanged: same URL, same headers, same body, same outcomes, which is what makes the system
 * default provably today's behaviour.
 *
 * <p>Instances are immutable and stateless — the {@code WebClient} holds the only pooled resource
 * — so one can be cached and shared across sends for as long as its settings stand.
 */
@Slf4j
public class SmsCountrySender implements SmsSender {

    /** This adapter's identity in the delivery ledger. */
    public static final String PROVIDER_ID = SmsProviderType.SMSCOUNTRY.getWireName();

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(20);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** {@code StatusTime} / {@code ProcessTime}, e.g. {@code "Sep 29 2026 8:02PM"} once spaces collapse. IST. */
    private static final DateTimeFormatter STATUS_TIME = DateTimeFormatter.ofPattern("MMM d yyyy h:mma", Locale.ENGLISH);

    private final WebClient webClient;
    private final SmsCountrySettings settings;
    private final URI endpoint;
    private final OtpMessageTemplate otpTemplate;
    private final boolean dryRun;
    private final String deliveryReportUrl;

    /**
     * @param webClientBuilder the shared builder; each instance builds its own client
     * @param settings         the account this instance sends through
     * @param dryRun           {@code notifications.sms.dry-run}, global to the channel (O2-12)
     * @throws org.arghyam.jalsoochak.message.exception.ProviderNotUsableException if the settings
     *         carry an OTP template that cannot be rendered (O2-15)
     */
    public SmsCountrySender(WebClient.Builder webClientBuilder, SmsCountrySettings settings, boolean dryRun) {
        this(webClientBuilder, settings, dryRun, null);
    }

    /**
     * @param deliveryReportUrl where SMSCountry should push delivery reports, our tracking reference
     *                          appended as {@code ref}; {@code null} or blank to ask for none, in which
     *                          case delivery status comes from {@link #lookupStatuses} alone
     */
    public SmsCountrySender(WebClient.Builder webClientBuilder, SmsCountrySettings settings, boolean dryRun,
                            String deliveryReportUrl) {
        this.webClient = webClientBuilder.build();
        this.settings = settings;
        this.endpoint = endpointFor(settings);
        this.otpTemplate = OtpMessageTemplate.compile(settings.otpTemplate());
        this.dryRun = dryRun;
        this.deliveryReportUrl = deliveryReportUrl == null || deliveryReportUrl.isBlank() ? null : deliveryReportUrl;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supportsStatusLookup() {
        return true;
    }

    /**
     * {@code {baseUrl}/Accounts/{authKey}/SMSes/}, with the key as one percent-encoded path
     * segment.
     *
     * <p>PER-TENANT-PROVIDERS: the key used to come from {@code smscountry.auth-key}, so
     * concatenating it into a string and handing that to {@code WebClient.uri(String)} was
     * harmless. It is now a per-tenant secret a state admin writes, and that overload reads its
     * argument as a URI <em>template</em>: a key of {@code a/../../x} or {@code a?y=} would be
     * parsed as URI syntax and move the POST — which carries that tenant's own basic-auth pair —
     * to a path of the writer's choosing. Building the segment through
     * {@link UriComponentsBuilder} and passing the resulting {@link URI} encodes the key instead of
     * interpreting it, and {@code SmsCountrySenderFactory} rejects a key outside
     * {@code [A-Za-z0-9_-]} before it ever reaches here.
     *
     * <p>The trailing slash is appended explicitly because {@code pathSegment} does not emit one
     * and the endpoint this adapter has always called ends in one.
     */
    private static URI endpointFor(SmsCountrySettings settings) {
        return UriComponentsBuilder.fromUriString(settings.baseUrl())
                .pathSegment("Accounts", settings.authKey(), "SMSes")
                .path("/")
                .build()
                .encode()
                .toUri();
    }

    /**
     * Sends an OTP SMS to the given phone number via the SMSCountry REST API.
     *
     * <p>This method returns a {@code Mono<Boolean>} that completes with {@code true} if the SMS
     * was accepted by SMSCountry, {@code false} on a non-retryable failure (4xx API rejection),
     * or an error signal for transient failures (5xx server errors, network issues).
     *
     * @param phoneNumber  E.164 format without '+' (e.g., "919876543210")
     * @param otp          the one-time password string
     * @param expiryMinutes how long the OTP is valid
     * @return a {@code Mono} emitting {@code true} on success, {@code false} on non-retryable failure,
     *         or an error signal for retryable failures
     */
    @Override
    public Mono<Boolean> sendOtp(String phoneNumber, String otp, int expiryMinutes) {
        return sendOtpForResult(phoneNumber, otp, expiryMinutes).map(SmsSendResult::accepted);
    }

    @Override
    public Mono<SmsSendResult> sendOtpForResult(String phoneNumber, String otp, int expiryMinutes) {
        return sendOtpForResult(phoneNumber, otp, expiryMinutes, null);
    }

    /**
     * As {@link #sendOtpForResult(String, String, int)}, asking SMSCountry to push the delivery report
     * for this message to the configured callback with {@code trackingRef} attached, when a callback is
     * configured.
     */
    @Override
    public Mono<SmsSendResult> sendOtpForResult(String phoneNumber, String otp, int expiryMinutes,
                                                String trackingRef) {
        if (dryRun) {
            log.info("[SMSCountry] Dry-run mode: skipping SMS OTP send");
            log.debug("[SMSCountry] Dry-run: phone={} otp={}", phoneNumber, otp);
            return Mono.just(SmsSendResult.accepted(ProviderAcceptance.untracked("dry-run")));
        }

        String text = otpTemplate.render(otp, expiryMinutes);

        Map<String, String> body = new HashMap<>(Map.of(
                "Text", text,
                "Number", phoneNumber,
                "SenderId", settings.senderId(),
                "Tool", "API",
                "DLTTemplateId", settings.dltTemplateId(),
                "PrincipalEntityId", settings.dltPrincipalEntityId(),
                "DLTHeaderId", settings.dltHeaderId()
        ));
        if (deliveryReportUrl != null && trackingRef != null) {
            body.put("DRNotifyUrl", UriComponentsBuilder.fromUriString(deliveryReportUrl)
                    .queryParam("ref", trackingRef).encode().build().toUriString());
            body.put("DRNotifyHttpMethod", "POST");
        }

        return webClient.post()
                // The URI overload: the String one would expand the path as a URI template.
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .flatMap(response -> {
                    if (response == null) {
                        log.error("[SMSCountry] SMS OTP delivery failed: empty response body");
                        return Mono.just(SmsSendResult.rejected(null, "empty response body"));
                    }

                    JsonNode successNode = response.path("Success");
                    boolean success = successNode.isBoolean()
                            ? successNode.asBoolean()
                            : "true".equalsIgnoreCase(successNode.asText(""));
                    String apiId = response.path("ApiId").asText("");
                    String messageUuid = response.path("MessageUUID").asText("");
                    String message = response.path("Message").asText("");

                    if (!success) {
                        log.error("[SMSCountry] SMS OTP delivery rejected by API: Message='{}' ApiId='{}'",
                                message, apiId);
                        return Mono.just(SmsSendResult.rejected(apiId.isBlank() ? null : apiId, message));
                    }

                    log.info("[SMSCountry] SMS OTP queued successfully: Message='{}' ApiId='{}' MessageUUID='{}'",
                            message, apiId, messageUuid);
                    log.debug("[SMSCountry] SMS OTP queued for phone={}", phoneNumber);
                    return Mono.just(SmsSendResult.accepted(ProviderAcceptance.of(messageUuid, message)));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.error("[SMSCountry] SMS OTP delivery failed: empty response body");
                    return Mono.just(SmsSendResult.rejected(null, "empty response body"));
                }))
                .onErrorResume(WebClientResponseException.class, e -> {
                    if (e.getStatusCode().is5xxServerError()) {
                        // Transient server error — propagate error signal
                        log.error("[SMSCountry] SMS OTP delivery failed with server error: HTTP {}", e.getStatusCode());
                        return Mono.error(new RuntimeException("SMSCountry OTP send failed (server error)", e));
                    }
                    // 4xx: configuration/auth error — non-retryable, a rejection
                    log.error("[SMSCountry] SMS OTP delivery failed: HTTP {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return Mono.just(SmsSendResult.rejected(String.valueOf(e.getStatusCode().value()),
                            "HTTP " + e.getStatusCode().value()));
                })
                .onErrorResume(e -> {
                    // Unexpected errors (network issues, etc.) — propagate error signal
                    log.error("[SMSCountry] SMS OTP delivery failed with unexpected error: {}", e.getMessage(), e);
                    return Mono.error(new RuntimeException("SMSCountry OTP send failed", e));
                });
    }

    /**
     * Asks SMSCountry for each message's detail record, {@code GET …/SMSes/{MessageUUID}/}.
     *
     * <p>The record also carries the recipient's number and the full message text — an OTP. Only
     * {@code MessageUUID}, {@code Status}, {@code StatusTime} and {@code Cost} are read; nothing else is
     * kept or logged.</p>
     */
    @Override
    public List<DeliveryReceipt> lookupStatuses(Collection<String> providerMessageIds, Instant sentFrom,
                                                Instant sentTo) {
        if (providerMessageIds == null || dryRun) {
            return List.of();
        }
        List<DeliveryReceipt> receipts = new ArrayList<>();
        for (String uuid : providerMessageIds) {
            if (uuid == null || !uuid.matches("^[0-9a-fA-F-]{8,64}$")) {
                continue;
            }
            try {
                URI uri = UriComponentsBuilder.fromUri(endpoint).pathSegment(uuid).path("/").build().toUri();
                JsonNode response = webClient.get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(LOOKUP_TIMEOUT);
                JsonNode sms = smsNode(response, uuid);
                if (sms != null) {
                    receipts.add(toReceipt(uuid, sms));
                }
            } catch (Exception e) {
                log.warn("[SMSCountry] status lookup failed for MessageUUID={}: {}", uuid, e.getMessage());
            }
        }
        return receipts;
    }

    /** The detail record in a lookup response: {@code SMS}, the matching entry of {@code SMSes}, or the root. */
    private static JsonNode smsNode(JsonNode response, String uuid) {
        if (response == null) {
            return null;
        }
        if (response.path("SMS").isObject()) {
            return response.path("SMS");
        }
        JsonNode list = response.path("SMSes");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if (uuid.equalsIgnoreCase(item.path("MessageUUID").asText(""))) {
                    return item;
                }
            }
            return null;
        }
        return response.hasNonNull("Status") ? response : null;
    }

    /** One SMSCountry detail record as a receipt. Shared with the delivery-report callback. */
    public static DeliveryReceipt toReceipt(String uuid, JsonNode sms) {
        String status = text(sms, "Status");
        DeliveryState state = mapStatus(status);
        BigDecimal cost = null;
        String currency = null;
        String rawCost = text(sms, "Cost");
        if (rawCost != null) {
            String[] parts = rawCost.trim().split("\\s+");
            try {
                cost = new BigDecimal(parts[0]);
                currency = parts.length > 1 ? parts[1] : null;
            } catch (NumberFormatException ignored) {
                // Not a cost we can read; leave it out.
            }
        }
        return new DeliveryReceipt(PROVIDER_ID, uuid, null, state, status,
                state == DeliveryState.FAILED ? status : null, null,
                parseTime(text(sms, "StatusTime")), cost, currency);
    }

    /**
     * SMSCountry's status words onto {@link DeliveryState}. {@code Delivered} is confirmed by the
     * account's own records; the failure words are the operator outcomes SMS gateways report. Anything
     * else — {@code Sent}, {@code Submitted}, a word not seen yet — is still in flight.
     */
    static DeliveryState mapStatus(String status) {
        if (status == null) {
            return DeliveryState.PENDING;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "delivered", "delivrd" -> DeliveryState.DELIVERED;
            case "failed", "undelivered", "undeliv", "rejected", "rejectd", "expired", "dnd", "blocked",
                 "invalid", "error" -> DeliveryState.FAILED;
            default -> DeliveryState.PENDING;
        };
    }

    /** {@code "Sep 29 2026  8:02PM"} (IST) as an instant, or {@code null}. */
    static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim().replaceAll("\\s+", " "), STATUS_TIME).atZone(IST).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText("").isBlank() ? null : value.asText();
    }

    private String credentials() {
        return Base64.getEncoder().encodeToString(
                (settings.authKey() + ":" + settings.authToken()).getBytes(StandardCharsets.UTF_8));
    }
}
