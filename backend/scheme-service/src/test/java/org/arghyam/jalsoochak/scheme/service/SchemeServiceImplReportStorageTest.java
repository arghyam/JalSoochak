package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.config.properties.StorageProperties;
import org.arghyam.jalsoochak.scheme.dto.ReportLinkResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.storage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the scheme and scheme-mapping CSV reports are stored and how their download link is built:
 * a fresh report is uploaded to the configured reports bucket under a tenant-scoped key, recorded
 * against that bucket and key, and linked through a presigned URL that carries a user-facing filename
 * and lives for the configured TTL; a cached report is linked without being uploaded again.
 */
@ExtendWith(MockitoExtension.class)
class SchemeServiceImplReportStorageTest {

    private static final String SCHEMA = "tenant_ka";
    private static final String REPORTS_BUCKET = "scheme-reports-under-test";
    private static final long PRESIGNED_TTL_SECONDS = 900L;
    private static final String PRESIGNED_URL = "https://reports.example.org/presigned";
    private static final int ACTOR_USER_ID = 10;
    private static final long DATA_VERSION = 7L;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    SchemeUploadChunkProcessor chunkProcessor;

    @Mock
    KafkaProducer kafkaProducer;

    @Mock
    ObjectStorageService objectStorageService;

    @Spy
    StorageProperties storageProperties = storageProperties();

    @Mock
    PiiEncryptionService piiEncryptionService;

    @InjectMocks
    SchemeServiceImpl schemeService;

