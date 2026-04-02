package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Base64;
import java.util.Map;

/**
 * Sends SMS OTPs via the SMSCountry REST API.
 *
 * <p>Endpoint: {@code POST {baseUrl}/Accounts/{authKey}/SMSes/}</p>
 * <p>Auth: HTTP Basic Auth using authKey:authToken, Base64-encoded.</p>
 * <p>The message text follows the DLT-approved template:
 * "Your OTP for Jalsoochak login is {otp}. This service is operated by Arghyam.
 * Do not share this OTP. Valid for {expiryMinutes} minutes."</p>
 */
@Service
@Slf4j
public class SmsCountryService {

    private static final String MESSAGE_TEMPLATE =
            "Your OTP for Jalsoochak login is %s. This service is operated by Arghyam. " +
            "Do not share this OTP. Valid for %d minutes.";

    private final WebClient webClient;

    @Value("${smscountry.base-url:https://restapi.smscountry.com/v0.1}")
    private String baseUrl;

    @Value("${smscountry.auth-key:}")
    private String authKey;

    @Value("${smscountry.auth-token:}")
    private String authToken;

    @Value("${smscountry.sender-id}")
    private String senderId;

    @Value("${smscountry.dlt-principal-entity-id:}")
    private String dltPrincipalEntityId;

    @Value("${smscountry.dlt-template-id:}")
    private String dltTemplateId;

    @Value("${smscountry.dlt-header-id:}")
    private String dltHeaderId;

    @Value("${notifications.dry-run:false}")
    private boolean dryRun;

    public SmsCountryService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Sends an OTP SMS to the given phone number.
     *
     * @param phoneNumber  E.164 format without '+' (e.g., "919876543210")
     * @param otp          the one-time password string
     * @param expiryMinutes how long the OTP is valid
     * @return {@code true} if the SMS was accepted by SMSCountry; {@code false} on a non-retryable failure
     * @throws RuntimeException on transient errors that should trigger Kafka retry
     */
    public boolean sendOtp(String phoneNumber, String otp, int expiryMinutes) {
        if (dryRun) {
            log.info("[SMSCountry] Dry-run mode: skipping SMS OTP send");
            log.debug("[SMSCountry] Dry-run: phone={} otp={}", phoneNumber, otp);
            return true;
        }

        String text = MESSAGE_TEMPLATE.formatted(otp, expiryMinutes);
        String url = baseUrl + "/Accounts/" + authKey + "/SMSes/";
        String credentials = Base64.getEncoder().encodeToString((authKey + ":" + authToken).getBytes());

        Map<String, String> body = Map.of(
                "Text", text,
                "Number", phoneNumber,
                "SenderId", senderId,
                "Tool", "API",
                "DLTTemplateId", dltTemplateId,
                "PrincipalEntityId", dltPrincipalEntityId,
                "DLTHeaderId", dltHeaderId
        );

        try {
            JsonNode response = webClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) {
                log.error("[SMSCountry] SMS OTP delivery failed: empty response body");
                return false;
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
                return false;
            }

            log.info("[SMSCountry] SMS OTP queued successfully: Message='{}' ApiId='{}' MessageUUID='{}'",
                    message, apiId, messageUuid);
            log.debug("[SMSCountry] SMS OTP queued for phone={}", phoneNumber);
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                // Transient server error — rethrow so Kafka container will retry
                log.error("[SMSCountry] SMS OTP delivery failed with server error: HTTP {}", e.getStatusCode());
                throw new RuntimeException("SMSCountry OTP send failed (server error)", e);
            }
            // 4xx: configuration/auth error — non-retryable, return false
            log.error("[SMSCountry] SMS OTP delivery failed: HTTP {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("[SMSCountry] SMS OTP delivery failed with unexpected error: {}", e.getMessage(), e);
            throw new RuntimeException("SMSCountry OTP send failed", e);
        }
    }
}
