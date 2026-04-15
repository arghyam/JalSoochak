package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.dto.CodeCountDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.exception.FileValidationException;
import org.arghyam.jalsoochak.scheme.exception.UnsupportedFileTypeException;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.repository.SchemeUpdateRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeServiceImplTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    SchemeUploadChunkProcessor chunkProcessor;

    @Mock
    KafkaProducer kafkaProducer;

    @InjectMocks
    SchemeServiceImpl schemeService;

    @Captor
    ArgumentCaptor<List<SchemeCreateRecord>> insertRowsCaptor;

    @Captor
    ArgumentCaptor<List<SchemeUpdateRecord>> updateRowsCaptor;

    @Captor
    ArgumentCaptor<Map<String, Object>> kafkaPayloadCaptor;

    @BeforeEach
    void setUp() {
        TenantContext.setSchema("tenant_ka");

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("email", "admin@example.com")
                .claim("tenant_state_code", "ka")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, Collections.emptyList());

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);

    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void listSchemes_parsesStatuses_andClampsPagination() {
        SchemeDTO row = SchemeDTO.builder().id(1).stateSchemeId("SS-1").build();
        when(schemeDbRepository.listSchemes(
                "tenant_ka",
                "SS-1",
                "Scheme",
                "SearchName",
                2,
                3,
                "active",
                "scheme_name",
                "asc",
                0,
                100
        )).thenReturn(List.of(row));
        when(schemeDbRepository.countSchemes("tenant_ka", "SS-1", "Scheme", "SearchName", 2, 3, "active"))
                .thenReturn(1L);

        PageResponseDTO<SchemeDTO> res = schemeService.listSchemes(
                "KA", -1, 500, "scheme_name", "asc",
                "SS-1", "Scheme", "SearchName", "completed", "3", "active"
        );

        assertThat(res.getTotalElements()).isEqualTo(1);
        assertThat(res.getNumber()).isZero();
        assertThat(res.getSize()).isEqualTo(100);
        assertThat(res.getContent()).hasSize(1);
    }

    @Test
    void listSchemes_rejectsInvalidWorkStatus() {
        assertThatThrownBy(() -> schemeService.listSchemes(
                "KA", 0, 10, "id", "desc",
                null, null, null, "bad-status", null, null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid workStatus");
    }

    @Test
    void listSchemeMappings_parsesStatuses_andClampsPagination() {
        SchemeMappingDTO row = SchemeMappingDTO.builder().id(1L).stateSchemeId("SS-1").build();
        when(schemeDbRepository.listSchemeMappings(
                "tenant_ka",
                "SearchName",
                1,
                2,
                "inactive",
                "VLG-001",
                "North",
                "scheme_name",
                "desc",
                2,
                1
        )).thenReturn(List.of(row));
        when(schemeDbRepository.countSchemeMappings(
                "tenant_ka",
                "SearchName",
                1,
                2,
                "inactive",
                "VLG-001",
                "North"
        )).thenReturn(1L);

        PageResponseDTO<SchemeMappingDTO> res = schemeService.listSchemeMappings(
                "KA", 2, 0, "scheme_name", "desc", "SearchName", "ongoing", "2", "inactive", "VLG-001", "North"
        );

        assertThat(res.getSize()).isEqualTo(1);
        assertThat(res.getNumber()).isEqualTo(2);
        assertThat(res.getTotalElements()).isEqualTo(1);
        assertThat(res.getContent()).hasSize(1);
    }

    @Test
    void listSchemeMappings_rejectsInvalidOperatingStatus() {
        assertThatThrownBy(() -> schemeService.listSchemeMappings(
                "KA", 0, 10, "id", "desc",
                null, null, "bad-operating", null, null, null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid operatingStatus");
    }

    @Test
    void getSchemeCounts_andStatusCounts_returnsAggregates() {
        when(schemeDbRepository.countActiveInactiveSchemes("tenant_ka"))
                .thenReturn(new SchemeDbRepository.SchemeCounts(7, 3));
        when(schemeDbRepository.countSchemesTotal("tenant_ka")).thenReturn(10L);
        when(schemeDbRepository.countSchemesByWorkStatus("tenant_ka"))
                .thenReturn(List.of(CodeCountDTO.builder().status("Ongoing").count(7).build()));
        when(schemeDbRepository.countSchemesByOperatingStatus("tenant_ka"))
                .thenReturn(List.of(CodeCountDTO.builder().status("Operative").count(7).build()));

        SchemeCountsDTO counts = schemeService.getSchemeCounts("ka");
        SchemeStatusCountsDTO statusCounts = schemeService.getSchemeStatusCounts("ka");

        assertThat(counts.activeSchemes()).isEqualTo(7);
        assertThat(counts.inactiveSchemes()).isEqualTo(3);
        assertThat(statusCounts.totalSchemes()).isEqualTo(10);
        assertThat(statusCounts.statusCounts()).hasSize(2);
        assertThat(statusCounts.workStatusCounts()).hasSize(1);
        assertThat(statusCounts.operatingStatusCounts()).hasSize(1);
    }

    @Test
    void uploadSchemes_insertsRows_andPublishesAnalyticsEvent() {
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(99);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "schemes.csv",
                "text/csv",
                """
                        state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                        101,201,Scheme One,10,5,20,77.1,12.9,ongoing,operative
                        """.getBytes(StandardCharsets.UTF_8)
        );

        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of());
        when(schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of());
        when(chunkProcessor.insertSchemesChunk(eq("tenant_ka"), anyList())).thenReturn(1);
        when(schemeDbRepository.findSchemeAnalyticsRowsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(List.of(new SchemeDbRepository.SchemeAnalyticsRow(
                        1, "101", "201", "Scheme One", 12.9, 77.1, 1, 501, 701
                )));

        SchemeUploadResponseDTO res = schemeService.uploadSchemes(file);

        assertThat(res.getTotalRows()).isEqualTo(1);
        assertThat(res.getUploadedRows()).isEqualTo(1);
        verify(chunkProcessor).insertSchemesChunk(eq("tenant_ka"), insertRowsCaptor.capture());
        assertThat(insertRowsCaptor.getValue()).hasSize(1);
        assertThat(insertRowsCaptor.getValue().getFirst().createdBy()).isEqualTo(10);
        verify(kafkaProducer).publishJson(eq("scheme-service-topic"), kafkaPayloadCaptor.capture());
        assertThat(kafkaPayloadCaptor.getValue()).containsEntry("tenantId", 99);
        assertThat(kafkaPayloadCaptor.getValue()).containsEntry("schemeId", 1);
    }

    @Test
    void uploadSchemes_updatesExistingRow_whenSnapshotChanged() {
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(99);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "schemes.csv",
                "text/csv",
                """
                        state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                        101,201,Scheme One Updated,10,5,20,77.1,12.9,completed,operative
                        """.getBytes(StandardCharsets.UTF_8)
        );

        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("101", 42));
        when(schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("101", new SchemeDbRepository.SchemeSnapshot(
                        42, "101", "201", "Old Name", 5, 10, 20, 12.9, 77.1, 1, 1
                )));
        when(chunkProcessor.updateSchemesChunk(eq("tenant_ka"), anyList())).thenReturn(1);
        when(schemeDbRepository.findSchemeAnalyticsRowsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(List.of(new SchemeDbRepository.SchemeAnalyticsRow(
                        42, "101", "201", "Scheme One Updated", 12.9, 77.1, 1, 501, 701
                )));

        SchemeUploadResponseDTO res = schemeService.uploadSchemes(file);

        assertThat(res.getUploadedRows()).isEqualTo(1);
        verify(chunkProcessor).updateSchemesChunk(eq("tenant_ka"), updateRowsCaptor.capture());
        assertThat(updateRowsCaptor.getValue()).hasSize(1);
        assertThat(updateRowsCaptor.getValue().getFirst().id()).isEqualTo(42);
        assertThat(updateRowsCaptor.getValue().getFirst().updatedBy()).isEqualTo(10);
    }

    @Test
    void uploadSchemes_rejectsDuplicateUploadWhenNothingChanges() {
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(99);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "schemes.csv",
                "text/csv",
                """
                        state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                        101,201,Scheme One,10,5,20,77.1,12.9,ongoing,operative
                        """.getBytes(StandardCharsets.UTF_8)
        );

        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("101", 42));
        when(schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("101", new SchemeDbRepository.SchemeSnapshot(
                        42, "101", "201", "Scheme One", 5, 10, 20, 12.9, 77.1, 1, 1
                )));

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Duplicate upload");

        verify(chunkProcessor, never()).insertSchemesChunk(eq("tenant_ka"), anyList());
        verify(chunkProcessor, never()).updateSchemesChunk(eq("tenant_ka"), anyList());
    }

    @Test
    void uploadSchemes_rejectsUnsupportedFileType() {
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(99);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "schemes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("Only .csv and .xlsx files are supported");
    }

    @Test
    void uploadSchemes_rejectsFileWithOnlyHeader() {
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(99);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "schemes.csv",
                "text/csv",
                """
                        state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                        """.getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("No data rows found in uploaded file");
    }

    @Test
    void uploadSchemes_acceptsLegacyCentreHeader_andDefaultsOperatingStatus() {
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(99);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "schemes.csv",
                "text/csv",
                """
                        state_scheme_id,centre_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                        102,202,Legacy Scheme,10,5,20,77.2,12.8,1,
                        """.getBytes(StandardCharsets.UTF_8)
        );

        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of());
        when(schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of());
        when(chunkProcessor.insertSchemesChunk(eq("tenant_ka"), anyList())).thenReturn(1);
        when(schemeDbRepository.findSchemeAnalyticsRowsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(List.of());

        SchemeUploadResponseDTO res = schemeService.uploadSchemes(file);

        assertThat(res.getUploadedRows()).isEqualTo(1);
        verify(chunkProcessor).insertSchemesChunk(eq("tenant_ka"), insertRowsCaptor.capture());
        assertThat(insertRowsCaptor.getValue().getFirst().centreSchemeId()).isEqualTo("202");
        assertThat(insertRowsCaptor.getValue().getFirst().operatingStatus()).isEqualTo(1);
    }
}