    private final AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        TenantContext.setSchema(SCHEMA);
        authenticateAs("user-uuid", "ka");
        when(schemeDbRepository.findUserIdByUuid(SCHEMA, "user-uuid")).thenReturn(ACTOR_USER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void downloadSchemesReport_uploadsTheCsvToTheReportsBucketAndLinksItThroughAPresignedUrl() {
        givenNoCachedReport("SCHEME");
        doAnswer(invocation -> {
            Consumer<SchemeDTO> consumer = invocation.getArgument(1);
            consumer.accept(SchemeDTO.builder().id(1).uuid("u-1").stateSchemeId("SS-1").schemeName("Scheme One")
                    .build());
            return null;
        }).when(schemeDbRepository).streamAllSchemes(eq(SCHEMA), any());
        givenUploadsSucceed();

        ReportLinkResponseDTO response = schemeService.downloadSchemesReport();

        assertThat(response.getLink()).isEqualTo(PRESIGNED_URL);
        String objectKey = assertUploadedCsv("scheme",
                "id,uuid,state_scheme_id,centre_scheme_id,scheme_name,fhtc_count,planned_fhtc,house_hold_count,"
                        + "latitude,longitude,channel,work_status,operating_status");
        assertLinkedWithFilename(objectKey, "scheme_report_KA_");
        assertRecorded("SCHEME", objectKey);
    }

    @Test
    void downloadSchemeMappingsReport_uploadsTheCsvToTheReportsBucketAndLinksItThroughAPresignedUrl() {
        givenNoCachedReport("SCHEME_MAPPING");
        doAnswer(invocation -> {
            Consumer<SchemeMappingDTO> consumer = invocation.getArgument(1);
            consumer.accept(new SchemeMappingDTO(1L, 1, "SS-1", "Scheme One", "101", "Village", "North"));
            return null;
        }).when(schemeDbRepository).streamAllSchemeMappings(eq(SCHEMA), any());
        givenUploadsSucceed();

        ReportLinkResponseDTO response = schemeService.downloadSchemeMappingsReport();

        assertThat(response.getLink()).isEqualTo(PRESIGNED_URL);
        String objectKey = assertUploadedCsv("scheme_mapping",
                "id,scheme_id,state_scheme_id,scheme_name,village_lgd_code,village_name,sub_division_name");
        assertLinkedWithFilename(objectKey, "scheme_mapping_report_KA_");
        assertRecorded("SCHEME_MAPPING", objectKey);
    }

    @Test
    void downloadSchemesReport_linksACachedReportWithoutUploadingItAgain() {
        String cachedKey = "ka/reports/scheme/2026/09/cached.csv";
        when(schemeDbRepository.currentDataVersion(SCHEMA, "SCHEME")).thenReturn(DATA_VERSION);
        when(schemeDbRepository.findReportObjectKey(eq(SCHEMA), eq("SCHEME"), eq("csv"), anyString(),
                eq(DATA_VERSION))).thenReturn(Optional.of(cachedKey));
        givenPresigningSucceeds();

        ReportLinkResponseDTO response = schemeService.downloadSchemesReport();

        assertThat(response.getLink()).isEqualTo(PRESIGNED_URL);
        assertLinkedWithFilename(cachedKey, "scheme_report_KA_");
        verify(objectStorageService, never()).upload(anyString(), anyString(), any(InputStream.class), anyLong(),
                anyString());
        verify(schemeDbRepository, never()).streamAllSchemes(any(), any());
    }

    @Test
    void downloadSchemesReport_recordsNothingWhenTheUploadFails() {
        givenNoCachedReport("SCHEME");
        RuntimeException failure = new RuntimeException("upload failed");
        doThrow(failure).when(objectStorageService)
                .upload(anyString(), anyString(), any(InputStream.class), anyLong(), eq("text/csv"));

        assertThatThrownBy(() -> schemeService.downloadSchemesReport()).isSameAs(failure);

        verify(objectStorageService, never()).presignedGetUrl(anyString(), anyString(), any(), anyString());
        verify(schemeDbRepository, never()).insertReportRecord(any(), any(), any(), any(), any(), any(), anyLong(),
                any(), any(), any(), any(), eq(ACTOR_USER_ID));
    }

    private void givenNoCachedReport(String reportType) {
        when(schemeDbRepository.currentDataVersion(SCHEMA, reportType)).thenReturn(DATA_VERSION);
        when(schemeDbRepository.findReportObjectKey(eq(SCHEMA), eq(reportType), eq("csv"), anyString(),
                eq(DATA_VERSION))).thenReturn(Optional.empty());
    }

    private void givenUploadsSucceed() {
        doAnswer(invocation -> {
            uploadedBytes.set(invocation.<InputStream>getArgument(2).readAllBytes());
            return null;
        }).when(objectStorageService)
                .upload(anyString(), anyString(), any(InputStream.class), anyLong(), eq("text/csv"));
        givenPresigningSucceeds();
    }

    private void givenPresigningSucceeds() {
        when(objectStorageService.presignedGetUrl(anyString(), anyString(), any(Duration.class), anyString()))
                .thenReturn(URI.create(PRESIGNED_URL));
    }

    /** Asserts the upload's bucket, key, declared size and content, and returns the key. */
    private String assertUploadedCsv(String keyType, String header) {
        ArgumentCaptor<Long> size = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).upload(eq(REPORTS_BUCKET), key.capture(), any(InputStream.class), size.capture(),
                eq("text/csv"));
        assertThat(key.getValue()).matches("ka/reports/" + keyType + "/\\d{4}/\\d{2}/" + UUID_PATTERN + "\\.csv");
        assertThat(size.getValue()).isEqualTo(uploadedBytes.get().length);
        assertThat(new String(uploadedBytes.get(), StandardCharsets.UTF_8)).startsWith(header + "\r\n");
        return key.getValue();
    }

    private void assertLinkedWithFilename(String objectKey, String filenamePrefix) {
        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).presignedGetUrl(eq(REPORTS_BUCKET), eq(objectKey),
                eq(Duration.ofSeconds(PRESIGNED_TTL_SECONDS)), filename.capture());
        assertThat(filename.getValue()).matches(filenamePrefix + "\\d{8}_\\d{4}\\.csv");
    }

    private void assertRecorded(String reportType, String objectKey) {
        verify(schemeDbRepository).insertReportRecord(eq(SCHEMA), anyString(), eq(reportType), eq("csv"),
                anyString(), eq("{}"), eq(DATA_VERSION), eq(REPORTS_BUCKET), eq(objectKey), eq(1),
                eq((long) uploadedBytes.get().length), eq(ACTOR_USER_ID));
    }

    private static StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setReportsBucket(REPORTS_BUCKET);
        properties.setPresignedTtlSeconds(PRESIGNED_TTL_SECONDS);
        return properties;
    }

    private static void authenticateAs(String subject, String tenantStateCode) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_state_code", tenantStateCode)
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
    }
}
