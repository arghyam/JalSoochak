package org.arghyam.jalsoochak.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.minio.MinioClient;
import io.minio.UploadObjectArgs;

/**
 * Unit tests for {@link MinioStorageService}.
 *
 * <p>The production constructor builds its own MinioClient, so tests use the
 * package-private test constructor to inject a mock MinioClient. The constructor
 * validation tests exercise the production constructor directly.</p>
 */
@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    private static final String BUCKET = "escalation-reports";
    private static final String BASE_URL = "http://minio.example.com";

    @Mock
    private MinioClient minioClient;

    private MinioStorageService minioStorageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        minioStorageService = new MinioStorageService(minioClient, BUCKET, BASE_URL);
    }

    // ──────────────────────────── upload success ───────────────────────────────

    @Test
    void upload_returnsPublicUrl_builtFromBaseUrlBucketAndFilename() throws Exception {
        Path pdfFile = tempDir.resolve("report.pdf");
        Files.write(pdfFile, "dummy pdf content".getBytes());

        String url = minioStorageService.upload(pdfFile);

        assertThat(url).isEqualTo(BASE_URL + "/" + BUCKET + "/report.pdf");
    }

    @Test
    void upload_callsMinioClientUploadObject() throws Exception {
        Path pdfFile = tempDir.resolve("report.pdf");
        Files.write(pdfFile, "pdf bytes".getBytes());

        minioStorageService.upload(pdfFile);

        verify(minioClient).uploadObject(any(UploadObjectArgs.class));
    }

    @Test
    void upload_usesTheFileNameAsObjectName_notTheFulPath() throws Exception {
        Path pdfFile = tempDir.resolve("escalation_L2_2026_04_16.pdf");
        Files.write(pdfFile, "content".getBytes());

        String url = minioStorageService.upload(pdfFile);

        assertThat(url).endsWith("/escalation_L2_2026_04_16.pdf");
        // must not include parent directories in the URL
        assertThat(url).doesNotContain(tempDir.toString());
    }

    @Test
    void upload_urlContainsBucketName() throws Exception {
        Path pdfFile = tempDir.resolve("any.pdf");
        Files.write(pdfFile, "x".getBytes());

        String url = minioStorageService.upload(pdfFile);

        assertThat(url).contains(BUCKET);
    }

    // ──────────────────────────── upload failure ───────────────────────────────

    @Test
    void upload_throwsException_whenMinioClientFails() throws Exception {
        Path pdfFile = tempDir.resolve("bad.pdf");
        Files.write(pdfFile, "content".getBytes());
        doThrow(new RuntimeException("MinIO unavailable"))
                .when(minioClient).uploadObject(any(UploadObjectArgs.class));

        assertThatThrownBy(() -> minioStorageService.upload(pdfFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MinIO unavailable");
    }

    @Test
    void upload_propagatesRuntimeExceptionFromMinioClient_withDifferentMessage() throws Exception {
        Path pdfFile = tempDir.resolve("another.pdf");
        Files.write(pdfFile, "content".getBytes());
        doThrow(new RuntimeException("Connection refused to MinIO"))
                .when(minioClient).uploadObject(any(UploadObjectArgs.class));

        assertThatThrownBy(() -> minioStorageService.upload(pdfFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Connection refused to MinIO");
    }

    // ───────────────────── constructor credential validation ───────────────────

    @Test
    void constructor_throwsIllegalState_whenAccessKeyIsBlank() {
        assertThatThrownBy(() ->
                new MinioStorageService("http://minio.example.com", "", "my-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minio.access-key");
    }

    @Test
    void constructor_throwsIllegalState_whenAccessKeyIsNull() {
        assertThatThrownBy(() ->
                new MinioStorageService("http://minio.example.com", null, "my-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minio.access-key");
    }

    @Test
    void constructor_throwsIllegalState_whenSecretKeyIsBlank() {
        assertThatThrownBy(() ->
                new MinioStorageService("http://minio.example.com", "my-access-key", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minio.secret-key");
    }

    @Test
    void constructor_throwsIllegalState_whenSecretKeyIsNull() {
        assertThatThrownBy(() ->
                new MinioStorageService("http://minio.example.com", "my-access-key", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minio.secret-key");
    }
}
