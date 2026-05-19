package org.arghyam.jalsoochak.user.storage;

import org.arghyam.jalsoochak.user.exceptions.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
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
        service = new S3CompatibleStorageService(s3Client, s3Presigner);
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("forwards bucket, key, content-type and length to S3 and returns the key")
        void uploadsWithMetadata() {
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            String key = service.upload("b", "k", new ByteArrayInputStream("x".getBytes()),
                    1L, "text/csv");

            assertThat(key).isEqualTo("k");
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
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("forwards delete to S3")
        void delegatesDelete() {
            service.delete("b", "k");
            ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(req.capture());
            assertThat(req.getValue().bucket()).isEqualTo("b");
            assertThat(req.getValue().key()).isEqualTo("k");
        }

        @Test
        @DisplayName("absent object is treated as success (idempotent)")
        void absentObjectIsNoop() {
            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("nope").build());

            // does not throw
            service.delete("b", "k");
        }
    }

    @Nested
    @DisplayName("download")
    class Download {

        @Test
        @DisplayName("returns the S3 stream verbatim")
        void returnsStream() {
            @SuppressWarnings("unchecked")
            ResponseInputStream<GetObjectResponse> stream = mock(ResponseInputStream.class);
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);

            assertThat(service.download("b", "k")).isSameAs(stream);
        }

        @Test
        @DisplayName("NoSuchKey translates to StorageException 'Object not found'")
        void missingObjectTranslated() {
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("absent").build());

            assertThatThrownBy(() -> service.download("b", "k"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Object not found: k");
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

        @Test
        @DisplayName("default 3-arg overload omits Content-Disposition override")
        void noFilenameOmitsDisposition() throws Exception {
            stubPresigner("https://x/y?sig=1");

            URI uri = service.presignedGetUrl("b", "k", Duration.ofMinutes(5));

            assertThat(uri.toString()).isEqualTo("https://x/y?sig=1");
            ArgumentCaptor<GetObjectPresignRequest> req =
                    ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(s3Presigner).presignGetObject(req.capture());
            assertThat(req.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(req.getValue().getObjectRequest().responseContentDisposition()).isNull();
        }

        @Test
        @DisplayName("filename is set as attachment Content-Disposition with RFC 5987 fallback")
        void filenameAddsDisposition() throws Exception {
            stubPresigner("https://x/y?sig=2");

            service.presignedGetUrl("b", "k", Duration.ofMinutes(15),
                    "staff_report_MP_20260519_1422.csv");

            ArgumentCaptor<GetObjectPresignRequest> req =
                    ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(s3Presigner).presignGetObject(req.capture());
            String cd = req.getValue().getObjectRequest().responseContentDisposition();
            assertThat(cd)
                    .startsWith("attachment; filename=\"staff_report_MP_20260519_1422.csv\"")
                    .contains("filename*=UTF-8''staff_report_MP_20260519_1422.csv");
        }

        @Test
        @DisplayName("blank filename is treated as omitted")
        void blankFilenameOmitsDisposition() throws Exception {
            stubPresigner("https://x/y?sig=3");

            service.presignedGetUrl("b", "k", Duration.ofMinutes(5), "   ");

            ArgumentCaptor<GetObjectPresignRequest> req =
                    ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(s3Presigner).presignGetObject(req.capture());
            assertThat(req.getValue().getObjectRequest().responseContentDisposition()).isNull();
        }

        @Test
        @DisplayName("filename containing quote / CR / LF is sanitized for the ASCII filename slot")
        void sanitizesUnsafeChars() throws Exception {
            stubPresigner("https://x/y?sig=4");

            service.presignedGetUrl("b", "k", Duration.ofMinutes(5),
                    "bad\"name\r\n.csv");

            ArgumentCaptor<GetObjectPresignRequest> req =
                    ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(s3Presigner).presignGetObject(req.capture());
            String cd = req.getValue().getObjectRequest().responseContentDisposition();
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
}
