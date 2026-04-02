package org.arghyam.jalsoochak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.arghyam.jalsoochak.user.auth.UploadAuthService;
import org.arghyam.jalsoochak.user.config.TenantContext;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorUploadResponseDTO;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.UserUploadRepository;
import org.arghyam.jalsoochak.user.service.serviceImpl.PumpOperatorUploadServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PumpOperatorUploadServiceImplTest {

    @Mock
    private UploadAuthService uploadAuthService;

    @Mock
    private UserTenantRepository userTenantRepository;

    @Mock
    private UserUploadRepository userUploadRepository;

    @Mock
    private UserCommonRepository userCommonRepository;

    @Mock
    private GlificPreferredLanguageService preferredLanguageService;

    @Mock
    private UserEventPublisher userEventPublisher;

    @Mock
    private PumpOperatorUploadChunkProcessor chunkProcessor;

    @InjectMocks
    private PumpOperatorUploadServiceImpl service;

    @Captor
    private ArgumentCaptor<List<PumpOperatorUploadChunkProcessor.UploadRow>> uploadRowsCaptor;

    @AfterEach
    void cleanupTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void defaultUploadRepoStubs() {
        when(userUploadRepository.findUserIdByEmailOrPhone(anyString(), any(), any())).thenReturn(null);
    }

    @Test
    void upload_shouldSkipRow_whenPhoneBelongsToNonPumpOperator() {
        TenantContext.setSchema("tenant_ka");
        when(uploadAuthService.requireStateAdminUserId(eq("tenant_ka"), anyString())).thenReturn(10);
        when(userTenantRepository.findUserById(eq("tenant_ka"), eq(10L)))
                .thenReturn(Optional.of(new TenantUserRecord(10L, 1, "9111111111", "admin@example.com", 1L, "STATE_ADMIN", "Admin", null, null, null)));
        when(preferredLanguageService.resolvePreferredLanguageId(eq(1))).thenReturn(1);
        when(userCommonRepository.findUserTypeIdByName(eq("PUMP_OPERATOR"))).thenReturn(Optional.of(2));
        when(userCommonRepository.findUserTypeIdByName(eq("SECTION_OFFICER"))).thenReturn(Optional.of(3));
        when(userCommonRepository.findUserTypeIdByName(eq("SUB_DIVISIONAL_OFFICER"))).thenReturn(Optional.of(4));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pump-operators.csv",
                "text/csv",
                ("first_name,last_name,full_name,phone_number,person_type,state_scheme_id\n" +
                 "Ram,Kumar,Ram Kumar,9999999999,pump_operator,SS-1\n").getBytes(StandardCharsets.UTF_8)
        );

        // Service validation passes; actual skipping happens in the chunk processor.
        when(userUploadRepository.findSchemeId(eq("tenant_ka"), eq("SS-1"), eq((String) null))).thenReturn(100);
        when(chunkProcessor.processChunk(eq("tenant_ka"), eq("KA"), any(), anyMap(), anyInt(), anyInt(), anyList(), any()))
                .thenReturn(new PumpOperatorUploadChunkProcessor.ChunkResult(0, 1, 0));

        PumpOperatorUploadResponseDTO res = service.uploadPumpOperatorMappings(file, "Bearer token");
        assertThat(res.totalRows()).isEqualTo(1);
        assertThat(res.uploadedRows()).isEqualTo(0);
        assertThat(res.skippedRows()).isEqualTo(1);
    }

    @Test
    void upload_shouldInsertMappings_whenValid() {
        TenantContext.setSchema("tenant_ka");
        when(uploadAuthService.requireStateAdminUserId(eq("tenant_ka"), anyString())).thenReturn(10);
        when(userTenantRepository.findUserById(eq("tenant_ka"), eq(10L)))
                .thenReturn(Optional.of(new TenantUserRecord(10L, 1, "9111111111", "admin@example.com", 1L, "STATE_ADMIN", "Admin", null, null, null)));
        when(preferredLanguageService.resolvePreferredLanguageId(eq(1))).thenReturn(1);
        when(userCommonRepository.findUserTypeIdByName(eq("PUMP_OPERATOR"))).thenReturn(Optional.of(2));
        when(userCommonRepository.findUserTypeIdByName(eq("SECTION_OFFICER"))).thenReturn(Optional.of(3));
        when(userCommonRepository.findUserTypeIdByName(eq("SUB_DIVISIONAL_OFFICER"))).thenReturn(Optional.of(4));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pump-operators.csv",
                "text/csv",
                ("first_name,last_name,full_name,phone_number,person_type,state_scheme_id\n" +
                 "Ram,Kumar,Ram Kumar,9999999999,pump_operator,SS-1\n").getBytes(StandardCharsets.UTF_8)
        );

        when(userTenantRepository.findUserByPhone(eq("tenant_ka"), eq("9999999999")))
                .thenReturn(Optional.empty());
        when(userTenantRepository.findUserByEmail(eq("tenant_ka"), anyString()))
                .thenReturn(Optional.empty());
        when(userTenantRepository.createUser(eq("tenant_ka"), anyString(), eq(1), anyString(), anyString(), eq(2), eq("9999999999"), anyString(), eq(10L)))
                .thenReturn(55L);
        when(userUploadRepository.findSchemeId(eq("tenant_ka"), eq("SS-1"), eq((String) null))).thenReturn(100);
        when(chunkProcessor.processChunk(eq("tenant_ka"), eq("KA"), any(), anyMap(), anyInt(), anyInt(), anyList(), any()))
                .thenReturn(new PumpOperatorUploadChunkProcessor.ChunkResult(1, 0, 0));

        PumpOperatorUploadResponseDTO res = service.uploadPumpOperatorMappings(file, "Bearer token");

        assertThat(res.totalRows()).isEqualTo(1);
        assertThat(res.uploadedRows()).isEqualTo(1);
        assertThat(res.skippedRows()).isEqualTo(0);

        verify(chunkProcessor).processChunk(eq("tenant_ka"), eq("KA"), any(), anyMap(), eq(1), eq(10), uploadRowsCaptor.capture(), any());
        assertThat(uploadRowsCaptor.getValue()).hasSize(1);
    }

    @Test
    void upload_shouldOnboardPumpOperator_whenUserDoesNotExist() {
        TenantContext.setSchema("tenant_ka");
        when(uploadAuthService.requireStateAdminUserId(eq("tenant_ka"), anyString())).thenReturn(10);
        when(userTenantRepository.findUserById(eq("tenant_ka"), eq(10L)))
                .thenReturn(Optional.of(new TenantUserRecord(10L, 1, "9111111111", "admin@example.com", 1L, "STATE_ADMIN", "Admin", null, null, null)));
        when(preferredLanguageService.resolvePreferredLanguageId(eq(1))).thenReturn(1);
        when(userCommonRepository.findUserTypeIdByName(eq("PUMP_OPERATOR"))).thenReturn(Optional.of(2));
        when(userCommonRepository.findUserTypeIdByName(eq("SECTION_OFFICER"))).thenReturn(Optional.of(3));
        when(userCommonRepository.findUserTypeIdByName(eq("SUB_DIVISIONAL_OFFICER"))).thenReturn(Optional.of(4));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pump-operators.csv",
                "text/csv",
                ("first_name,last_name,full_name,phone_number,person_type,state_scheme_id\n" +
                 "Ram,Kumar,Ram Kumar,9999999999,pump_operator,SS-1\n").getBytes(StandardCharsets.UTF_8)
        );

        when(userUploadRepository.findSchemeId(eq("tenant_ka"), eq("SS-1"), eq((String) null))).thenReturn(100);
        when(chunkProcessor.processChunk(eq("tenant_ka"), eq("KA"), any(), anyMap(), anyInt(), anyInt(), anyList(), any()))
                .thenReturn(new PumpOperatorUploadChunkProcessor.ChunkResult(1, 0, 0));

        PumpOperatorUploadResponseDTO res = service.uploadPumpOperatorMappings(file, "Bearer token");

        assertThat(res.uploadedRows()).isEqualTo(1);
        verify(chunkProcessor).processChunk(eq("tenant_ka"), eq("KA"), any(), anyMap(), eq(1), eq(10), anyList(), any());
    }

    @Test
    void upload_shouldNotCreateUser_whenSchemeIsInvalid() {
        TenantContext.setSchema("tenant_ka");
        when(uploadAuthService.requireStateAdminUserId(eq("tenant_ka"), anyString())).thenReturn(10);
        when(userTenantRepository.findUserById(eq("tenant_ka"), eq(10L)))
                .thenReturn(Optional.of(new TenantUserRecord(10L, 1, "9111111111", "admin@example.com", 1L, "STATE_ADMIN", "Admin", null, null, null)));
        when(preferredLanguageService.resolvePreferredLanguageId(eq(1))).thenReturn(1);
        when(userCommonRepository.findUserTypeIdByName(eq("PUMP_OPERATOR"))).thenReturn(Optional.of(2));
        when(userCommonRepository.findUserTypeIdByName(eq("SECTION_OFFICER"))).thenReturn(Optional.of(3));
        when(userCommonRepository.findUserTypeIdByName(eq("SUB_DIVISIONAL_OFFICER"))).thenReturn(Optional.of(4));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pump-operators.csv",
                "text/csv",
                ("first_name,last_name,full_name,phone_number,person_type,state_scheme_id\n" +
                 "Ram,Kumar,Ram Kumar,9999999999,pump_operator,BAD-SS\n").getBytes(StandardCharsets.UTF_8)
        );

        when(userUploadRepository.findSchemeId(eq("tenant_ka"), eq("BAD-SS"), eq((String) null))).thenReturn(null);

        BadRequestException ex = assertThrows(
                BadRequestException.class,
                () -> service.uploadPumpOperatorMappings(file, "Bearer token")
        );
        assertThat(ex.getMessage()).containsIgnoringCase("validation failed");

        verify(chunkProcessor, never()).processChunk(anyString(), anyString(), any(), anyMap(), anyInt(), anyInt(), anyList(), any());
    }
}
