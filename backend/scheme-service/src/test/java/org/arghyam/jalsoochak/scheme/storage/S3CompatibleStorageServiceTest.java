package org.arghyam.jalsoochak.scheme.storage;

import org.arghyam.jalsoochak.scheme.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3CompatibleStorageService")
class S3CompatibleStorageServiceTest {

    @Mock S3Client s3Client;
    @Mock S3Presigner s3Presigner;

    private S3CompatibleStorageService service;

    @BeforeEach
    void setUp() {
        service = new S3CompatibleStorageService(s3Client, s3Presigner, null);
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("forwards bucket, key, content-type and length to S3")
        void uploadsWithMetadata() {
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            service.upload("b", "k", new ByteArrayInputStream("x".getBytes()), 1L, "text/csv");

            ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(req.capture(), any(RequestBody.class));
            assertThat(req.getValue().bucket()).isEqualTo("b");
            assertThat(req.getValue().key()).isEqualTo("k");
            assertThat(req.getValue().contentType()).isEqualTo("text/csv");
            assertThat(req.getValue().contentLength()).isEqualTo(1L);
        }

        @Test
        @DisplayName("wraps SdkException into StorageException")
        void wrapsSdkFailure() {
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(AwsServiceException.builder().message("boom").build());

            assertThatThrownBy(() -> service.upload("b", "k",
                    new ByteArrayInputStream(new byte[]{1}), 1L, "text/csv"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Upload failed for key: k");
        }
    }

    @Nested
    @DisplayName("presignedGetUrl")
    class Presign {

        private void stubPresigner(String url) throws Exception {
            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(URI.create(url).toURL());
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
        }

        private GetObjectPresignRequest capturedRequest() {
            ArgumentCaptor<GetObjectPresignRequest> req = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(s3Presigner).presignGetObject(req.capture());
            return req.getValue();
        }

        @Test
        @DisplayName("signs bucket and key for the given TTL, with no Content-Disposition override for a null filename")
        void signsForTheTtl() throws Exception {
            stubPresigner("https://x/y?sig=1");

            URI uri = service.presignedGetUrl("b", "k", Duration.ofMinutes(5), null);

            assertThat(uri.toString()).isEqualTo("https://x/y?sig=1");
            GetObjectPresignRequest req = capturedRequest();
            assertThat(req.signatureDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(req.getObjectRequest().bucket()).isEqualTo("b");
            assertThat(req.getObjectRequest().key()).isEqualTo("k");
            assertThat(req.getObjectRequest().responseContentDisposition()).isNull();
        }

        @Test
        @DisplayName("filename is set as attachment Content-Disposition with RFC 5987 fallback")
        void filenameAddsDisposition() throws Exception {
            stubPresigner("https://x/y?sig=2");

            service.presignedGetUrl("b", "k", Duration.ofMinutes(15), "scheme_report_MP_20260519_1422.csv");

            assertThat(capturedRequest().getObjectRequest().responseContentDisposition())
                    .startsWith("attachment; filename=\"scheme_report_MP_20260519_1422.csv\"")
                    .contains("filename*=UTF-8''scheme_report_MP_20260519_1422.csv");
        }

        @Test
        @DisplayName("blank filename is treated as omitted")
        void blankFilenameOmitsDisposition() throws Exception {
            stubPresigner("https://x/y?sig=3");

            service.presignedGetUrl("b", "k", Duration.ofMinutes(5), "   ");

            assertThat(capturedRequest().getObjectRequest().responseContentDisposition()).isNull();
        }

        @Test
        @DisplayName("filename containing quote / CR / LF is sanitized for the ASCII filename slot")
        void sanitizesUnsafeChars() throws Exception {
            stubPresigner("https://x/y?sig=4");

            service.presignedGetUrl("b", "k", Duration.ofMinutes(5), "bad\"name\r\n.csv");

            String cd = capturedRequest().getObjectRequest().responseContentDisposition();
            assertThat(cd).doesNotContain("\"name").doesNotContain("\r").doesNotContain("\n");
            assertThat(cd).contains("filename=\"bad_name__.csv\"");
        }

        @Test
        @DisplayName("presigner failure is wrapped in StorageException")
        void presignerFailureWrapped() {
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                    .thenThrow(new RuntimeException("boom"));

            assertThatThrownBy(() -> service.presignedGetUrl("b", "k", Duration.ofMinutes(5), null))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to presign URL for key: k");
            verifyNoInteractions(s3Client);
        }
    }

    @Nested
    @DisplayName("rewritePublicUrl")
    class RewritePublicUrl {

        private S3CompatibleStorageService withPresignedBaseUrl(String presignedBaseUrl) {
            return new S3CompatibleStorageService(s3Client, s3Presigner, presignedBaseUrl);
        }

        @Test
        @DisplayName("no-op when presignedBaseUrl is null")
        void noopWhenNull() {
            URI sdk = URI.create("http://storage:9000/bucket/key?X-Amz-Signature=abc&X-Amz-Expires=3600");
            assertThat(withPresignedBaseUrl(null).rewritePublicUrl(sdk)).isEqualTo(sdk);
        }

        @Test
        @DisplayName("no-op when presignedBaseUrl is blank")
        void noopWhenBlank() {
            URI sdk = URI.create("http://storage:9000/bucket/key?X-Amz-Signature=abc");
            assertThat(withPresignedBaseUrl("   ").rewritePublicUrl(sdk)).isEqualTo(sdk);
        }

        @Test
        @DisplayName("rewrites origin and prepends path prefix, preserving query string")
        void rewritesOriginAndPathPrefix() {
            URI sdk = URI.create("http://storage:9000/jalsoochak-reports/scheme/file.csv?X-Amz-Signature=abc&X-Amz-Expires=3600");

            URI result = withPresignedBaseUrl("https://jalsoochak.in/storage").rewritePublicUrl(sdk);

            assertThat(result.getScheme()).isEqualTo("https");
            assertThat(result.getHost()).isEqualTo("jalsoochak.in");
            assertThat(result.getPort()).isEqualTo(-1);
            assertThat(result.getPath()).isEqualTo("/storage/jalsoochak-reports/scheme/file.csv");
            assertThat(result.getQuery()).isEqualTo("X-Amz-Signature=abc&X-Amz-Expires=3600");
        }

        @Test
        @DisplayName("trailing slash in presignedBaseUrl does not produce double slash in path")
        void noDoubleSlash() {
            URI sdk = URI.create("http://storage:9000/bucket/key?sig=x");

            URI result = withPresignedBaseUrl("https://jalsoochak.in/storage/").rewritePublicUrl(sdk);

            assertThat(result.getPath()).isEqualTo("/storage/bucket/key");
        }

        @Test
        @DisplayName("works when presignedBaseUrl has no path prefix (origin-only rewrite)")
        void originOnlyRewrite() {
            URI sdk = URI.create("http://storage:9000/bucket/key?sig=x");

            URI result = withPresignedBaseUrl("https://storage.jalsoochak.in").rewritePublicUrl(sdk);

            assertThat(result.toString()).isEqualTo("https://storage.jalsoochak.in/bucket/key?sig=x");
        }

        @Test
        @DisplayName("keeps an explicit port on presignedBaseUrl")
        void keepsExplicitPort() {
            URI sdk = URI.create("http://storage:9000/bucket/key?sig=x");

            URI result = withPresignedBaseUrl("http://localhost:9443").rewritePublicUrl(sdk);

            assertThat(result.toString()).isEqualTo("http://localhost:9443/bucket/key?sig=x");
        }

        @Test
        @DisplayName("keeps percent-encoded signed query parameters verbatim")
        void keepsEncodedQueryVerbatim() {
            String signedQuery =
                    "X-Amz-Credential=access%2F20260924%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Signature=abc123";
            URI sdk = URI.create("http://storage:9000/bucket/key?" + signedQuery);

            URI result = withPresignedBaseUrl("https://jalsoochak.in/storage").rewritePublicUrl(sdk);

            assertThat(result.toString()).isEqualTo("https://jalsoochak.in/storage/bucket/key?" + signedQuery);
        }
    }
}
