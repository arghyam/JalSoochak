package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.security.MediaUrlNotAllowedException;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
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
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Meter-image retrieval: from Glific by media id, or straight from a pre-signed URL, plus the upload
 * to MinIO. Transient failures are retried with a bounded backoff; a 4xx is not, since retrying a
 * rejected request only delays the operator's reply.
 *
 * <p>The two sources use different clients on purpose — a media id resolves against the configured
 * Glific host, while a URL is caller-controlled and goes out on the guarded client.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificMediaService — download and upload")
class GlificMediaServiceDownloadTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};
    private static final String MEDIA_BASE_URL = "https://api.glific.org/v1/media";
    private static final String MEDIA_URL = "https://example.org/img.jpg";

    @Mock
    private MinioService minioService;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplate mediaFetchRestTemplate;
    @Mock
    private MediaUrlValidator mediaUrlValidator;

    private GlificMediaService service(String token) {
        when(mediaUrlValidator.validate(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        return new GlificMediaService(minioService, restTemplate, mediaFetchRestTemplate, mediaUrlValidator,
                MEDIA_BASE_URL, 3, 1L, 1L, 5L, 20_971_520L, token);
    }

    /** Stubs the guarded fetch with the given outcomes in order: a ResponseEntity, or a Throwable. */
    @SuppressWarnings("unchecked")
    private void mediaFetchYields(Object... outcomes) {
        var stub = when((ResponseEntity<byte[]>) mediaFetchRestTemplate.execute(any(URI.class), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class)));
        for (Object outcome : outcomes) {
            stub = outcome instanceof Throwable failure
                    ? stub.thenThrow(failure)
                    : stub.thenReturn((ResponseEntity<byte[]>) outcome);
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyMediaFetches(int times) {
        verify(mediaFetchRestTemplate, times(times)).execute(any(URI.class), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class));
    }

    /** Replays the captured callback against a stand-in request to see the headers it would set. */
    @SuppressWarnings("unchecked")
    private HttpHeaders headersAppliedToMediaFetch() throws IOException {
        ArgumentCaptor<RequestCallback> captor = ArgumentCaptor.forClass(RequestCallback.class);
        verify(mediaFetchRestTemplate, org.mockito.Mockito.atLeastOnce())
                .execute(any(URI.class), eq(HttpMethod.GET), captor.capture(), any(ResponseExtractor.class));
        MockClientHttpRequest request = new MockClientHttpRequest();
        captor.getValue().doWithRequest(request);
        return request.getHeaders();
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<Void> capturedGlificRequest() {
        ArgumentCaptor<HttpEntity<Void>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                .exchange(anyString(), eq(HttpMethod.GET), captor.capture(), eq(byte[].class));
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

        @Test
        void rejectsAMediaIdThatWouldWalkOutOfItsPathSegment() {
            // Appended to the media base URL, so "../.." would address a different Glific endpoint.
            assertThatThrownBy(() -> service("token").downloadImage("../../admin", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid media");

            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(byte[].class));
        }

        @Test
        void acceptsTheOrdinaryMediaIdShapes() throws IOException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service("token").downloadImage("555", null)).isEqualTo(IMAGE);
            assertThat(service("token").downloadImage("9f8e-4c1a-b2d3", null)).isEqualTo(IMAGE);
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

            service("token").downloadImage("media-1", MEDIA_URL);

            verify(restTemplate).exchange(eq(MEDIA_BASE_URL + "/media-1"), eq(HttpMethod.GET),
                    any(), eq(byte[].class));
            verifyMediaFetches(0);
        }

        @Test
        void sendsTheConfiguredApiTokenAsABearerCredential() throws IOException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("secret-token").downloadImage("media-1", null);

            assertThat(capturedGlificRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer secret-token");
        }

        @Test
        void omitsTheAuthorizationHeaderWhenNoTokenIsConfigured() throws IOException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("  ").downloadImage("media-1", null);

            assertThat(capturedGlificRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        void identifiesItselfWithAUserAgent() throws IOException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(ResponseEntity.ok(IMAGE));

            service("token").downloadImage("media-1", null);

            assertThat(capturedGlificRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT))
                    .isEqualTo("WaterSupplyBot/1.0");
        }

        @Test
        void reportsANonOkStatusAsAFailure() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(), eq(byte[].class))).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to download image from Glific");
        }

        @Test
        void reportsAnEmptyBodyAsAFailure() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
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
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                            "boom", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("after 3 attempts");

            verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void retriesARateLimitResponse() throws IOException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                            "slow down", HttpHeaders.EMPTY, new byte[0], null))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            // 429 is the one 4xx worth retrying: the request was valid, just too soon.
            assertThat(service("token").downloadImage("media-1", null)).isEqualTo(IMAGE);
            verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void retriesANetworkErrorAndSucceedsOnASubsequentAttempt() throws IOException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(new ResourceAccessException("connection reset"))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service("token").downloadImage("media-1", null)).isEqualTo(IMAGE);
            verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void doesNotRetryAClientError() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                            "missing", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service("token").downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("non-retriable");

            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class));
        }

        @Test
        void makesASingleAttemptWhenRetriesAreDisabled() {
            var noRetries = new GlificMediaService(minioService, restTemplate, mediaFetchRestTemplate,
                    mediaUrlValidator, MEDIA_BASE_URL, 0, 0L, 0L, 0L, 20_971_520L, "token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                    .thenThrow(new ResourceAccessException("connection reset"));

            assertThatThrownBy(() -> noRetries.downloadImage("media-1", null))
                    .isInstanceOf(IOException.class);

            // max-attempts is clamped to at least 1, so the call still happens exactly once.
            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class));
        }
    }

    @Nested
    @DisplayName("download by pre-signed URL")
    class DownloadByUrl {

        @Test
        void fetchesTheValidatedUrlOnTheGuardedClient() throws IOException {
            mediaFetchYields(ResponseEntity.ok(IMAGE));

            assertThat(service("token").downloadImage(null, MEDIA_URL)).isEqualTo(IMAGE);

            verify(mediaUrlValidator).validate(MEDIA_URL);
            // A caller-supplied destination must never go out on the client shared with FlowVision,
            // Glific and MinIO, whose hosts are allowed to be internal.
            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(byte[].class));
        }

        @Test
        void refusesAUrlTheValidatorRejectsWithoutIssuingARequest() {
            GlificMediaService service = service("token");
            when(mediaUrlValidator.validate(anyString()))
                    .thenThrow(new MediaUrlNotAllowedException("non-public address"));

            assertThatThrownBy(() -> service.downloadImage(null, "http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .hasMessageContaining("Invalid media");

            verifyMediaFetches(0);
        }

        @Test
        void doesNotRetryAUrlTheValidatorRejects() {
            GlificMediaService service = service("token");
            when(mediaUrlValidator.validate(anyString()))
                    .thenThrow(new MediaUrlNotAllowedException("host not allowlisted"));

            assertThatThrownBy(() -> service.downloadImage(null, "https://evil.example/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class);

            verify(mediaUrlValidator, times(1)).validate(anyString());
        }

        @Test
        void neverSendsTheApiTokenToAThirdPartyUrl() throws IOException {
            mediaFetchYields(ResponseEntity.ok(IMAGE));

            service("secret-token").downloadImage(null, MEDIA_URL);

            HttpHeaders sent = headersAppliedToMediaFetch();
            assertThat(sent.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
            assertThat(sent.getFirst(HttpHeaders.USER_AGENT)).isEqualTo("WaterSupplyBot/1.0");
        }

        @Test
        void reportsAFailureWithoutTheUrlSoCredentialsStayOutOfLogs() {
            mediaFetchYields(HttpClientErrorException.create(HttpStatus.FORBIDDEN,
                    "denied", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service("token")
                    .downloadImage(null, "https://example.org/img.jpg?signature=secret"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to download image")
                    .hasMessageNotContaining("signature=secret");
        }

        @Test
        void retriesATransientFailureOnTheGuardedClient() throws IOException {
            GlificMediaService service = service("token");
            mediaFetchYields(new ResourceAccessException("timeout"), ResponseEntity.ok(IMAGE));

            assertThat(service.downloadImage(null, MEDIA_URL)).isEqualTo(IMAGE);
        }
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        void uploadsUnderAContactScopedObjectKeyAndReturnsThePublicUrl() {
            when(minioService.upload(any(), anyString()))
                    .thenReturn("https://minio/telemetry/bfm/919999900001/1.jpg");

            String url = service("token").uploadImage("919999900001", IMAGE);

            assertThat(url).isEqualTo("https://minio/telemetry/bfm/919999900001/1.jpg");

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(minioService).upload(eq(IMAGE), objectKey.capture());
            assertThat(objectKey.getValue()).startsWith("bfm/919999900001/").endsWith(".jpg");
        }
    }
}
