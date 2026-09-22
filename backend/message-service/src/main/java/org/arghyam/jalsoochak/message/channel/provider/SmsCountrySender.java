package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

    private final WebClient webClient;
    private final SmsCountrySettings settings;
    private final URI endpoint;
    private final OtpMessageTemplate otpTemplate;
    private final boolean dryRun;

    /**
     * @param webClientBuilder the shared builder; each instance builds its own client
     * @param settings         the account this instance sends through
     * @param dryRun           {@code notifications.sms.dry-run}, global to the channel (O2-12)
     * @throws org.arghyam.jalsoochak.message.exception.ProviderNotUsableException if the settings
     *         carry an OTP template that cannot be rendered (O2-15)
     */
    public SmsCountrySender(WebClient.Builder webClientBuilder, SmsCountrySettings settings, boolean dryRun) {
        this.webClient = webClientBuilder.build();
        this.settings = settings;
        this.endpoint = endpointFor(settings);
        this.otpTemplate = OtpMessageTemplate.compile(settings.otpTemplate());
        this.dryRun = dryRun;
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
        if (dryRun) {
            log.info("[SMSCountry] Dry-run mode: skipping SMS OTP send");
            log.debug("[SMSCountry] Dry-run: phone={} otp={}", phoneNumber, otp);
            return Mono.just(true);
        }

        String text = otpTemplate.render(otp, expiryMinutes);
        String credentials = Base64.getEncoder().encodeToString(
                (settings.authKey() + ":" + settings.authToken()).getBytes(StandardCharsets.UTF_8));

        Map<String, String> body = Map.of(
                "Text", text,
                "Number", phoneNumber,
                "SenderId", settings.senderId(),
                "Tool", "API",
                "DLTTemplateId", settings.dltTemplateId(),
                "PrincipalEntityId", settings.dltPrincipalEntityId(),
                "DLTHeaderId", settings.dltHeaderId()
        );

        return webClient.post()
                // The URI overload: the String one would expand the path as a URI template.
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .flatMap(response -> {
                    if (response == null) {
                        log.error("[SMSCountry] SMS OTP delivery failed: empty response body");
                        return Mono.just(false);
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
                        return Mono.just(false);
                    }

                    log.info("[SMSCountry] SMS OTP queued successfully: Message='{}' ApiId='{}' MessageUUID='{}'",
                            message, apiId, messageUuid);
                    log.debug("[SMSCountry] SMS OTP queued for phone={}", phoneNumber);
                    return Mono.just(true);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.error("[SMSCountry] SMS OTP delivery failed: empty response body");
                    return Mono.just(false);
                }))
                .onErrorResume(WebClientResponseException.class, e -> {
                    if (e.getStatusCode().is5xxServerError()) {
                        // Transient server error — propagate error signal
                        log.error("[SMSCountry] SMS OTP delivery failed with server error: HTTP {}", e.getStatusCode());
                        return Mono.error(new RuntimeException("SMSCountry OTP send failed (server error)", e));
                    }
                    // 4xx: configuration/auth error — non-retryable, return false
                    log.error("[SMSCountry] SMS OTP delivery failed: HTTP {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return Mono.just(false);
                })
                .onErrorResume(e -> {
                    // Unexpected errors (network issues, etc.) — propagate error signal
                    log.error("[SMSCountry] SMS OTP delivery failed with unexpected error: {}", e.getMessage(), e);
                    return Mono.error(new RuntimeException("SMSCountry OTP send failed", e));
                });
    }
}
