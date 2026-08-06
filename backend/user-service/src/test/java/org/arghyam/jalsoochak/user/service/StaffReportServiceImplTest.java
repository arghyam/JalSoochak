package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.config.properties.StorageProperties;
import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.repository.DataVersionRepository;
import org.arghyam.jalsoochak.user.repository.ReportsRepository;
import org.arghyam.jalsoochak.user.repository.TenantStaffRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.report.ReportWriter;
import org.arghyam.jalsoochak.user.service.report.StaffReportDefinition;
import org.arghyam.jalsoochak.user.service.report.writer.CsvReportWriter;
import org.arghyam.jalsoochak.user.service.report.writer.XlsxReportWriter;
import org.arghyam.jalsoochak.user.service.serviceImpl.StaffReportServiceImpl;
import org.arghyam.jalsoochak.user.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaffReportServiceImpl")
class StaffReportServiceImplTest {

    private static final String TENANT_CODE = "MP";
    private static final String SCHEMA = "tenant_mp";
    private static final String KC_UUID = "kc-uuid-1";

    @Mock TenantStaffRepository tenantStaffRepository;
    @Mock UserTenantRepository userTenantRepository;
    @Mock ReportsRepository reportsRepository;
    @Mock DataVersionRepository dataVersionRepository;
    @Mock ObjectStorageService objectStorageService;

