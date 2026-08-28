package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Meter-image retrieval: from Glific by media id, or straight from a pre-signed URL, plus the upload
 * to MinIO. Transient failures are retried with a bounded backoff; a 4xx is not, since retrying a
 * rejected request only delays the operator's reply.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificMediaService — download and upload")
class GlificMediaServiceDownloadTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};
    private static final String MEDIA_BASE_URL = "https://api.glific.org/v1/media";

    @Mock
    private MinioService minioService;
    @Mock
    private RestTemplate restTemplate;

    private GlificMediaService service(String token) {
        return new GlificMediaService(minioService, restTemplate, MEDIA_BASE_URL,
                3, 1L, 1L, 5L, token);
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<Void> capturedRequest() {
        ArgumentCaptor<HttpEntity<Void>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                .exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                        captor.capture(), eq(byte[].class));
        return captor.getValue();
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        void rejectsASubmissionCarryingNeitherAMediaIdNorAUrl() {
            assertThatThrownBy(() -> service("token").downloadImage(null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid media");

            assertThatThrownBy(() -> service("token").downloadImage("  ", "  "))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("download by Glific media id")
    class DownloadByMediaId {

        @Test
        void fetchesFromTheConfiguredMediaBaseUrl() throws IOException {
            when(restTemplate.exchange(eq(MEDIA_BASE_URL + "/media-1"), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service("token").downloadImage("media-1", null)).isEqualTo(IMAGE);
        }

        @Test
        void prefersTheMediaIdWhenBothAreSupplied() throws IOException {
            when(restTemplate.exchange(eq(MEDIA_BASE_URL + "/media-1"), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("token").downloadImage("media-1", "https://example.org/img.jpg");

            verify(restTemplate).exchange(eq(MEDIA_BASE_URL + "/media-1"), eq(HttpMethod.GET),
                    any(), eq(byte[].class));
        }

        @Test
        void sendsTheConfiguredApiTokenAsABearerCredential() throws IOException {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("secret-token").downloadImage("media-1", null);

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer secret-token");
        }

        @Test
        void omitsTheAuthorizationHeaderWhenNoTokenIsConfigured() throws IOException {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("  ").downloadImage("media-1", null);

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        void identifiesItselfWithAUserAgent() throws IOException {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("token").downloadImage("media-1", null);

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT))
                    .isEqualTo("WaterSupplyBot/1.0");
        }

        @Test
        void reportsANonOkStatusAsAFailure() {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to download image from Glific");
        }

        @Test
        void reportsAnEmptyBodyAsAFailure() {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(null));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("retry policy")
    class RetryPolicy {

        @Test
        void retriesAServerErrorUpToTheAttemptLimit() {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class)))
                    .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                            "boom", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("after 3 attempts");

            verify(restTemplate, times(3)).exchange(org.mockito.ArgumentMatchers.anyString(),
                    eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void retriesARateLimitResponse() throws IOException {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                            "slow down", HttpHeaders.EMPTY, new byte[0], null))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            // 429 is the one 4xx worth retrying: the request was valid, just too soon.
            assertThat(service("token").downloadImage("media-1", null)).isEqualTo(IMAGE);
            verify(restTemplate, times(2)).exchange(org.mockito.ArgumentMatchers.anyString(),
                    eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void retriesANetworkErrorAndSucceedsOnASubsequentAttempt() throws IOException {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class)))
                    .thenThrow(new ResourceAccessException("connection reset"))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service("token").downloadImage("media-1", null)).isEqualTo(IMAGE);
            verify(restTemplate, times(2)).exchange(org.mockito.ArgumentMatchers.anyString(),
                    eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void doesNotRetryAClientError() {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                            "missing", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("non-retriable");

            verify(restTemplate, times(1)).exchange(org.mockito.ArgumentMatchers.anyString(),
                    eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void makesASingleAttemptWhenRetriesAreDisabled() {
            var noRetries = new GlificMediaService(minioService, restTemplate, MEDIA_BASE_URL,
                    0, 0L, 0L, 0L, "token");
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenThrow(new ResourceAccessException("connection reset"));

            assertThatThrownBy(() -> noRetries.downloadImage("media-1", null))
                    .isInstanceOf(IOException.class);

            // max-attempts is clamped to at least 1, so the call still happens exactly once.
            verify(restTemplate, times(1)).exchange(org.mockito.ArgumentMatchers.anyString(),
                    eq(HttpMethod.GET), any(), eq(byte[].class));
        }
    }

    @Nested
    @DisplayName("download by pre-signed URL")
    class DownloadByUrl {

        @Test
        void fetchesTheGivenUrl() throws IOException {
            when(restTemplate.exchange(eq("https://example.org/img.jpg"), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service("token").downloadImage(null, "https://example.org/img.jpg")).isEqualTo(IMAGE);
        }

        @Test
        void neverSendsTheApiTokenToAThirdPartyUrl() throws IOException {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("secret-token").downloadImage(null, "https://example.org/img.jpg");

            assertThat(capturedRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        void reportsAFailureWithoutTheUrlSoCredentialsStayOutOfLogs() {
            when(restTemplate.exchange(org.mockito.ArgumentMatchers.anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN,
                            "denied", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service("token")
                    .downloadImage(null, "https://example.org/img.jpg?signature=secret"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to download image")
                    .hasMessageNotContaining("signature=secret");
        }
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        void uploadsUnderAContactScopedObjectKeyAndReturnsThePublicUrl() {
            when(minioService.upload(any(), org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn("https://minio/telemetry/bfm/919999900001/1.jpg");

            String url = service("token").uploadImage("919999900001", IMAGE);

            assertThat(url).isEqualTo("https://minio/telemetry/bfm/919999900001/1.jpg");

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(minioService).upload(eq(IMAGE), objectKey.capture());
            assertThat(objectKey.getValue()).startsWith("bfm/919999900001/").endsWith(".jpg");
        }
    }
}
