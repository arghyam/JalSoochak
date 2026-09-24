package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.config.StorageConfig;
import org.arghyam.jalsoochak.message.config.StorageProperties;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.storage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test: renders the water-report PDFs and uploads them through the storage port to a
 * throwaway S3-compatible store started via Testcontainers — no Kafka, Postgres or WhatsApp provider.
 * Requires Docker.
 *
 * <p>Covers the half of the contract a mocked S3 client cannot: that a missing bucket is detected
 * and created, that a folder-structured object key survives the round trip as a real path, and that
 * the URL handed onward addresses the object that was actually stored. The client is built by
 * {@link StorageConfig}, so the path-style wiring a non-AWS store needs is exercised too.</p>
 */
@Testcontainers
class ReportStorageUploadIT {

    /** The image's default root credentials. */
    private static final String CREDENTIAL = "minioadmin";

    @Container
    static final GenericContainer<?> STORE =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withCommand("server /data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @TempDir
    Path tempDir;

    private S3Client s3Client;
    private ObjectStorageService storage;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(true);
        props.setEndpoint(endpoint());
        props.setAccessKey(CREDENTIAL);
        props.setSecretKey(CREDENTIAL);
        props.setPublicBaseUrl(endpoint());
        StorageConfig config = new StorageConfig();
        s3Client = config.s3Client(props);
        storage = config.objectStorageService(s3Client, props);
    }

    @AfterEach
    void tearDown() {
        s3Client.close();
    }

    private static String endpoint() {
        return "http://" + STORE.getHost() + ":" + STORE.getMappedPort(9000);
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
        String url = uploadReport(localPdf, ReportFileNaming.DAILY_BUCKET, objectKey);

        assertThat(objectKey).isEqualTo("SO/2026-07-19/" + filename);
        assertThat(url).isEqualTo(endpoint() + "/" + ReportFileNaming.DAILY_BUCKET + "/" + objectKey);
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
        String url = uploadReport(localPdf, ReportFileNaming.WEEKLY_BUCKET, objectKey);

        assertThat(objectKey).isEqualTo("SDO/2026-07-13_to_2026-07-19/" + filename);
        assertThat(url).endsWith("/" + ReportFileNaming.WEEKLY_BUCKET + "/" + objectKey);
        assertStoredAt(ReportFileNaming.WEEKLY_BUCKET, objectKey);
    }

    @Test
    void createsTheBucketWhenItDoesNotExistYet() {
        // A fresh environment has neither water bucket, and a first run that failed on a missing one
        // would lose that day's reports for every officer. Uses its own bucket name so the assertion
        // holds regardless of what the other tests in this class have already created.
        String freshBucket = "probe-water-reports";
        HeadBucketRequest head = HeadBucketRequest.builder().bucket(freshBucket).build();
        assertThatThrownBy(() -> s3Client.headBucket(head)).isInstanceOf(NoSuchBucketException.class);

        storage.ensureBucket(freshBucket);

        assertThatCode(() -> s3Client.headBucket(head)).doesNotThrowAnyException();
        // Every report upload asks again, so a bucket that already exists must be left alone.
        assertThatCode(() -> storage.ensureBucket(freshBucket)).doesNotThrowAnyException();
    }

    /** Uploads the way the router does: bucket first, then the PDF, then the URL it hands onward. */
    private String uploadReport(Path pdf, String bucket, String objectKey) throws IOException {
        storage.ensureBucket(bucket);
        try (InputStream content = Files.newInputStream(pdf)) {
            storage.upload(bucket, objectKey, content, Files.size(pdf), "application/pdf");
        }
        return storage.publicUrl(bucket, objectKey).toString();
    }

    /** Fetches the object back, so the assertion is about what the store holds rather than what we sent. */
    private void assertStoredAt(String bucket, String objectKey) {
        ResponseBytes<GetObjectResponse> stored =
                s3Client.getObjectAsBytes(request -> request.bucket(bucket).key(objectKey));
        assertThat(stored.response().contentType()).isEqualTo("application/pdf");
        assertThat(new String(stored.asByteArray(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
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
