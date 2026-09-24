package org.arghyam.jalsoochak.scheme.service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the report-storage contract the scheme reports depend on: which bucket an upload lands in,
 * the content type it carries, and the shape of the presigned download link — its TTL, its
 * download filename, and the public origin it is rewritten onto.
 */
@ExtendWith(MockitoExtension.class)
class MinioServiceTest {

    private static final String BUCKET = "jalsoochak-reports";
    private static final String KEY = "ka/reports/scheme/2026/09/report.csv";
    private static final String SIGNED_QUERY =
            "?X-Amz-Credential=access%2F20260924%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Signature=abc123";
    private static final String SDK_URL = "http://storage:9000/" + BUCKET + "/" + KEY + SIGNED_QUERY;

    @Mock
    MinioClient minioClient;

    /**
     * The constructor builds a real client from the endpoint (no network I/O happens until a call),
     * which is then swapped for the mock.
     */
    private MinioService serviceWith(String presignedBaseUrl, long presignedTtlSeconds) {
        MinioService service = new MinioService("http://storage:9000/", "access", "secret", BUCKET,
                presignedBaseUrl, presignedTtlSeconds);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        return service;
    }

    private PutObjectArgs capturedUpload() throws Exception {
        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        return args.getValue();
    }

    private GetPresignedObjectUrlArgs capturedPresign() throws Exception {
        ArgumentCaptor<GetPresignedObjectUrlArgs> args = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(args.capture());
        return args.getValue();
    }

    @Test
    void upload_putsTheStreamIntoTheReportsBucketUnderTheKeyAndReturnsTheKey() throws Exception {
        MinioService service = serviceWith("", 3600);
        byte[] csv = "id,uuid\n1,u-1\n".getBytes(StandardCharsets.UTF_8);

        String stored = service.upload(new ByteArrayInputStream(csv), csv.length, KEY, "text/csv");

        assertThat(stored).isEqualTo(KEY);
        PutObjectArgs args = capturedUpload();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo(KEY);
        assertThat(args.contentType()).isEqualTo("text/csv");
        assertThat(args.objectSize()).isEqualTo(csv.length);
    }

    @Test
    void upload_fallsBackToOctetStreamForABlankContentType() throws Exception {
        MinioService service = serviceWith("", 3600);

        service.upload(new ByteArrayInputStream(new byte[]{1}), 1, KEY, " ");

        assertThat(capturedUpload().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void upload_bytesAreStoredAsCsv() throws Exception {
        MinioService service = serviceWith("", 3600);
        byte[] csv = "id\n1\n".getBytes(StandardCharsets.UTF_8);

        String stored = service.upload(csv, KEY);

        assertThat(stored).isEqualTo(KEY);
        PutObjectArgs args = capturedUpload();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.contentType()).isEqualTo("text/csv");
        assertThat(args.objectSize()).isEqualTo(csv.length);
    }

    @Test
    void upload_wrapsAClientFailure() throws Exception {
        MinioService service = serviceWith("", 3600);
        IllegalStateException cause = new IllegalStateException("connection refused");
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(cause);

        assertThatThrownBy(() -> service.upload(new ByteArrayInputStream(new byte[]{1}), 1, KEY, "text/csv"))
                .isInstanceOf(RuntimeException.class)
                .hasCause(cause);
    }

    @Test
    void getBucket_returnsTheReportsBucket() {
        assertThat(serviceWith("", 3600).getBucket()).isEqualTo(BUCKET);
    }

    @Test
    void getObjectUrl_presignsAGetOnTheReportsBucketForTheConfiguredTtl() throws Exception {
        MinioService service = serviceWith("", 900);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        service.getObjectUrl(KEY);

        GetPresignedObjectUrlArgs args = capturedPresign();
        assertThat(args.method()).isEqualTo(Method.GET);
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo(KEY);
        assertThat(args.expiry()).isEqualTo(900);
        assertThat(args.extraQueryParams().containsKey("response-content-disposition")).isFalse();
    }

    @Test
    void getObjectUrl_clampsANonPositiveTtlToOneSecond() throws Exception {
        MinioService service = serviceWith("", 0);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        service.getObjectUrl(KEY);

        assertThat(capturedPresign().expiry()).isEqualTo(1);
    }

    @Test
    void getObjectUrl_asksForTheDownloadFilenameAsAnAttachment() throws Exception {
        MinioService service = serviceWith("", 3600);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        service.getObjectUrl(KEY, "scheme report KA.csv");

        assertThat(capturedPresign().extraQueryParams().get("response-content-disposition"))
                .containsExactly("attachment; filename=\"scheme report KA.csv\"; "
                        + "filename*=UTF-8''scheme%20report%20KA.csv");
    }

    @Test
    void getObjectUrl_replacesQuotesAndLineBreaksInTheDownloadFilename() throws Exception {
        MinioService service = serviceWith("", 3600);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        service.getObjectUrl(KEY, "a\"b\r\nc.csv");

        assertThat(capturedPresign().extraQueryParams().get("response-content-disposition"))
                .containsExactly("attachment; filename=\"a_b__c.csv\"; filename*=UTF-8''a_b__c.csv");
    }

    @Test
    void getObjectUrl_omitsTheDispositionForABlankFilename() throws Exception {
        MinioService service = serviceWith("", 3600);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        service.getObjectUrl(KEY, " ");

        assertThat(capturedPresign().extraQueryParams().containsKey("response-content-disposition")).isFalse();
    }

    @Test
    void getObjectUrl_returnsTheSdkUrlWhenNoPublicBaseUrlIsConfigured() throws Exception {
        MinioService service = serviceWith("", 3600);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        assertThat(service.getObjectUrl(KEY)).isEqualTo(SDK_URL);
    }

    @Test
    void getObjectUrl_rewritesTheOriginAndPathPrefixAndKeepsTheSignedQueryVerbatim() throws Exception {
        MinioService service = serviceWith("https://reports.example.org/files/", 3600);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        assertThat(service.getObjectUrl(KEY)).isEqualTo(
                "https://reports.example.org/files/jalsoochak-reports/" + KEY + SIGNED_QUERY);
    }

    @Test
    void getObjectUrl_keepsAnExplicitPortOnThePublicBaseUrl() throws Exception {
        MinioService service = serviceWith("http://localhost:9443", 3600);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(SDK_URL);

        assertThat(service.getObjectUrl(KEY)).isEqualTo(
                "http://localhost:9443/jalsoochak-reports/" + KEY + SIGNED_QUERY);
    }

    @Test
    void getObjectUrl_wrapsAPresignFailure() throws Exception {
        MinioService service = serviceWith("", 3600);
        IllegalStateException cause = new IllegalStateException("region lookup failed");
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenThrow(cause);

        assertThatThrownBy(() -> service.getObjectUrl(KEY))
                .isInstanceOf(RuntimeException.class)
                .hasCause(cause);
    }
}
