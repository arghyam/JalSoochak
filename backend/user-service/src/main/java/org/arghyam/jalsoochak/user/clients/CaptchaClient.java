package org.arghyam.jalsoochak.user.clients;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * HTTP client for the CAPTCHA provider's {@code siteverify} endpoint.
 *
 * <p>Mirrors {@link KeycloakClient}: a {@link RestClient} on {@link JdkClientHttpRequestFactory}
 * with timeouts from {@code http-client.*}, and a form-urlencoded POST. Transport / non-2xx
 * failures surface as {@code 502 BAD_GATEWAY} so a provider outage is not mistaken for a failed
 * CAPTCHA (which would be a {@code 400}).
 */
@Slf4j
@Component
public class CaptchaClient {

    private final RestClient restClient;

    public CaptchaClient(@Value("${http-client.connect-timeout-ms}") int connectTimeoutMs,
                         @Value("${http-client.read-timeout-ms}") int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * POSTs {@code secret} + {@code response} form params to the provider and returns the parsed result.
     *
     * @throws ResponseStatusException {@code 502} if the provider cannot be reached or returns a non-2xx status
     */
    public RecaptchaVerifyResponse verify(String verifyUrl, String secret, String token) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("secret", secret);
        body.add("response", token);

        try {
            return restClient.post()
                    .uri(verifyUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RecaptchaVerifyResponse.class);
        } catch (RestClientResponseException e) {
            log.error("CAPTCHA verify error: status={}", e.getStatusCode().value());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CAPTCHA verification request failed", e);
        } catch (Exception e) {
            log.error("CAPTCHA verify error", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CAPTCHA verification request failed", e);
        }
    }
}
