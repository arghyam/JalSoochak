package org.arghyam.jalsoochak.message.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: renders the water-report PDFs and uploads them to a throwaway MinIO started via
 * Testcontainers — no Kafka, Postgres or Glific. Requires Docker.
 *
 * <p>Covers the half of the contract a mocked MinIO client cannot: that the bucket is created when
 * absent, that a folder-structured object key survives the round trip as a real path, and that the
 * URL handed onward addresses the object that was actually stored.</p>
 */
@Testcontainers
class DailyReportMinioUploadIT {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

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

    private String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private MinioStorageService storageService() {
        MinioStorageService minio = new MinioStorageService(endpoint(), ACCESS_KEY, SECRET_KEY);
        ReflectionTestUtils.setField(minio, "bucket", "escalation-reports");
        ReflectionTestUtils.setField(minio, "minioBaseUrl", endpoint());
        return minio;
    }

    @Test
    void generatesTheDailyReportAndUploadsItUnderItsRoleAndDateFolder() throws Exception {
        DailyReportPdfService pdfService = new DailyReportPdfService();
        ReflectionTestUtils.setField(pdfService, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(pdfService, "dashboardUrl", "https://jalsoochak.jjmbrain.in/staff/");
        ReflectionTestUtils.setField(pdfService, "supportPhone", "919999999999");

        List<ReportSchemeRow> noSupply = List.of(
                ReportSchemeRow.builder().schemeId(1).schemeName("Rampur WSS").imisId("RPWSS-108")
                        .jalMitraNames("Ramesh Kumar").jalMitraMobiles("919000000001").build());

        LocalDate reportDate = LocalDate.of(2026, 7, 19);
        Path localPdf = pdfService.generate(dailyKpis(), 21343L, "Binod Nimoli", "SECTION_OFFICER",
                noSupply, List.of());
        String filename = localPdf.getFileName().toString();
        assertThat(localPdf.toFile()).exists();

        String objectKey = ReportFileNaming.dailyObjectKey("SECTION_OFFICER", filename, reportDate);
        String url = storageService().upload(localPdf, ReportFileNaming.DAILY_BUCKET, objectKey);

        assertThat(objectKey).isEqualTo("SO/2026-07-19/" + filename);
        assertThat(url).endsWith("/" + ReportFileNaming.DAILY_BUCKET + "/" + objectKey);
        // The slashes must stay slashes: percent-encoding the whole key would store one object literally
        // named "SO%2F2026-07-19%2F…", and the URL would then address something that is not there.
        assertThat(url).doesNotContain("%2F");
        assertStoredAt(ReportFileNaming.DAILY_BUCKET, objectKey);
    }

    @Test
    void generatesTheSdoWeeklyReportAndUploadsItUnderItsWeekFolder() throws Exception {
        WeeklyReportPdfService pdfService = new WeeklyReportPdfService();
        ReflectionTestUtils.setField(pdfService, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(pdfService, "dashboardUrl", "https://jalsoochak.jjmbrain.in/staff/");
        ReflectionTestUtils.setField(pdfService, "supportPhone", "");

        LocalDate weekStart = LocalDate.of(2026, 7, 13);
        LocalDate weekEnd = LocalDate.of(2026, 7, 19);
        Path localPdf = pdfService.generate(weeklyKpis(), 5521L, "Bharat Sharma", "SUB_DIVISIONAL_OFFICER",
                List.of(), List.of(), List.of(), List.of());
        String filename = localPdf.getFileName().toString();
        assertThat(localPdf.toFile()).exists();

        String objectKey = ReportFileNaming.weeklyObjectKey("SUB_DIVISIONAL_OFFICER", filename, weekStart, weekEnd);
        String url = storageService().upload(localPdf, ReportFileNaming.WEEKLY_BUCKET, objectKey);

        assertThat(objectKey).isEqualTo("SDO/2026-07-13_to_2026-07-19/" + filename);
        assertThat(url).endsWith("/" + ReportFileNaming.WEEKLY_BUCKET + "/" + objectKey);
        assertStoredAt(ReportFileNaming.WEEKLY_BUCKET, objectKey);
    }

    @Test
    void createsTheBucketWhenItDoesNotExistYet() throws Exception {
        // A fresh environment has neither water bucket, and a first run that failed on a missing one
        // would lose that day's reports for every officer. Uses its own bucket name so the assertion
        // holds regardless of what the other tests in this class have already created.
        String freshBucket = "probe-water-reports";
        MinioClient admin = MinioClient.builder().endpoint(endpoint())
                .credentials(ACCESS_KEY, SECRET_KEY).build();
        assertThat(admin.bucketExists(BucketExistsArgs.builder().bucket(freshBucket).build())).isFalse();

        Path pdf = java.nio.file.Files.writeString(tempDir.resolve("probe.pdf"), "%PDF-1.4 test");
        storageService().upload(pdf, freshBucket, "SO/2026-01-01/probe.pdf");

        assertThat(admin.bucketExists(BucketExistsArgs.builder().bucket(freshBucket).build())).isTrue();
    }

    /** Fetches the object back, so the assertion is about what MinIO holds rather than what we sent. */
    private void assertStoredAt(String bucket, String objectKey) throws Exception {
        MinioClient client = MinioClient.builder().endpoint(endpoint())
                .credentials(ACCESS_KEY, SECRET_KEY).build();
        try (InputStream in = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            assertThat(in.readNBytes(4)).isEqualTo("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    private DailyReportKpis dailyKpis() {
        return DailyReportKpis.builder()
                .reportDate("2026-07-19")
                .cutoffIst("2026-07-19T16:00:00")
                .totalSchemes(148)
                .schemesSupplying(142)
                .schemesNotSupplying(6)
                .householdsWithSupply(14200)
                .householdsWithSupplyPct(95.9)
                .householdsWithoutSupply(600)
                .householdsWithoutSupplyPct(4.1)
                .totalHouseholds(14800)
                .avgLpcd(63)
                .anomalousCount(11)
                .noSupplySchemeIds(List.of(1))
                .schemeAnomalies(List.of())
                .build();
    }

    private WeeklyReportKpis weeklyKpis() {
        return WeeklyReportKpis.builder()
                .weekStart("2026-07-13")
                .weekEnd("2026-07-19")
                .previousWeekStart("2026-07-06")
                .previousWeekEnd("2026-07-12")
                .week(WeeklyReportKpis.WeekKpis.builder()
                        .totalSchemes(148).schemesSupplying(142).schemesNotSupplying(6)
                        .schemesLowLpcd(4).avgLpcd(63).build())
                .previousWeek(WeeklyReportKpis.WeekKpis.builder()
                        .totalSchemes(148).schemesSupplying(140).schemesNotSupplying(8)
                        .schemesLowLpcd(7).avgLpcd(61).build())
                .noSupplySchemeIds(List.of())
                .lowSupplyDaysSchemeIds(List.of())
                .lowLpcdSchemeIds(List.of())
                .sectionOfficerSummaries(List.of())
                .build();
    }
}
