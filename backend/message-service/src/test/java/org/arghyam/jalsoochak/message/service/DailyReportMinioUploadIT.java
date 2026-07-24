package org.arghyam.jalsoochak.message.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: renders a Daily Water Service Situation Report PDF and uploads it to a
 * throwaway MinIO started via Testcontainers — no Kafka, Postgres, or WhatsApp/Glific, and no
 * dependency on a locally running MinIO. Requires Docker (like the other Testcontainers ITs).
 *
 * <p>Verifies bucket creation, PDF generation, upload, and the returned public object URL.</p>
 */
@Testcontainers
class DailyReportMinioUploadIT {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET = "escalation-reports";

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server /data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @TempDir
    Path tempDir;

    @Test
    void generatesPdfAndUploadsToMinio() throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);

        // 1) Ensure the target bucket exists (uploadObject requires a pre-existing bucket).
        MinioClient adminClient = MinioClient.builder()
                .endpoint(endpoint).credentials(ACCESS_KEY, SECRET_KEY).build();
        boolean exists = adminClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            adminClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }

        // 2) Render the report PDF (no Kafka / no DB).
        DailyReportPdfService pdfService = new DailyReportPdfService();
        ReflectionTestUtils.setField(pdfService, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(pdfService, "dashboardUrl", "https://jalsoochak.jjmbrain.in/staff/");
        ReflectionTestUtils.setField(pdfService, "supportPhone", "919999999999");

        List<DailyReportPriorityRow> priority = List.of(
                DailyReportPriorityRow.builder().scheme("Rampur WSS").imisId("RPWSS-108")
                        .jalMitraNames("Ramesh Kumar").jalMitraMobiles("919000000001")
                        .issue("Pump Failure").remarks("No water supply for past 7 days").build(),
                DailyReportPriorityRow.builder().scheme("Sitapur WSS").imisId("RPWSS-214")
                        .jalMitraNames("Suresh Rao").jalMitraMobiles("919000000002")
                        .issue("Electricity Supply Disconnected").remarks("No water supply for past 6 days").build());

        String filename = pdfService.generate(sampleKpis(), 500L, "Binod Nimoli", "SECTION_OFFICER", priority, List.of());
        Path localPdf = tempDir.resolve(filename);
        assertThat(localPdf.toFile()).exists();

        // 3) Upload to MinIO (no WhatsApp / no Glific).
        MinioStorageService minio = new MinioStorageService(endpoint, ACCESS_KEY, SECRET_KEY);
        ReflectionTestUtils.setField(minio, "bucket", BUCKET);
        ReflectionTestUtils.setField(minio, "minioBaseUrl", endpoint);

        String url = minio.upload(localPdf);

        assertThat(url).endsWith("/" + BUCKET + "/" + filename);
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
