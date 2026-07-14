package org.arghyam.jalsoochak.message.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual local integration test: renders a Daily Water Service Situation Report PDF and uploads it to
 * a real (local) MinIO — WITHOUT Kafka, Postgres, or WhatsApp/Glific.
 *
 * <p>Disabled by default (never runs in the normal suite / CI). Enable it by setting
 * {@code MINIO_LOCAL_TEST=true} plus the MinIO connection env vars, then run:</p>
 *
 * <pre>{@code
 *   MINIO_LOCAL_TEST=true \
 *   MINIO_ENDPOINT=http://localhost:9000 \
 *   MINIO_ACCESS_KEY=minioadmin \
 *   MINIO_SECRET_KEY=minioadmin \
 *   MINIO_BUCKET=escalation-reports \
 *   MINIO_BASE_URL=http://localhost:9000 \
 *   mvn -pl message-service test -Dtest=DailyReportMinioUploadIT -Djacoco.skip=true
 * }</pre>
 *
 * <p>On success it prints the public object URL; open it in the MinIO console (http://localhost:9001)
 * or via {@code mc} to view the generated PDF.</p>
 */
@EnabledIfEnvironmentVariable(named = "MINIO_LOCAL_TEST", matches = "true")
class DailyReportMinioUploadIT {

    @TempDir
    Path tempDir;

    @Test
    void generatesPdfAndUploadsToMinio() throws Exception {
        String endpoint = env("MINIO_ENDPOINT", "http://localhost:9000");
        String accessKey = env("MINIO_ACCESS_KEY", "minioadmin");
        String secretKey = env("MINIO_SECRET_KEY", "minioadmin");
        String bucket = env("MINIO_BUCKET", "escalation-reports");
        String baseUrl = env("MINIO_BASE_URL", endpoint);

        // 1) Ensure the target bucket exists (uploadObject requires a pre-existing bucket).
        MinioClient adminClient = MinioClient.builder()
                .endpoint(endpoint).credentials(accessKey, secretKey).build();
        boolean exists = adminClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            adminClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        // 2) Render the report PDF (no Kafka / no DB).
        DailyReportPdfService pdfService = new DailyReportPdfService();
        ReflectionTestUtils.setField(pdfService, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(pdfService, "dashboardUrl", "https://jalsoochak.jjmbrain.in/");
        ReflectionTestUtils.setField(pdfService, "supportPhone", "919999999999");

        String filename = pdfService.generate(sampleKpis(), "Binod Nimoli", "SECTION_OFFICER");
        Path localPdf = tempDir.resolve(filename);
        assertThat(localPdf.toFile()).exists();

        // 3) Upload to MinIO (no WhatsApp / no Glific).
        MinioStorageService minio = new MinioStorageService(endpoint, accessKey, secretKey);
        ReflectionTestUtils.setField(minio, "bucket", bucket);
        ReflectionTestUtils.setField(minio, "minioBaseUrl", baseUrl);

        String url = minio.upload(localPdf);

        assertThat(url).endsWith("/" + bucket + "/" + filename);
        System.out.println("\n[DailyReportMinioUploadIT] PDF uploaded to MinIO:\n  " + url + "\n");
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private DailyReportKpis sampleKpis() {
        return DailyReportKpis.builder()
                .reportDate("2026-07-13")
                .previousDate("2026-07-12")
                .totalSchemes(148)
                .yesterday(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(142).schemesNotSupplying(6)
                        .avgLpcd(63).avgMld(18.4)
                        .regularSupplyPctWeek(92).readingSubmissionPct(97)
                        .anomalousCount(11).build())
                .previousDay(DailyReportKpis.DayKpis.builder()
                        .schemesSupplying(140).schemesNotSupplying(8)
                        .avgLpcd(61).avgMld(18.1)
                        .regularSupplyPctWeek(97).readingSubmissionPct(96)
                        .anomalousCount(15).build())
                .reasonsForNoSupply(List.of(
                        DailyReportKpis.ReasonCount.builder().reason("PUMP_FAILURE").count(4).build(),
                        DailyReportKpis.ReasonCount.builder().reason("ELECTRICITY_SUPPLY_DISCONNECTED").count(2).build(),
                        DailyReportKpis.ReasonCount.builder().reason("Pipeline Break").count(3).build()))
                .anomaliesByType(List.of(
                        DailyReportKpis.TypeCount.builder().type("5").count(3).build(),
                        DailyReportKpis.TypeCount.builder().type("4").count(1).build(),
                        DailyReportKpis.TypeCount.builder().type("1").count(2).build()))
                .build();
    }
}
