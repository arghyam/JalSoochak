package org.arghyam.jalsoochak.message.storage;

import org.arghyam.jalsoochak.message.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3CompatibleStorageService")
class S3CompatibleStorageServiceTest {

    @Mock S3Client s3Client;

    private S3CompatibleStorageService service;

    @BeforeEach
    void setUp() {
        service = new S3CompatibleStorageService(s3Client, "https://jalsoochak.in/storage");
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("forwards bucket, key, content-type and length to S3")
        void uploadsWithMetadata() {
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            service.upload("b", "k", new ByteArrayInputStream("x".getBytes()), 1L, "application/pdf");

            ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(req.capture(), any(RequestBody.class));
            assertThat(req.getValue().bucket()).isEqualTo("b");
            assertThat(req.getValue().key()).isEqualTo("k");
            assertThat(req.getValue().contentType()).isEqualTo("application/pdf");
            assertThat(req.getValue().contentLength()).isEqualTo(1L);
        }

        @Test
        @DisplayName("wraps SdkException into StorageException")
        void wrapsSdkFailure() {
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(AwsServiceException.builder().message("boom").build());

            assertThatThrownBy(() -> service.upload("b", "k",
                    new ByteArrayInputStream(new byte[]{1}), 1L, "application/pdf"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Upload failed for key: k");
        }
    }

    @Nested
    @DisplayName("publicUrl")
    class PublicUrl {

        private S3CompatibleStorageService withPublicBaseUrl(String publicBaseUrl) {
            return new S3CompatibleStorageService(s3Client, publicBaseUrl);
        }

        @Test
        @DisplayName("joins base URL, bucket and key into an unsigned URL with no query string, without calling the store")
        void joinsBaseBucketAndKey() {
            URI uri = service.publicUrl("escalation-reports", "SO/2026-07-19/daily_water_report_16743.pdf");

            assertThat(uri.toString()).isEqualTo("https://jalsoochak.in/storage/escalation-reports/SO/2026-07-19/daily_water_report_16743.pdf");
            assertThat(uri.getRawQuery()).isNull();
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("trims surrounding whitespace and every trailing slash from the base URL")
        void trimsBaseUrl() {
            URI uri = withPublicBaseUrl("  https://jalsoochak.in/storage//  ").publicUrl("b", "k.pdf");

            assertThat(uri.toString()).isEqualTo("https://jalsoochak.in/storage/b/k.pdf");
        }

        @Test
        @DisplayName("percent-encodes each key segment and keeps the separators")
        void encodesEachSegment() {
            URI uri = service.publicUrl("b", "SO/2026-07-19/daily report+final.pdf");

            assertThat(uri.toString())
                    .isEqualTo("https://jalsoochak.in/storage/b/SO/2026-07-19/daily%20report%2Bfinal.pdf");
        }

        @ParameterizedTest(name = "publicBaseUrl=\"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        @DisplayName("blank base URL is a StorageException naming the property")
        void blankBaseUrlRejected(String publicBaseUrl) {
            S3CompatibleStorageService svc = withPublicBaseUrl(publicBaseUrl);

            assertThatThrownBy(() -> svc.publicUrl("b", "k"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("storage.public-base-url");
        }

        @Test
        @DisplayName("malformed base URL is wrapped in StorageException")
        void malformedBaseUrlWrapped() {
            S3CompatibleStorageService svc = withPublicBaseUrl("https://jalsoochak.in/a path");

            assertThatThrownBy(() -> svc.publicUrl("b", "k"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to build public URL for key: k")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