    private StaffReportServiceImpl service;
    private StorageProperties storageProperties;
    private final TenantUserRecord callingUser = new TenantUserRecord(
            7L, 1, "919999999999", "admin@test.com", 2L, "STATE_ADMIN",
            "Admin", KC_UUID, TenantUserStatus.ACTIVE.code, null);

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setEnabled(true);
        storageProperties.setReportsBucket("staff-reports");
        storageProperties.setPresignedTtlSeconds(60);
        StaffReportDefinition definition = new StaffReportDefinition(tenantStaffRepository);
        List<ReportWriter> writers = List.of(new CsvReportWriter(), new XlsxReportWriter());
        service = new StaffReportServiceImpl(
                userTenantRepository, reportsRepository, dataVersionRepository,
                objectStorageService, storageProperties,
                definition, writers);
    }

    private Authentication authentication() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("sub", KC_UUID)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private ReportsRepository.ReportRecord cachedRow(String format) {
        return new ReportsRepository.ReportRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "TENANT_STAFF", format, "h".repeat(64), 5L,
                "staff-reports", "mp/reports/staff_users/2026/05/x." + format.toLowerCase(),
                3, 256L, 7L,
                OffsetDateTime.of(2026, 5, 19, 12, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("cache hit short-circuits — no fetch, no generation, no upload, no insert")
    void cacheHitReturnsCachedTrue() {
        when(dataVersionRepository.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).thenReturn(5L);
        when(reportsRepository.findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("CSV"), anyString(), eq(5L)))
                .thenReturn(Optional.of(cachedRow("CSV")));
        when(objectStorageService.presignedGetUrl(eq("staff-reports"), anyString(), any(Duration.class), anyString()))
                .thenReturn(URI.create("https://example/staff.csv?sig=x"));

        ReportResponseDTO response = service.generate(TENANT_CODE, ReportFormat.CSV,
                new StaffReportRequestDTO(null, null, null), authentication());

        assertThat(response.cached()).isTrue();
        assertThat(response.format()).isEqualTo("CSV");
        assertThat(response.downloadUrl()).contains("staff.csv");
        verify(tenantStaffRepository, never())
                .listAllStaffForExport(anyString(), any(), any(), anyString());
        verify(reportsRepository, never()).insertIfAbsent(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("cache hit forwards human-friendly filename to presigner")
    void cacheHitPassesDownloadFilename() {
        when(dataVersionRepository.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).thenReturn(5L);
        when(reportsRepository.findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("CSV"), anyString(), eq(5L)))
                .thenReturn(Optional.of(cachedRow("CSV")));
        when(objectStorageService.presignedGetUrl(eq("staff-reports"), anyString(), any(Duration.class), anyString()))
                .thenReturn(URI.create("https://example/staff.csv?sig=x"));

        service.generate(TENANT_CODE, ReportFormat.CSV,
                new StaffReportRequestDTO(null, null, null), authentication());

        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).presignedGetUrl(eq("staff-reports"), anyString(),
                any(Duration.class), filename.capture());
        assertThat(filename.getValue())
                .startsWith("staff_report_MP_")
                .endsWith(".csv");
    }

    @Test
    @DisplayName("cache miss fetches rows, uploads, inserts and returns cached=false")
    void cacheMissGenerates() {
        when(dataVersionRepository.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).thenReturn(5L);
        when(reportsRepository.findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("CSV"), anyString(), eq(5L)))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(cachedRow("CSV")));
        when(userTenantRepository.findUserByKeycloakUuid(SCHEMA, KC_UUID))
                .thenReturn(Optional.of(callingUser));
        when(tenantStaffRepository.listAllStaffForExport(eq(SCHEMA), any(), any(), any()))
                .thenReturn(List.of(TenantStaffResponseDTO.builder()
                        .id(1L).uuid("u1").title("Alice")
                        .email("a@x").phoneNumber("9").status(TenantUserStatus.ACTIVE)
                        .role("SUB_DIVISIONAL_OFFICER").schemes(List.of()).build()));
        when(reportsRepository.insertIfAbsent(eq(SCHEMA), any(), anyString())).thenReturn(true);
        when(objectStorageService.presignedGetUrl(eq("staff-reports"), anyString(), any(Duration.class), anyString()))
                .thenReturn(URI.create("https://example/staff.csv?sig=y"));

        ReportResponseDTO response = service.generate(TENANT_CODE, ReportFormat.CSV,
                new StaffReportRequestDTO(null, null, null), authentication());

        assertThat(response.cached()).isFalse();
        ArgumentCaptor<ReportsRepository.ReportRecord> recCap =
                ArgumentCaptor.forClass(ReportsRepository.ReportRecord.class);
        verify(reportsRepository).insertIfAbsent(eq(SCHEMA), recCap.capture(), anyString());
        assertThat(recCap.getValue().dataVersion()).isEqualTo(5L);
        assertThat(recCap.getValue().generatedBy()).isEqualTo(7L);
        assertThat(recCap.getValue().objectKey())
                .startsWith("mp/reports/staff_users/");
        verify(objectStorageService).upload(eq("staff-reports"), anyString(),
                any(), anyLong(), eq("text/csv"));
    }

    @Test
    @DisplayName("concurrent winner — insert returns false → second findByCacheKey wins, cached=true")
    void concurrentWinnerReturnsCachedTrue() {
        when(dataVersionRepository.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).thenReturn(5L);
        when(reportsRepository.findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("XLSX"), anyString(), eq(5L)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(cachedRow("XLSX")));
        when(userTenantRepository.findUserByKeycloakUuid(SCHEMA, KC_UUID))
                .thenReturn(Optional.of(callingUser));
        when(tenantStaffRepository.listAllStaffForExport(eq(SCHEMA), any(), any(), any()))
                .thenReturn(List.of());
        when(reportsRepository.insertIfAbsent(eq(SCHEMA), any(), anyString())).thenReturn(false);
        when(objectStorageService.presignedGetUrl(eq("staff-reports"), anyString(), any(Duration.class), anyString()))
                .thenReturn(URI.create("https://example/staff.xlsx?sig=z"));

        ReportResponseDTO response = service.generate(TENANT_CODE, ReportFormat.XLSX,
                new StaffReportRequestDTO(null, null, null), authentication());

        assertThat(response.cached()).isTrue();
        assertThat(response.format()).isEqualTo("XLSX");
        verify(objectStorageService).upload(eq("staff-reports"), anyString(),
                any(), anyLong(), eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        verify(reportsRepository, times(2)).findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("XLSX"),
                anyString(), eq(5L));
    }

    @Test
    @DisplayName("DB persistence failure after upload triggers object deletion to avoid orphans")
    void orphanedUploadDeletedOnDbFailure() {
        when(dataVersionRepository.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).thenReturn(3L);
        when(reportsRepository.findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("CSV"), anyString(), eq(3L)))
                .thenReturn(Optional.empty());
        when(userTenantRepository.findUserByKeycloakUuid(SCHEMA, KC_UUID))
                .thenReturn(Optional.of(callingUser));
        when(tenantStaffRepository.listAllStaffForExport(eq(SCHEMA), any(), any(), any()))
                .thenReturn(List.of());
        when(reportsRepository.insertIfAbsent(eq(SCHEMA), any(), anyString()))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.generate(TENANT_CODE, ReportFormat.CSV,
                new StaffReportRequestDTO(null, null, null), authentication()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");

        verify(objectStorageService).upload(eq("staff-reports"), anyString(), any(), anyLong(), anyString());
        verify(objectStorageService).delete(eq("staff-reports"), anyString());
    }

    @Test
    @DisplayName("filter normalization is deterministic — different order, same hash & cache key")
    void filterNormalizationDeterministic() {
        when(dataVersionRepository.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).thenReturn(2L);
        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        when(reportsRepository.findByCacheKey(eq(SCHEMA), eq("TENANT_STAFF"), eq("CSV"), hashCap.capture(), eq(2L)))
                .thenReturn(Optional.of(cachedRow("CSV")));
        when(objectStorageService.presignedGetUrl(any(), any(), any(), any()))
                .thenReturn(URI.create("https://x"));

        service.generate(TENANT_CODE, ReportFormat.CSV,
                new StaffReportRequestDTO(List.of("SUB_DIVISIONAL_OFFICER", "SECTION_OFFICER"), "ACTIVE", null),
                authentication());
        service.generate(TENANT_CODE, ReportFormat.CSV,
                new StaffReportRequestDTO(List.of("section_officer", "sub_divisional_officer"), " active ", null),
                authentication());

        List<String> hashes = hashCap.getAllValues();
        assertThat(hashes).hasSize(2);
        assertThat(hashes.get(0)).isEqualTo(hashes.get(1));
    }
}
