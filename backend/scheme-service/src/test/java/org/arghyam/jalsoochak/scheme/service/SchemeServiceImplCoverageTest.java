package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.exception.FileValidationException;
import org.arghyam.jalsoochak.scheme.exception.UnsupportedFileTypeException;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeServiceImplCoverageTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    SchemeUploadChunkProcessor chunkProcessor;

    @InjectMocks
    SchemeServiceImpl schemeService;

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
