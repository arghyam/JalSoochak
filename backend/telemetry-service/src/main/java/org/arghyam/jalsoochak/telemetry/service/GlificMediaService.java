package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GlificMediaService {

    /**
     * A Glific media id is appended to the configured media base URL, so it has to stay a single
     * path segment — otherwise a crafted id walks the path to another endpoint on that host.
     */
    private static final Pattern MEDIA_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final MinioService minioService;
    private final RestTemplate restTemplate;
    private final RestTemplate mediaFetchRestTemplate;
    private final MediaUrlValidator mediaUrlValidator;
    private final String glificApiToken;
    private final String glificMediaBaseUrl;
    private final int mediaDownloadRetryMaxAttempts;
    private final long mediaDownloadRetryInitialBackoffMs;
    private final long mediaDownloadRetryMaxBackoffMs;
    private final long mediaDownloadRetryMaxTotalBackoffMs;
    private final long mediaDownloadMaxBytes;

    public GlificMediaService(MinioService minioService,
                              RestTemplate restTemplate,
                              @Qualifier("mediaFetchRestTemplate") RestTemplate mediaFetchRestTemplate,
                              MediaUrlValidator mediaUrlValidator,
                              @Value("${glific.media-base-url:https://api.glific.org/v1/media}") String glificMediaBaseUrl,
                              @Value("${media-download.retry.max-attempts:3}") int mediaDownloadRetryMaxAttempts,
                              @Value("${media-download.retry.initial-backoff-ms:300}") long mediaDownloadRetryInitialBackoffMs,
                              @Value("${media-download.retry.max-backoff-ms:200}") long mediaDownloadRetryMaxBackoffMs,
                              @Value("${media-download.retry.max-total-backoff-ms:400}") long mediaDownloadRetryMaxTotalBackoffMs,
                              @Value("${media-download.max-bytes:20971520}") long mediaDownloadMaxBytes,
                              @Value("${glific.api-token:}") String glificApiToken) {
        this.minioService = minioService;
        this.restTemplate = restTemplate;
        this.mediaFetchRestTemplate = mediaFetchRestTemplate;
        this.mediaUrlValidator = mediaUrlValidator;
        this.glificMediaBaseUrl = glificMediaBaseUrl.endsWith("/")
                ? glificMediaBaseUrl.substring(0, glificMediaBaseUrl.length() - 1)
                : glificMediaBaseUrl;
        this.mediaDownloadRetryMaxAttempts = Math.max(1, mediaDownloadRetryMaxAttempts);
        this.mediaDownloadRetryInitialBackoffMs = Math.max(0L, mediaDownloadRetryInitialBackoffMs);
        this.mediaDownloadRetryMaxBackoffMs = Math.max(0L, mediaDownloadRetryMaxBackoffMs);
        this.mediaDownloadRetryMaxTotalBackoffMs = Math.max(0L, mediaDownloadRetryMaxTotalBackoffMs);
        this.mediaDownloadMaxBytes = mediaDownloadMaxBytes;
        this.glificApiToken = glificApiToken;
    }

    public byte[] downloadImage(String mediaId, String mediaUrl) throws IOException {
        boolean hasImage = (mediaId != null && !mediaId.isBlank()) || (mediaUrl != null && !mediaUrl.isBlank());
        if (!hasImage) {
            throw new IllegalStateException("Invalid media. Please send a clear meter image.");
        }
        return mediaId != null && !mediaId.isBlank()
                ? downloadImageFromGlific(mediaId)
                : downloadImageFromUrl(mediaUrl);
    }

    public String uploadImage(String contactId, byte[] imageBytes) {
        String objectKey = "bfm/" + contactId + "/" + System.currentTimeMillis() + ".jpg";
        String imageStorageUrl = minioService.upload(imageBytes, objectKey);
        log.info("imageStorageUrl: {}", imageStorageUrl);
        log.debug("Image uploaded for contactId {} with objectKey {}", contactId, objectKey);
        return imageStorageUrl;
    }

    private byte[] downloadImageFromGlific(String mediaId) throws IOException {
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

        return executeDownloadWithRetry(
                () -> restTemplate.exchange(glificMediaBaseUrl + "/" + mediaId, HttpMethod.GET, entity, byte[].class),
                "glific:" + mediaId,
                "Failed to download image from Glific"
        );
    }

    /**
     * Fetches a meter image from a URL that came in on the webhook payload — that is, from a
     * destination an unauthenticated caller chose. It goes through the guarded client rather than the
     * shared one, and the URL is vetted before the request rather than only parsed by it.
     */
    private byte[] downloadImageFromUrl(String url) throws IOException {
        URI mediaUri = mediaUrlValidator.validate(url);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "WaterSupplyBot/1.0");

        return executeDownloadWithRetry(
                () -> mediaFetchRestTemplate.execute(
                        mediaUri,
                        HttpMethod.GET,
                        request -> request.getHeaders().addAll(headers),
                        this::readBoundedResponse),
                // The media URL is pre-signed and carries access credentials, so logs get a digest of it.
                "url:" + digest(url),
                "Failed to download image"
        );
    }

    /**
     * Reads the body under a hard byte ceiling. A caller-supplied URL can point at a response of any
     * size — including one that never ends — and buffering it whole would put the ceiling on the
     * heap instead.
     */
    private ResponseEntity<byte[]> readBoundedResponse(ClientHttpResponse response) throws IOException {
        if (mediaDownloadMaxBytes > 0 && response.getHeaders().getContentLength() > mediaDownloadMaxBytes) {
            throw new MediaTooLargeException("Media exceeds the configured size limit");
        }
        byte[] body = readAtMost(response.getBody());
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(body);
    }

    private byte[] readAtMost(InputStream body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = body.read(chunk)) != -1) {
            total += read;
            if (mediaDownloadMaxBytes > 0 && total > mediaDownloadMaxBytes) {
                throw new MediaTooLargeException("Media exceeds the configured size limit");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * Unchecked on purpose: an oversized response is a property of the media, not a transient fault,
     * so it must escape the retry loop instead of being attempted again.
     */
    static class MediaTooLargeException extends IllegalStateException {
        MediaTooLargeException(String message) {
            super(message);
        }
    }

    private byte[] executeDownloadWithRetry(Supplier<ResponseEntity<byte[]>> requestSupplier,
                                            String mediaRef,
                                            String failurePrefix) throws IOException {
        long totalBackoffMs = 0L;
        for (int attempt = 1; attempt <= mediaDownloadRetryMaxAttempts; attempt++) {
            try {
                ResponseEntity<byte[]> response = requestSupplier.get();

                if (response == null) {
                    throw new IOException(failurePrefix + ", no response");
                }
                if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                    throw new IOException(failurePrefix + ", status: " + response.getStatusCode());
                }
                return response.getBody();
            } catch (RestClientException e) {
                boolean retriable = isRetriableException(e);
                if (!retriable) {
                    log.warn("Media download for {} failed with non-retriable error: {}", mediaRef, describe(e));
                    throw new IOException(failurePrefix + " due to a non-retriable error", e);
                }
                if (attempt == mediaDownloadRetryMaxAttempts) {
                    log.warn("Media download for {} failed after {} attempts: {}", mediaRef, attempt, describe(e));
                    throw new IOException(failurePrefix + " after " + attempt + " attempts", e);
                }
                long backoffMs = computeBackoffMs(attempt, totalBackoffMs);
                totalBackoffMs += backoffMs;
                log.warn("Media download attempt {} failed for {}. Retrying in {} ms", attempt, mediaRef, backoffMs);
                sleepBackoff(backoffMs);
            }
        }
        throw new IOException(failurePrefix);
    }

    /**
     * Renders an exception for logging using only metadata that cannot carry media content or credentials.
     * The message of a {@link RestClientResponseException} embeds the response body, so it is never logged.
     */
    private String describe(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return exception.getClass().getSimpleName() + " status=" + responseException.getStatusCode().value();
        }
        return exception.getClass().getSimpleName();
    }

    /**
     * Stable short digest, so repeated failures for the same media can be correlated across log lines
     * without the underlying value appearing in them.
     */
    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private boolean isRetriableException(RestClientException exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        if (exception instanceof RestClientResponseException responseException) {
            int statusCode = responseException.getRawStatusCode();
            return statusCode == 429 || statusCode >= 500;
        }
        return true;
    }

    private long computeBackoffMs(int attempt, long totalBackoffMs) {
        if (mediaDownloadRetryInitialBackoffMs <= 0L || mediaDownloadRetryMaxBackoffMs <= 0L || mediaDownloadRetryMaxTotalBackoffMs <= 0L) {
            return 0L;
        }
        long exponentialBackoff = mediaDownloadRetryInitialBackoffMs;
        for (int i = 1; i < attempt; i++) {
            exponentialBackoff = Math.min(Long.MAX_VALUE / 2, exponentialBackoff * 2);
        }
        long cappedPerAttemptBackoff = Math.min(exponentialBackoff, mediaDownloadRetryMaxBackoffMs);
        long remainingBackoffBudget = Math.max(0L, mediaDownloadRetryMaxTotalBackoffMs - totalBackoffMs);
        return Math.min(cappedPerAttemptBackoff, remainingBackoffBudget);
    }

    private void sleepBackoff(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
