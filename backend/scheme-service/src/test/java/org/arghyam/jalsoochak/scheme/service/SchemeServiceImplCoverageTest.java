package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.exception.FileValidationException;
import org.arghyam.jalsoochak.scheme.exception.UnsupportedFileTypeException;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeServiceImplCoverageTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    SchemeUploadChunkProcessor chunkProcessor;

    @Mock
    KafkaProducer kafkaProducer;

    @InjectMocks
    SchemeServiceImpl schemeService;

    @Captor
    ArgumentCaptor<Map<String, Object>> payloadCaptor;

    @BeforeEach
    void setUp() {
        TenantContext.setSchema("tenant_ka");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadSchemes_throwsDuplicateUploadWhenEverythingIsUnchanged() {
        setJwtAuth("admin@example.com", "ka", false);

        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);
        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("ss-1", 1));
        when(schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of(
                        "ss-1",
                        new SchemeDbRepository.SchemeSnapshot(
                                1, "SS-1", "C-1", "Scheme One",
                                8, 10, 20, 12.34, 77.56, 1, 1
                        )
                ));

        String csv = """
                state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                SS-1,C-1,Scheme One,10,8,20,77.56,12.34,Ongoing,
                """;
        MockMultipartFile file = csv("scheme-upload.csv", csv);

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Duplicate upload");
    }

    @Test
    void uploadSchemes_throwsForEmptyFile() {
        setJwtAuth("admin@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);

        MockMultipartFile file = new MockMultipartFile("file", "scheme-upload.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Uploaded file is empty");
    }

    @Test
    void uploadSchemes_throwsForDuplicateStateSchemeIdInFile() {
        setJwtAuth("admin@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);

        String csv = """
                state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                SS-1,C-1,Scheme One,10,8,20,77.56,12.34,Ongoing,Operative
                ss-1,C-2,Scheme Two,12,9,21,77.57,12.35,Ongoing,Operative
                """;

        assertThatThrownBy(() -> schemeService.uploadSchemes(csv("scheme-upload.csv", csv)))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Validation failed for uploaded file")
                .satisfies(ex -> assertThat(((FileValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getMessage()).contains("Duplicate state_scheme_id")));
    }

    @Test
    void uploadSchemes_usesPreferredUsernameWhenEmailClaimMissing() {
        setJwtPreferredUsernameAuth("preferred@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "preferred@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scheme-upload.txt",
                "text/plain",
                "x".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(UnsupportedFileTypeException.class);

        verify(schemeDbRepository).findUserIdByEmail("tenant_ka", "preferred@example.com");
    }

    @Test
    void uploadSchemes_throwsForbiddenForTenantMismatch() {
        setJwtAuth("admin@example.com", "tn", false);

        MockMultipartFile file = csv("scheme-upload.csv", "state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status\n");

        assertThatThrownBy(() -> schemeService.uploadSchemes(file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void uploadSchemes_throwsUnauthorizedWhenAuthenticationIsNotJwt() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken("user", "pwd"));
        SecurityContextHolder.setContext(context);

        assertThatThrownBy(() -> schemeService.uploadSchemes(csv("scheme-upload.csv", "x")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(schemeDbRepository);
    }

    @Test
    void uploadSchemeMappings_throwsForDuplicateMappingInFile() {
        setJwtAuth("admin@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);
        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList())).thenReturn(Map.of("ss-1", 1));
        when(schemeDbRepository.findLgdIdsByCodes(eq("tenant_ka"), anyList())).thenReturn(Map.of("vlg-001", 1001));
        when(schemeDbRepository.findDepartmentIdsByTitles(eq("tenant_ka"), anyList())).thenReturn(Map.of("north", 2001));

        String csv = """
                state_scheme_id,village_lgd_code,sub_division_name
                SS-1,VLG-001,North
                ss-1,vlg-001,north
                """;

        assertThatThrownBy(() -> schemeService.uploadSchemeMappings(csv("mapping.csv", csv)))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Validation failed for uploaded file")
                .satisfies(ex -> assertThat(((FileValidationException) ex).getErrors())
                        .anySatisfy(err -> assertThat(err.getMessage()).contains("Duplicate mapping")));
    }

    @Test
    void uploadSchemeMappings_throwsWhenNoDataRowsPresent() {
        setJwtAuth("admin@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);

        String csv = "state_scheme_id,village_lgd_code,sub_division_name\n";

        assertThatThrownBy(() -> schemeService.uploadSchemeMappings(csv("mapping.csv", csv)))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("No data rows found in uploaded file");
    }

    @Test
    void listSchemes_throwsBadRequestForInvalidWorkStatus() {
        assertThatThrownBy(() -> schemeService.listSchemes(
                "ka",
                0,
                10,
                "id",
                "desc",
                null,
                null,
                null,
                "invalid",
                null,
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void listSchemeMappings_throwsBadRequestForInvalidOperatingStatus() {
        assertThatThrownBy(() -> schemeService.listSchemeMappings(
                "ka",
                0,
                10,
                "id",
                "desc",
                null,
                null,
                "invalid",
                null,
                null,
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateSchemeStatuses_updatesBothStatuses() {
        setJwtAuth("admin@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);
        when(schemeDbRepository.updateSchemeStatusesById("tenant_ka", 77, 2, 1, 10)).thenReturn(true);
        when(schemeDbRepository.findSchemeAnalyticsRowsBySchemeIds("tenant_ka", List.of(77)))
                .thenReturn(List.of(
                        new SchemeDbRepository.SchemeAnalyticsRow(77, "101", "201", "Scheme Updated", 11.11, 22.22, 2, 1, 501, 601)
                ));

        SchemeStatusUpdateRequestDTO request = new SchemeStatusUpdateRequestDTO();
        request.setWorkStatus("Completed");
        request.setOperatingStatus("Operative");

        schemeService.updateSchemeStatuses("ka", 77, request);

        verify(schemeDbRepository).updateSchemeStatusesById("tenant_ka", 77, 2, 1, 10);
        verify(kafkaProducer).publishJson(eq("scheme-service-topic"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("eventType", "SCHEME_UPDATED")
                .containsEntry("schemeId", 77)
                .containsEntry("tenantId", 200)
                .containsEntry("status", 1)
                .containsEntry("operating_status", 1)
                .containsEntry("work_status", 2);
    }

    @Test
    void updateSchemeStatuses_throwsWhenNoFieldsProvided() {
        SchemeStatusUpdateRequestDTO request = new SchemeStatusUpdateRequestDTO();
        assertThatThrownBy(() -> schemeService.updateSchemeStatuses("ka", 77, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateSchemeStatuses_throwsNotFoundWhenSchemeMissing() {
        setJwtAuth("admin@example.com", "ka", false);
        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);
        when(schemeDbRepository.updateSchemeStatusesById("tenant_ka", 999, null, 2, 10)).thenReturn(false);

        SchemeStatusUpdateRequestDTO request = new SchemeStatusUpdateRequestDTO();
        request.setOperatingStatus("Non-Operative");

        assertThatThrownBy(() -> schemeService.updateSchemeStatuses("ka", 999, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void uploadSchemes_successfullyInsertsAndUpdatesRows() {
        setJwtAuth("admin@example.com", "ka", false);

        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
        when(schemeDbRepository.findTenantIdByUserId("tenant_ka", 10)).thenReturn(200);
        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("ss-existing", 77));
        when(schemeDbRepository.findSchemeSnapshotsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of(
                        "ss-existing",
                        new SchemeDbRepository.SchemeSnapshot(
                                77, "SS-EXISTING", "C-2", "Old Name",
                                8, 10, 20, 12.34, 77.56, 1, 1
                        )
                ));
        when(chunkProcessor.insertSchemesChunk(eq("tenant_ka"), anyList())).thenReturn(1);
        when(chunkProcessor.updateSchemesChunk(eq("tenant_ka"), anyList())).thenReturn(1);
        when(schemeDbRepository.findSchemeAnalyticsRowsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(List.of(
                        new SchemeDbRepository.SchemeAnalyticsRow(99, "101", "201", "Scheme New", 11.11, 22.22, 2, 1, 501, 601),
                        new SchemeDbRepository.SchemeAnalyticsRow(77, "102", "ABC", "Scheme Existing Updated", 33.33, 44.44, 1, 2, 502, null)
                ));

        String csv = """
                state_scheme_id,center_scheme_id,scheme_name,planned_fhtc,achieved_fhtc,house_hold_count,longitude,latitude,work_status,operating_status
                SS-NEW,C-1,Scheme New,12,10,30,22.22,11.11,Completed,Operative
                SS-EXISTING,C-2,Scheme Existing Updated,10,9,21,44.44,33.33,Ongoing,Non-Operative
                """;

        SchemeUploadResponseDTO response = schemeService.uploadSchemes(csv("scheme-upload.csv", csv));

        assertThat(response.getMessage()).isEqualTo("Schemes uploaded successfully");
        assertThat(response.getTotalRows()).isEqualTo(2);
        assertThat(response.getUploadedRows()).isEqualTo(2);

        verify(chunkProcessor).insertSchemesChunk(eq("tenant_ka"), anyList());
        verify(chunkProcessor).updateSchemesChunk(eq("tenant_ka"), anyList());
        verify(kafkaProducer, times(2)).publishJson(eq("scheme-service-topic"), payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues())
                .anySatisfy(payload -> {
                    assertThat(payload.get("eventType")).isEqualTo("SCHEME_UPDATED");
                    assertThat(payload.get("tenantId")).isEqualTo(200);
                    assertThat(payload.get("stateSchemeId")).isEqualTo(101);
                    assertThat(payload.get("operating_status")).isEqualTo(1);
                    assertThat(payload.get("work_status")).isEqualTo(2);
                })
                .anySatisfy(payload -> {
                    assertThat(payload.get("stateSchemeId")).isEqualTo(102);
                    assertThat(payload.get("centreSchemeId")).isEqualTo(0);
                    assertThat(payload.get("operating_status")).isEqualTo(2);
                    assertThat(payload.get("work_status")).isEqualTo(1);
                });
    }

    @Test
    void listSchemes_returnsPageOnHappyPath() {
        SchemeDTO dto = SchemeDTO.builder()
                .id(1)
                .stateSchemeId("SS-1")
                .schemeName("Scheme One")
                .build();
        when(schemeDbRepository.listSchemes(
                "tenant_ka",
                "SS",
                "Scheme",
                "query",
                2,
                3,
                "ACTIVE",
                "scheme_name",
                "asc",
                0,
                100
        )).thenReturn(List.of(dto));
        when(schemeDbRepository.countSchemes("tenant_ka", "SS", "Scheme", "query", 2, 3, "ACTIVE")).thenReturn(1L);

        PageResponseDTO<SchemeDTO> page = schemeService.listSchemes(
                "ka",
                -1,
                500,
                "scheme_name",
                "asc",
                "SS",
                "Scheme",
                "query",
                "completed",
                "3",
                "ACTIVE"
        );

        assertThat(page.getContent()).containsExactly(dto);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getNumber()).isEqualTo(0);
        assertThat(page.getSize()).isEqualTo(100);
    }

    @Test
    void listSchemeMappings_returnsPageOnHappyPath() {
        SchemeMappingDTO dto = SchemeMappingDTO.builder()
                .id(10L)
                .schemeId(1)
                .stateSchemeId("SS-1")
                .villageLgdCode("VLG-001")
                .subDivisionName("North")
                .build();

        when(schemeDbRepository.listSchemeMappings(
                "tenant_ka",
                "Scheme",
                1,
                2,
                "INACTIVE",
                "VLG",
                "North",
                "id",
                "desc",
                2,
                1
        )).thenReturn(List.of(dto));
        when(schemeDbRepository.countSchemeMappings(
                "tenant_ka",
                "Scheme",
                1,
                2,
                "INACTIVE",
                "VLG",
                "North"
        )).thenReturn(1L);

        PageResponseDTO<SchemeMappingDTO> page = schemeService.listSchemeMappings(
                "ka",
                2,
                0,
                "id",
                "desc",
                "Scheme",
                "1",
                "non-operative",
                "INACTIVE",
                "VLG",
                "North"
        );

        assertThat(page.getContent()).containsExactly(dto);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(1);
    }

    private MockMultipartFile csv(String fileName, String content) {
        return new MockMultipartFile(
                "file",
                fileName,
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void setJwtAuth(String email, String tenantStateCode, boolean superUser) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_state_code", tenantStateCode)
                .claim("email", email);

        setJwt(builder.build(), superUser);
    }

    private void setJwtPreferredUsernameAuth(String preferredUsername, String tenantStateCode, boolean superUser) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_state_code", tenantStateCode)
                .claim("preferred_username", preferredUsername)
                .build();
        setJwt(jwt, superUser);
    }

    private void setJwt(Jwt jwt, boolean superUser) {
        List<SimpleGrantedAuthority> authorities = superUser
                ? List.of(new SimpleGrantedAuthority("ROLE_SUPER_USER"))
                : Collections.emptyList();

        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}
