package org.arghyam.jalsoochak.telemetry.provider.whatsapp.glific;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.InboundMediaFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.regex.Pattern;

/**
 * Fetches inbound media from Glific's media endpoint. It uses the shared client rather than the
 * guarded one: the media host is configured by us, not chosen by whoever called the webhook.
 */
@Slf4j
@Component
public class GlificMediaFetcher implements InboundMediaFetcher {

    /**
     * A Glific media id is appended to the configured media base URL, so it has to stay a single
     * path segment — otherwise a crafted id walks the path to another endpoint on that host.
     */
    private static final Pattern MEDIA_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final RestTemplate restTemplate;
    private final String glificMediaBaseUrl;
    private final String glificApiToken;

    public GlificMediaFetcher(RestTemplate restTemplate,
                              @Value("${glific.media-base-url:https://api.glific.org/v1/media}") String glificMediaBaseUrl,
                              @Value("${glific.api-token:}") String glificApiToken) {
        this.restTemplate = restTemplate;
        this.glificMediaBaseUrl = glificMediaBaseUrl.endsWith("/")
                ? glificMediaBaseUrl.substring(0, glificMediaBaseUrl.length() - 1)
                : glificMediaBaseUrl;
        this.glificApiToken = glificApiToken;
    }

    @Override
    public ResponseEntity<byte[]> fetch(String mediaId) {
        if (!MEDIA_ID_PATTERN.matcher(mediaId).matches()) {
            log.warn("media_id_rejected reason=\"unexpected characters\"");
            throw new IllegalStateException("Invalid media. Please send a clear meter image.");
        }

        HttpHeaders headers = new HttpHeaders();
        if (glificApiToken != null && !glificApiToken.isBlank()) {
            headers.setBearerAuth(glificApiToken);
        }
        headers.set(HttpHeaders.USER_AGENT, "WaterSupplyBot/1.0");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(glificMediaBaseUrl + "/" + mediaId, HttpMethod.GET, entity, byte[].class);
    }
}
