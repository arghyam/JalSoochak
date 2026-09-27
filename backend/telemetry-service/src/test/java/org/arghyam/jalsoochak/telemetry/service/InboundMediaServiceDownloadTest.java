package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.config.StorageProperties;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.InboundMediaFetcher;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlNotAllowedException;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.storage.ObjectStorageService;
import org.arghyam.jalsoochak.telemetry.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Meter-image retrieval: from the WhatsApp provider by media id, or straight from a pre-signed URL,
 * plus the upload to object storage. Transient failures are retried with a bounded backoff; a 4xx is
 * not, since retrying a rejected request only delays the operator's reply.
 *
 * <p>The two sources go out on different clients on purpose — a media id goes to the provider
 * through {@link InboundMediaFetcher}, while a URL is caller-controlled and goes out on the guarded
 * client.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboundMediaService — download and upload")
class InboundMediaServiceDownloadTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};
    private static final String MEDIA_URL = "https://example.org/img.jpg";
    private static final String IMAGE_BUCKET = "meter-images-under-test";

    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private InboundMediaFetcher inboundMediaFetcher;
    @Mock
    private RestTemplate mediaFetchRestTemplate;
    @Mock
    private MediaUrlValidator mediaUrlValidator;

    private InboundMediaService service() {
        when(mediaUrlValidator.validate(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        return new InboundMediaService(objectStorageService, storageProperties(), inboundMediaFetcher,
                mediaFetchRestTemplate, mediaUrlValidator, 3, 1L, 1L, 5L, 20_971_520L);
    }

    private static StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket(IMAGE_BUCKET);
        return properties;
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

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        void rejectsASubmissionCarryingNeitherAMediaIdNorAUrl() {
            assertThatThrownBy(() -> service().downloadImage(null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid media");

            assertThatThrownBy(() -> service().downloadImage("  ", "  "))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void doesNotRetryAMediaIdTheProviderCannotAddress() {
            // The provider's refusal is a verdict about the id itself, so a second attempt would
            // only be refused again.
            when(inboundMediaFetcher.fetch("../../admin"))
                    .thenThrow(new IllegalStateException("Invalid media. Please send a clear meter image."));

            assertThatThrownBy(() -> service().downloadImage("../../admin", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid media");

            verify(inboundMediaFetcher, times(1)).fetch(anyString());
        }
    }

    @Nested
    @DisplayName("download by provider media id")
    class DownloadByMediaId {

        @Test
        void fetchesTheMediaIdThroughTheProvider() throws IOException {
            when(inboundMediaFetcher.fetch("media-1")).thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service().downloadImage("media-1", null)).isEqualTo(IMAGE);
        }

        @Test
        void prefersTheMediaIdWhenBothAreSupplied() throws IOException {
            when(inboundMediaFetcher.fetch("media-1")).thenReturn(ResponseEntity.ok(IMAGE));

            service().downloadImage("media-1", MEDIA_URL);

            verify(inboundMediaFetcher).fetch("media-1");
            verifyMediaFetches(0);
        }

        @Test
        void reportsANonOkStatusAsAFailure() {
            when(inboundMediaFetcher.fetch("media-1")).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

            assertThatThrownBy(() -> service().downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to download image from the WhatsApp provider");
        }

        @Test
        void reportsAnEmptyBodyAsAFailure() {
            when(inboundMediaFetcher.fetch("media-1")).thenReturn(ResponseEntity.ok(null));

            assertThatThrownBy(() -> service().downloadImage("media-1", null))
                    .isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("retry policy")
    class RetryPolicy {

        @Test
        void retriesAServerErrorUpToTheAttemptLimit() {
            when(inboundMediaFetcher.fetch(anyString()))
                    .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                            "boom", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service().downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("after 3 attempts");

            verify(inboundMediaFetcher, times(3)).fetch("media-1");
        }

        @Test
        void retriesARateLimitResponse() throws IOException {
            when(inboundMediaFetcher.fetch(anyString()))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                            "slow down", HttpHeaders.EMPTY, new byte[0], null))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            // 429 is the one 4xx worth retrying: the request was valid, just too soon.
            assertThat(service().downloadImage("media-1", null)).isEqualTo(IMAGE);
            verify(inboundMediaFetcher, times(2)).fetch("media-1");
        }

        @Test
        void retriesANetworkErrorAndSucceedsOnASubsequentAttempt() throws IOException {
            when(inboundMediaFetcher.fetch(anyString()))
                    .thenThrow(new ResourceAccessException("connection reset"))
                    .thenReturn(ResponseEntity.ok(IMAGE));

            assertThat(service().downloadImage("media-1", null)).isEqualTo(IMAGE);
            verify(inboundMediaFetcher, times(2)).fetch("media-1");
        }

        @Test
        void doesNotRetryAClientError() {
            when(inboundMediaFetcher.fetch(anyString()))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                            "missing", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service().downloadImage("media-1", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("non-retriable");

            verify(inboundMediaFetcher, times(1)).fetch("media-1");
        }

        @Test
        void makesASingleAttemptWhenRetriesAreDisabled() {
            var noRetries = new InboundMediaService(objectStorageService, storageProperties(), inboundMediaFetcher,
                    mediaFetchRestTemplate, mediaUrlValidator, 0, 0L, 0L, 0L, 20_971_520L);
            when(inboundMediaFetcher.fetch(anyString()))
                    .thenThrow(new ResourceAccessException("connection reset"));

            assertThatThrownBy(() -> noRetries.downloadImage("media-1", null))
                    .isInstanceOf(IOException.class);

            // max-attempts is clamped to at least 1, so the call still happens exactly once.
            verify(inboundMediaFetcher, times(1)).fetch("media-1");
        }
    }

    @Nested
    @DisplayName("download by pre-signed URL")
    class DownloadByUrl {

        @Test
        void fetchesTheValidatedUrlOnTheGuardedClient() throws IOException {
            mediaFetchYields(ResponseEntity.ok(IMAGE));

            assertThat(service().downloadImage(null, MEDIA_URL)).isEqualTo(IMAGE);

            verify(mediaUrlValidator).validate(MEDIA_URL);
            // A caller-supplied destination must never reach the provider's fetcher, which goes out
            // on the client shared with the OCR provider, the WhatsApp provider and object storage,
            // whose hosts are allowed to be internal.
            verifyNoInteractions(inboundMediaFetcher);
        }

        @Test
        void refusesAUrlTheValidatorRejectsWithoutIssuingARequest() {
            InboundMediaService service = service();
            when(mediaUrlValidator.validate(anyString()))
                    .thenThrow(new MediaUrlNotAllowedException("non-public address"));

            assertThatThrownBy(() -> service.downloadImage(null, "http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .hasMessageContaining("Invalid media");

            verifyMediaFetches(0);
        }

        @Test
        void doesNotRetryAUrlTheValidatorRejects() {
            InboundMediaService service = service();
            when(mediaUrlValidator.validate(anyString()))
                    .thenThrow(new MediaUrlNotAllowedException("host not allowlisted"));

            assertThatThrownBy(() -> service.downloadImage(null, "https://evil.example/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class);

            verify(mediaUrlValidator, times(1)).validate(anyString());
        }

        @Test
        void neverSendsAnAuthorizationHeaderToAThirdPartyUrl() throws IOException {
            mediaFetchYields(ResponseEntity.ok(IMAGE));

            service().downloadImage(null, MEDIA_URL);

            HttpHeaders sent = headersAppliedToMediaFetch();
            assertThat(sent.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
            assertThat(sent.getFirst(HttpHeaders.USER_AGENT)).isEqualTo("WaterSupplyBot/1.0");
        }

        @Test
        void reportsAFailureWithoutTheUrlSoCredentialsStayOutOfLogs() {
            mediaFetchYields(HttpClientErrorException.create(HttpStatus.FORBIDDEN,
                    "denied", HttpHeaders.EMPTY, new byte[0], null));

            assertThatThrownBy(() -> service()
                    .downloadImage(null, "https://example.org/img.jpg?signature=secret"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Failed to download image")
                    .hasMessageNotContaining("signature=secret");
        }

        @Test
        void retriesATransientFailureOnTheGuardedClient() throws IOException {
            InboundMediaService service = service();
            mediaFetchYields(new ResourceAccessException("timeout"), ResponseEntity.ok(IMAGE));

            assertThat(service.downloadImage(null, MEDIA_URL)).isEqualTo(IMAGE);
        }
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        @BeforeEach
        void publicUrlsResolve() {
            when(objectStorageService.publicUrl(anyString(), anyString()))
                    .thenReturn(URI.create("https://storage.example.org/meter-images/image.jpg"));
        }

        @Test
        void uploadsTheImageUnderAContactScopedKeyInTheConfiguredBucketAndReturnsItsPublicUrl() {
            AtomicReference<byte[]> uploaded = new AtomicReference<>();
            doAnswer(invocation -> {
                uploaded.set(invocation.<InputStream>getArgument(2).readAllBytes());
                return null;
            }).when(objectStorageService).upload(anyString(), anyString(), any(InputStream.class), anyLong(),
                    anyString());
            when(objectStorageService.publicUrl(eq(IMAGE_BUCKET), anyString()))
                    .thenReturn(URI.create("https://storage.example.org/meter-images/bfm/919999900001/1.jpg"));

            String url = service().uploadImage("919999900001", IMAGE);

            assertThat(url).isEqualTo("https://storage.example.org/meter-images/bfm/919999900001/1.jpg");
            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(objectStorageService).upload(eq(IMAGE_BUCKET), objectKey.capture(), any(InputStream.class),
                    eq((long) IMAGE.length), anyString());
            assertThat(objectKey.getValue()).startsWith("bfm/919999900001/").endsWith(".jpg");
            assertThat(uploaded.get()).isEqualTo(IMAGE);
            verify(objectStorageService).publicUrl(IMAGE_BUCKET, objectKey.getValue());
        }

        @Test
        void labelsTheImageWithTheTypeItsFileSignatureShows() {
            service().uploadImage("919999900001", PNG);

            verify(objectStorageService).upload(anyString(), anyString(), any(InputStream.class), anyLong(),
                    eq("image/png"));
        }

        @Test
        void labelsUnrecognisableContentAsOctetStream() {
            service().uploadImage("919999900001", IMAGE);

            verify(objectStorageService).upload(anyString(), anyString(), any(InputStream.class), anyLong(),
                    eq("application/octet-stream"));
        }

        @Test
        void propagatesAStorageFailureWithoutBuildingAUrl() {
            StorageException failure = new StorageException("Upload failed to bucket: " + IMAGE_BUCKET);
            doThrow(failure).when(objectStorageService).upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString());

            assertThatThrownBy(() -> service().uploadImage("919999900001", IMAGE)).isSameAs(failure);

            verify(objectStorageService, never()).publicUrl(anyString(), anyString());
        }
    }
}
