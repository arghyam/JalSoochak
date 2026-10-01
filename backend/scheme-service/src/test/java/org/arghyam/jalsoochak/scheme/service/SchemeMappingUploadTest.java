package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.exception.FileValidationException;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.repository.SchemeLgdMappingCreateRecord;
import org.arghyam.jalsoochak.scheme.repository.SchemeSubdivisionMappingCreateRecord;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeMappingUploadTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    SchemeUploadChunkProcessor chunkProcessor;

    @Mock
    org.arghyam.jalsoochak.scheme.kafka.SchemeDimensionEvents schemeDimensionEvents;

    @Mock
    org.arghyam.jalsoochak.scheme.kafka.KafkaProducer kafkaProducer;

    @InjectMocks
    SchemeServiceImpl schemeService;

    @Captor
    ArgumentCaptor<List<SchemeLgdMappingCreateRecord>> rowsCaptor;

    @Captor
    ArgumentCaptor<List<SchemeSubdivisionMappingCreateRecord>> deptRowsCaptor;

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

        when(schemeDbRepository.findUserIdByEmail("tenant_ka", "admin@example.com")).thenReturn(10);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadSchemeMappings_insertsLgdMappings_andPopulatesCreatedByFromActor() {
        String csv = """
                state_scheme_id,village_lgd_code,sub_division_name
                SS-1,VLG-001,Bengaluru North
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scheme-mappings.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("ss-1", 1));
        when(schemeDbRepository.findLgdIdsByCodes(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("vlg-001", 501));
        when(schemeDbRepository.findDepartmentIdsByTitles(eq("tenant_ka"), anyList()))
                .thenReturn(Map.of("bengaluru north", 1001));

        SchemeUploadResponseDTO res = schemeService.uploadSchemeMappings(file);

        assertThat(res.getMessage()).isEqualTo("Scheme mappings uploaded successfully");
        assertThat(res.getTotalRows()).isEqualTo(1);
        assertThat(res.getUploadedRows()).isEqualTo(1);

        verify(chunkProcessor).insertMappingsChunk(eq("tenant_ka"), rowsCaptor.capture(), deptRowsCaptor.capture());
        List<SchemeLgdMappingCreateRecord> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().schemeId()).isEqualTo(1);
        assertThat(rows.getFirst().parentLgdId()).isEqualTo(501);
        assertThat(rows.getFirst().parentLgdLevel()).isEqualTo(6);
        assertThat(rows.getFirst().createdBy()).isEqualTo(10);
        assertThat(rows.getFirst().updatedBy()).isEqualTo(10);

        List<SchemeSubdivisionMappingCreateRecord> deptRows = deptRowsCaptor.getValue();
        assertThat(deptRows).hasSize(1);
        assertThat(deptRows.getFirst().schemeId()).isEqualTo(1);
        assertThat(deptRows.getFirst().parentDepartmentId()).isEqualTo(1001);
        assertThat(deptRows.getFirst().parentDepartmentLevel()).isEqualTo("sub_division");
        assertThat(deptRows.getFirst().createdBy()).isEqualTo(10);
        assertThat(deptRows.getFirst().updatedBy()).isEqualTo(10);

        // The re-mapped scheme's whole analytics row set is rebuilt from what was just written.
        verify(schemeDimensionEvents).build(eq("tenant_ka"), org.mockito.ArgumentMatchers.anyInt(), eq(List.of(1)));
    }

    @Test
    void uploadSchemeMappings_rejectsInvalidHeaders() {
        String csv = """
                scheme_id,parent_lgd_id,parent_lgd_level
                1,501,5
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scheme-mappings.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> schemeService.uploadSchemeMappings(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Invalid headers");

        verifyNoInteractions(chunkProcessor);
    }

    @Test
    void uploadSchemeMappings_rejectsUnknownSchemeOrBoundaryValues() {
        String csv = """
                state_scheme_id,village_lgd_code,sub_division_name
                UNKNOWN_SCHEME,UNKNOWN_VILLAGE,UNKNOWN_SUBDIV
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scheme-mappings.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        when(schemeDbRepository.findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList())).thenReturn(Map.of());
        when(schemeDbRepository.findLgdIdsByCodes(eq("tenant_ka"), anyList())).thenReturn(Map.of());
        when(schemeDbRepository.findDepartmentIdsByTitles(eq("tenant_ka"), anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> schemeService.uploadSchemeMappings(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Validation failed for uploaded file")
                .satisfies(ex -> {
                    FileValidationException fve = (FileValidationException) ex;
                    assertThat(fve.getErrors())
                            .anySatisfy(err -> {
                                assertThat(err.getField()).isEqualTo("state_scheme_id");
                                assertThat(err.getMessage()).contains("does not exist");
                            });
                });

        verify(schemeDbRepository).findSchemeIdsByStateSchemeIds(eq("tenant_ka"), anyList());
    }
}
