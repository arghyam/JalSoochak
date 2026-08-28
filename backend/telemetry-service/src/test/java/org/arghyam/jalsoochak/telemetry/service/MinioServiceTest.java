package org.arghyam.jalsoochak.telemetry.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Object upload to MinIO and the public URL handed back to Glific. The URL is what Meta/Gupshup
 * later fetches, so its shape (scheme, authority, {@code /bucket/object} path) is load-bearing.
 */
@DisplayName("MinioService")
class MinioServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * Builds the service against a mock {@link MinioClient}. The constructor builds a real client
     * from the endpoint (no network I/O happens until a call), which is then swapped for the mock.
     */
    private static MinioService serviceWith(MinioClient client, String endpoint, String bucket) {
        MinioService service = new MinioService(endpoint, "access", "secret", bucket);
        ReflectionTestUtils.setField(service, "minioClient", client);
        return service;
    }

    @Test
    void uploadsTheObjectIntoTheConfiguredBucket() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioService service = serviceWith(client, "https://minio.example.org", "telemetry");

        service.upload(PNG, "readings/img.png");

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo("telemetry");
        assertThat(args.getValue().object()).isEqualTo("readings/img.png");
    }

    @Test
    void returnsThePublicUrlForTheUploadedObject() {
        MinioService service = serviceWith(mock(MinioClient.class), "https://minio.example.org", "telemetry");

        assertThat(service.upload(PNG, "readings/img.png"))
                .isEqualTo("https://minio.example.org/telemetry/readings/img.png");
    }

    @Test
    void stripsATrailingSlashFromTheConfiguredEndpoint() {
        MinioService service = serviceWith(mock(MinioClient.class), "https://minio.example.org/", "telemetry");

        assertThat(service.upload(PNG, "img.png"))
                .isEqualTo("https://minio.example.org/telemetry/img.png");
    }

    @Test
    void preservesANonDefaultPortInTheObjectUrl() {
        MinioService service = serviceWith(mock(MinioClient.class), "http://localhost:9000", "telemetry");

        assertThat(service.upload(PNG, "img.png"))
                .isEqualTo("http://localhost:9000/telemetry/img.png");
    }

    @Test
    void encodesSpacesInTheObjectName() {
        MinioService service = serviceWith(mock(MinioClient.class), "https://minio.example.org", "telemetry");

        assertThat(service.upload(PNG, "my reading.png"))
                .isEqualTo("https://minio.example.org/telemetry/my%20reading.png");
    }

    @Test
    void detectsTheContentTypeFromTheFileSignature() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioService service = serviceWith(client, "https://minio.example.org", "telemetry");

        service.upload(PNG, "img.png");

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(args.capture());
        assertThat(args.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    void fallsBackToOctetStreamForUnrecognisableContent() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioService service = serviceWith(client, "https://minio.example.org", "telemetry");

        service.upload("just some text".getBytes(StandardCharsets.UTF_8), "blob.bin");

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(args.capture());
        assertThat(args.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void wrapsAnUploadFailureWithContext() throws Exception {
        MinioClient client = mock(MinioClient.class);
        doThrow(new IllegalStateException("connection refused")).when(client).putObject(any());
        MinioService service = serviceWith(client, "https://minio.example.org", "telemetry");

        assertThatThrownBy(() -> service.upload(PNG, "img.png"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to upload image to MinIO")
                .hasRootCauseMessage("connection refused");
    }

    @Test
    void reportsAMalformedEndpointRatherThanReturningABrokenUrl() {
        MinioService service = serviceWith(mock(MinioClient.class), "https://minio.example.org", "telemetry");
        ReflectionTestUtils.setField(service, "endpoint", "http://[bad-uri");

        assertThatThrownBy(() -> service.upload(PNG, "img.png"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to upload image to MinIO");
    }
}
