package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.function.Supplier;

@Slf4j
@Service
public class GlificMediaService {

    private final MinioService minioService;
    private final RestTemplate restTemplate;
    private final String glificApiToken;
    private final String glificMediaBaseUrl;
    private final int mediaDownloadRetryMaxAttempts;
    private final long mediaDownloadRetryInitialBackoffMs;
    private final long mediaDownloadRetryMaxBackoffMs;
    private final long mediaDownloadRetryMaxTotalBackoffMs;

    public GlificMediaService(MinioService minioService,
                              RestTemplate restTemplate,
                              @Value("${glific.media-base-url:https://api.glific.org/v1/media}") String glificMediaBaseUrl,
                              @Value("${media-download.retry.max-attempts:3}") int mediaDownloadRetryMaxAttempts,
                              @Value("${media-download.retry.initial-backoff-ms:300}") long mediaDownloadRetryInitialBackoffMs,
                              @Value("${media-download.retry.max-backoff-ms:200}") long mediaDownloadRetryMaxBackoffMs,
                              @Value("${media-download.retry.max-total-backoff-ms:400}") long mediaDownloadRetryMaxTotalBackoffMs,
                              @Value("${glific.api-token:}") String glificApiToken) {
        this.minioService = minioService;
        this.restTemplate = restTemplate;
        this.glificMediaBaseUrl = glificMediaBaseUrl.endsWith("/")
                ? glificMediaBaseUrl.substring(0, glificMediaBaseUrl.length() - 1)
                : glificMediaBaseUrl;
        this.mediaDownloadRetryMaxAttempts = Math.max(1, mediaDownloadRetryMaxAttempts);
        this.mediaDownloadRetryInitialBackoffMs = Math.max(0L, mediaDownloadRetryInitialBackoffMs);
        this.mediaDownloadRetryMaxBackoffMs = Math.max(0L, mediaDownloadRetryMaxBackoffMs);
        this.mediaDownloadRetryMaxTotalBackoffMs = Math.max(0L, mediaDownloadRetryMaxTotalBackoffMs);
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
        HttpHeaders headers = new HttpHeaders();
        if (glificApiToken != null && !glificApiToken.isBlank()) {
            headers.setBearerAuth(glificApiToken);
        }
        headers.set(HttpHeaders.USER_AGENT, "WaterSupplyBot/1.0");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return executeDownloadWithRetry(
                () -> restTemplate.exchange(glificMediaBaseUrl + "/" + mediaId, HttpMethod.GET, entity, byte[].class),
                "Glific mediaId " + mediaId,
                "Failed to download image from Glific"
        );
    }

    private byte[] downloadImageFromUrl(String url) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "WaterSupplyBot/1.0");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return executeDownloadWithRetry(
                () -> restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class),
                "URL " + url,
                "Failed to download image"
        );
    }

    private byte[] executeDownloadWithRetry(Supplier<ResponseEntity<byte[]>> requestSupplier,
                                            String targetLabel,
                                            String failurePrefix) throws IOException {
        long totalBackoffMs = 0L;
        for (int attempt = 1; attempt <= mediaDownloadRetryMaxAttempts; attempt++) {
            try {
                ResponseEntity<byte[]> response = requestSupplier.get();

                if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                    throw new IOException(failurePrefix + ", status: " + response.getStatusCode());
                }
                return response.getBody();
            } catch (RestClientException e) {
                boolean retriable = isRetriableException(e);
                if (!retriable) {
                    throw new IOException(failurePrefix + " due to non-retriable error: " + e.getMessage(), e);
                }
                if (attempt == mediaDownloadRetryMaxAttempts) {
                    throw new IOException(failurePrefix + " after " + attempt + " attempts: " + e.getMessage(), e);
                }
                long backoffMs = computeBackoffMs(attempt, totalBackoffMs);
                totalBackoffMs += backoffMs;
                log.warn("Media download attempt {} failed for {}. Retrying in {} ms", attempt, targetLabel, backoffMs);
                sleepBackoff(backoffMs);
            }
        }
        throw new IOException(failurePrefix);
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
