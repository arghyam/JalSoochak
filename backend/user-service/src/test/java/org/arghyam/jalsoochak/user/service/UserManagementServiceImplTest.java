package org.arghyam.jalsoochak.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.config.properties.FrontendProperties;
import org.arghyam.jalsoochak.user.config.properties.InviteProperties;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.ChangePasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.InviteRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateProfileRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.event.InviteEmailEvent;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.InsufficientActiveUsersException;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UnauthorizedAccessException;
import org.arghyam.jalsoochak.user.exceptions.UserAlreadyExistsException;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
import org.arghyam.jalsoochak.user.service.serviceImpl.UserManagementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserManagementServiceImpl - Unit Tests")
class UserManagementServiceImplTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private KeycloakProvider keycloakProvider;

    @Mock
    private KeycloakClient keycloakClient;

    @Mock
    private UserCommonRepository userCommonRepository;

    @Mock
    private UserTenantRepository userTenantRepository;

    @Mock
    private UserNotificationEventPublisher userNotificationEventPublisher;

    @Mock
    private KeycloakAdminHelper keycloakAdminHelper;

    @Mock
    private InviteProperties inviteProperties;

    @Mock
    private FrontendProperties frontendProperties;

    @Mock
    private TokenService tokenService;

    @Mock
    private PiiEncryptionService pii;

    private UserManagementServiceImpl userManagementService;

    @BeforeEach
    void setUp() {
        MetadataDecryptionHelper metadataDecryptionHelper = new MetadataDecryptionHelper(new ObjectMapper(), pii);
        userManagementService = new UserManagementServiceImpl(
                keycloakProvider, keycloakClient, userCommonRepository, userTenantRepository,
                userNotificationEventPublisher, keycloakAdminHelper, inviteProperties, frontendProperties,
                tokenService, new ObjectMapper(), pii, metadataDecryptionHelper
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private AdminUserRow userRow(Long id, String uuid, String email, int tenantId, int adminLevel, AdminUserStatus status) {
        return new AdminUserRow(id, uuid, email, "91XXXXXXXXXX", tenantId, adminLevel, status, 0, null);
    }

    private AdminUserResponseDTO responseDTO(Long id, String email, String role) {
        return AdminUserResponseDTO.builder().id(id).email(email).role(role).status(AdminUserStatus.ACTIVE.name()).build();
    }

    /** JWT auth token for SUPER_USER (no tenant authority). */
    private JwtAuthenticationToken superUserAuth(String uuid) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", uuid)
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPER_USER")));
    }

    /** JWT auth token for STATE_ADMIN with a specific tenant authority. */
    private JwtAuthenticationToken stateAdminAuth(String uuid, String tenantCode) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", uuid)
                .build();
        return new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("ROLE_STATE_ADMIN"),
                new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase())
        ));
    }

    // ── getMe ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMe()")
    class GetMeTests {

        @Test
        @DisplayName("Should return profile for authenticated user")
        void getMe_success() {
            AdminUserRow row = userRow(1L, "kc-id", "user@example.com", 0, 1, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(1L, "user@example.com", "SUPER_USER");

            when(userCommonRepository.findAdminUserByUuid("kc-id")).thenReturn(Optional.of(row));
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            AdminUserResponseDTO result = userManagementService.getMe("kc-id");

            assertNotNull(result);
            assertEquals("user@example.com", result.getEmail());
            assertEquals("SUPER_USER", result.getRole());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when uuid has no DB record")
        void getMe_notFound_throwsResourceNotFound() {
            when(userCommonRepository.findAdminUserByUuid("unknown")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> userManagementService.getMe("unknown"));
        }
    }

    // ── getUserById ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getUserById()")
    class GetUserByIdTests {

        @Test
        @DisplayName("SUPER_USER: should return user DTO for valid id")
        void getUserById_success() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow row = userRow(5L, "kc-id", "admin@example.com", 1, 2, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(5L, "admin@example.com", "STATE_ADMIN");

            when(userCommonRepository.findAdminUserById(5L)).thenReturn(Optional.of(row));
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            AdminUserResponseDTO result = userManagementService.getUserById(5L, auth);

            assertEquals("admin@example.com", result.getEmail());
        }

        @Test
        @DisplayName("STATE_ADMIN: should throw ForbiddenAccessException when viewing user in another state")
        void getUserById_stateAdminCrossTenant_throwsForbidden() {
            Authentication auth = stateAdminAuth("kc-sa", "MP");
            // Target user is in tenant 2 (GJ), caller is MP
            AdminUserRow row = userRow(5L, "kc-id", "admin@example.com", 2, 2, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(5L)).thenReturn(Optional.of(row));
            when(userCommonRepository.findTenantStateCodeById(2)).thenReturn(Optional.of("GJ"));

            assertThrows(ForbiddenAccessException.class, () -> userManagementService.getUserById(5L, auth));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when id not found")
        void getUserById_notFound_throwsResourceNotFound() {
            Authentication auth = superUserAuth("kc-super");
            when(userCommonRepository.findAdminUserById(99L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> userManagementService.getUserById(99L, auth));
        }
    }

    // ── listSuperUsers ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listSuperUsers()")
    class ListSuperUsersTests {

        @Test
        @DisplayName("Should return paginated super users with no status filter")
        void listSuperUsers_success() {
            AdminUserRow row = userRow(1L, "kc-1", "su@example.com", 0, 1, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(1L, "su@example.com", "SUPER_USER");

            when(userCommonRepository.listSuperUsers(null, 0, 20)).thenReturn(List.of(row));
            when(userCommonRepository.countSuperUsers(null)).thenReturn(1L);
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listSuperUsers(null, 0, 20);

            assertEquals(1, result.getContent().size());
            assertEquals(1L, result.getTotalElements());
            assertEquals(1, result.getTotalPages());
        }

        @Test
        @DisplayName("Should pass ACTIVE status filter through to repository")
        void listSuperUsers_withStatusFilter_passesStatusToRepo() {
            AdminUserRow row = userRow(1L, "kc-1", "su@example.com", 0, 1, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(1L, "su@example.com", "SUPER_USER");

            when(userCommonRepository.listSuperUsers(AdminUserStatus.ACTIVE, 0, 20)).thenReturn(List.of(row));
            when(userCommonRepository.countSuperUsers(AdminUserStatus.ACTIVE)).thenReturn(1L);
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listSuperUsers(AdminUserStatus.ACTIVE, 0, 20);

            assertEquals(1, result.getContent().size());
            assertEquals(1L, result.getTotalElements());
            verify(userCommonRepository).countSuperUsers(AdminUserStatus.ACTIVE);
        }
    }

    // ── listStateAdmins ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listStateAdmins()")
    class ListStateAdminsTests {

        @Test
        @DisplayName("SUPER_USER with tenantCode should filter by that tenant")
        void listStateAdmins_superUser_withTenantCode_filtersResults() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow row = userRow(2L, "kc-2", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(2L, "sa@example.com", "STATE_ADMIN");

            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.listStateAdminsByTenant(1, null, null, 0, 20)).thenReturn(List.of(row));
            when(userCommonRepository.countStateAdminsByTenant(1, null, null)).thenReturn(1L);
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listStateAdmins("MP", null, null, auth, 0, 20);

            assertEquals(1, result.getContent().size());
        }

        @Test
        @DisplayName("STATE_ADMIN should only see admins in their own tenant")
        void listStateAdmins_stateAdmin_usesOwnTenant() {
            Authentication auth = stateAdminAuth("kc-sa", "MP");
            AdminUserRow row = userRow(2L, "kc-2", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(2L, "sa@example.com", "STATE_ADMIN");

            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.listStateAdminsByTenant(1, null, null, 0, 20)).thenReturn(List.of(row));
            when(userCommonRepository.countStateAdminsByTenant(1, null, null)).thenReturn(1L);
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listStateAdmins(null, null, null, auth, 0, 20);

            assertEquals(1, result.getContent().size());
        }

        @Test
        @DisplayName("Should pass PENDING status filter through to repository")
        void listStateAdmins_withStatusFilter_passesStatusToRepo() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow row = userRow(3L, "kc-3", "pending@example.com", 1, 2, AdminUserStatus.PENDING);
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(3L).email("pending@example.com").role("STATE_ADMIN")
                    .status(AdminUserStatus.PENDING.name()).build();

            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.listStateAdminsByTenant(1, AdminUserStatus.PENDING, null, 0, 20)).thenReturn(List.of(row));
            when(userCommonRepository.countStateAdminsByTenant(1, AdminUserStatus.PENDING, null)).thenReturn(1L);
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listStateAdmins("MP", AdminUserStatus.PENDING, null, auth, 0, 20);

            assertEquals(1, result.getContent().size());
            assertEquals(AdminUserStatus.PENDING.name(), result.getContent().get(0).getStatus());
        }

        @Test
        @DisplayName("Name filter should resolve matching UUIDs from tenant user table and return filtered page")
        void listStateAdmins_withNameFilter_matchFound_returnsFilteredPage() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow row = userRow(2L, "kc-2", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(2L, "sa@example.com", "STATE_ADMIN");

            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(pii.hmac("jane doe")).thenReturn("name-hash-hex");
            when(userTenantRepository.findUuidsByTitleHash("tenant_mp", "name-hash-hex")).thenReturn(List.of("kc-2"));
            when(userCommonRepository.findPendingAdminUuidsByNameHash(eq("name-hash-hex"), eq(1))).thenReturn(java.util.Set.of());
            when(userCommonRepository.listStateAdminsByTenant(anyInt(), any(), any(), anyLong(), anyInt())).thenReturn(List.of(row));
            when(userCommonRepository.countStateAdminsByTenant(anyInt(), any(), any())).thenReturn(1L);
            when(keycloakAdminHelper.buildAdminUserResponse(row)).thenReturn(dto);

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listStateAdmins("MP", null, "Jane Doe", auth, 0, 20);

            assertEquals(1, result.getContent().size());
        }

        @Test
        @DisplayName("Name filter with no match should return empty page without calling list repository method")
        void listStateAdmins_withNameFilter_noMatch_returnsEmptyPage() {
            Authentication auth = superUserAuth("kc-super");

            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(pii.hmac("nobody")).thenReturn("no-match-hash");
            when(userTenantRepository.findUuidsByTitleHash("tenant_mp", "no-match-hash")).thenReturn(List.of());
            when(userCommonRepository.findPendingAdminUuidsByNameHash(eq("no-match-hash"), eq(1))).thenReturn(java.util.Set.of());

            PageResponseDTO<AdminUserResponseDTO> result = userManagementService.listStateAdmins("MP", null, "Nobody", auth, 0, 20);

            assertEquals(0, result.getContent().size());
            assertEquals(0L, result.getTotalElements());
        }
    }

    // ── inviteUser ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("inviteUser()")
    class InviteUserTests {

        @Test
        @DisplayName("SUPER_USER should invite SUPER_USER successfully")
        void inviteUser_superUserInvitesSuperUser_success() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            // findAdminUserByEmail returns empty → user does not yet exist (fresh invite)
            when(userCommonRepository.findAdminUserByEmail("new@example.com")).thenReturn(Optional.empty());
            // adminLevelId lookup for the invited role
            when(userCommonRepository.findUserTypeIdByName("SUPER_USER")).thenReturn(Optional.of(1));
            when(tokenService.generateRawToken()).thenReturn("raw-invite-token");
            when(tokenService.hash("raw-invite-token")).thenReturn("invite-hash");
            when(inviteProperties.expiryHours()).thenReturn(24);
            doNothing().when(userCommonRepository).insertToken(
                    eq("new@example.com"), eq("invite-hash"), eq("INVITE"), anyString(), any(), eq(1));
            when(frontendProperties.baseUrl()).thenReturn("http://localhost:3000");
            when(frontendProperties.invitePath()).thenReturn("/invite");

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("new@example.com");
            req.setRole("SUPER_USER");
            req.setFirstName("New");
            req.setLastName("User");
            req.setPhoneNumber("9112345678");

            userManagementService.inviteUser(req, auth);

            verify(userCommonRepository).insertToken(
                    eq("new@example.com"), eq("invite-hash"), eq("INVITE"), anyString(), any(), eq(1));
            verify(userNotificationEventPublisher).publishInviteEmailAfterCommit(any(InviteEmailEvent.class));
        }

        @Test
        @DisplayName("Should throw UnauthorizedAccessException when caller not in DB")
        void inviteUser_callerNotInDB_throwsUnauthorized() {
            Authentication auth = superUserAuth("kc-unknown");
            when(userCommonRepository.findAdminUserByUuid("kc-unknown")).thenReturn(Optional.empty());

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("new@example.com");
            req.setRole("SUPER_USER");

            assertThrows(UnauthorizedAccessException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("STATE_ADMIN inviting across state boundary should throw ForbiddenAccessException")
        void inviteUser_stateAdminCrossState_throwsForbidden() {
            Authentication auth = stateAdminAuth("kc-sa", "MP");
            AdminUserRow callerRow = userRow(2L, "kc-sa", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-sa")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(2)).thenReturn(Optional.of("STATE_ADMIN"));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("other@example.com");
            req.setRole("STATE_ADMIN");
            req.setTenantCode("GJ"); // different state

            assertThrows(ForbiddenAccessException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("Should throw UserAlreadyExistsException when email is already active")
        void inviteUser_emailAlreadyActive_throwsConflict() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            AdminUserRow existingUser = userRow(3L, "kc-dup", "dup@example.com", 0, 1, AdminUserStatus.ACTIVE);
            when(userCommonRepository.findAdminUserByEmail("dup@example.com")).thenReturn(Optional.of(existingUser));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("dup@example.com");
            req.setRole("SUPER_USER");

            assertThrows(UserAlreadyExistsException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("Should throw UserAlreadyExistsException when email belongs to a deactivated user")
        void inviteUser_emailAlreadyInactive_throwsConflict() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            AdminUserRow existingUser = userRow(3L, "kc-dup", "dup@example.com", 0, 1, AdminUserStatus.INACTIVE);
            when(userCommonRepository.findAdminUserByEmail("dup@example.com")).thenReturn(Optional.of(existingUser));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("dup@example.com");
            req.setRole("SUPER_USER");

            assertThrows(UserAlreadyExistsException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("Should throw BadRequestException when email already has a pending invitation")
        void inviteUser_emailAlreadyPending_throwsBadRequest() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            AdminUserRow pendingUser = userRow(5L, "placeholder-uuid", "pending@example.com", 0, 1, AdminUserStatus.PENDING);
            when(userCommonRepository.findAdminUserByEmail("pending@example.com")).thenReturn(Optional.of(pendingUser));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("pending@example.com");
            req.setRole("SUPER_USER");

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> userManagementService.inviteUser(req, auth));
            assertTrue(ex.getMessage().toLowerCase().contains("reinvite"),
                    "Error message should direct the caller to the reinvite endpoint");
        }

        @Test
        @DisplayName("Should throw BadRequestException when STATE_ADMIN role has no tenantCode")
        void inviteUser_stateAdminWithNoTenantCode_throwsBadRequest() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("new@example.com");
            req.setRole("STATE_ADMIN");
            // tenantCode intentionally omitted

            assertThrows(BadRequestException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenantCode does not exist")
        void inviteUser_tenantNotFound_throwsResourceNotFound() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.existsTenantByStateCode("XX")).thenReturn(false);

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("new@example.com");
            req.setRole("STATE_ADMIN");
            req.setTenantCode("XX");

            assertThrows(ResourceNotFoundException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("Should throw UserAlreadyExistsException when concurrent insert hits duplicate key for active user")
        void inviteUser_concurrentDuplicate_activeUser_throwsUserAlreadyExists() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.findUserTypeIdByName("SUPER_USER")).thenReturn(Optional.of(1));
            doThrow(new DuplicateKeyException("duplicate key"))
                    .when(userCommonRepository).createAdminUserPending(eq("race@example.com"), any(), any(), any(), any());
            // Pre-check passes (empty), then re-query after catch finds the concurrently inserted active user
            AdminUserRow activeUser = userRow(9L, "kc-race", "race@example.com", 0, 1, AdminUserStatus.ACTIVE);
            when(userCommonRepository.findAdminUserByEmail("race@example.com"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(activeUser));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("race@example.com");
            req.setRole("SUPER_USER");

            assertThrows(UserAlreadyExistsException.class, () -> userManagementService.inviteUser(req, auth));
        }

        @Test
        @DisplayName("Should throw BadRequestException when concurrent insert hits duplicate key for pending user")
        void inviteUser_concurrentDuplicate_pendingUser_throwsBadRequest() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.findUserTypeIdByName("SUPER_USER")).thenReturn(Optional.of(1));
            doThrow(new DuplicateKeyException("duplicate key"))
                    .when(userCommonRepository).createAdminUserPending(eq("race@example.com"), any(), any(), any(), any());
            // Re-query after catch finds the concurrently inserted pending user
            AdminUserRow pendingUser = userRow(10L, "placeholder", "race@example.com", 0, 1, AdminUserStatus.PENDING);
            when(userCommonRepository.findAdminUserByEmail("race@example.com"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pendingUser));

            InviteRequestDTO req = new InviteRequestDTO();
            req.setEmail("race@example.com");
            req.setRole("SUPER_USER");

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> userManagementService.inviteUser(req, auth));
            assertTrue(ex.getMessage().toLowerCase().contains("reinvite"));
        }

    }

    // ── deactivateUser ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivateUser()")
    class DeactivateUserTests {

        @Test
        @DisplayName("Should throw InsufficientActiveUsersException when deactivating last super user")
        void deactivateUser_lastSuperUser_throwsInsufficient() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow target = userRow(1L, "kc-target", "su@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(1L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.countActiveSuperUsers()).thenReturn(1);

            assertThrows(InsufficientActiveUsersException.class,
                    () -> userManagementService.deactivateUser(1L, auth));
        }

        @Test
        @DisplayName("Should throw InsufficientActiveUsersException when deactivating last state admin in tenant")
        void deactivateUser_lastStateAdmin_throwsInsufficient() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow target = userRow(2L, "kc-target", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(2L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findUserTypeNameById(2)).thenReturn(Optional.of("STATE_ADMIN"));
            when(userCommonRepository.countActiveStateAdminsForTenant(1)).thenReturn(1);

            assertThrows(InsufficientActiveUsersException.class,
                    () -> userManagementService.deactivateUser(2L, auth));
        }

        @Test
        @DisplayName("STATE_ADMIN deactivating user in another state should throw ForbiddenAccessException")
        void deactivateUser_crossTenant_throwsForbidden() {
            Authentication auth = stateAdminAuth("kc-sa", "MP");
            AdminUserRow target = userRow(3L, "kc-target", "other@example.com", 2, 2, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(3L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findUserTypeNameById(2)).thenReturn(Optional.of("STATE_ADMIN"));
            // caller is MP, target is in tenant 2 → different state
            when(userCommonRepository.findTenantStateCodeById(2)).thenReturn(Optional.of("GJ"));

            assertThrows(ForbiddenAccessException.class,
                    () -> userManagementService.deactivateUser(3L, auth));
        }

        @Test
        @DisplayName("Should deactivate user and disable in Keycloak on success")
        void deactivateUser_success() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow target = userRow(4L, "kc-target", "su2@example.com", 0, 1, AdminUserStatus.ACTIVE);
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(4L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.countActiveSuperUsers()).thenReturn(3);
            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            doNothing().when(userCommonRepository).deactivateAdminUser(4L, 1L);

            userManagementService.deactivateUser(4L, auth);

            verify(userCommonRepository).deactivateAdminUser(4L, 1L);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when target user not found")
        void deactivateUser_targetNotFound_throwsResourceNotFound() {
            Authentication auth = superUserAuth("kc-super");
            when(userCommonRepository.findAdminUserById(99L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> userManagementService.deactivateUser(99L, auth));
        }
    }

    // ── activateUser ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("activateUser()")
    class ActivateUserTests {

        @Test
        @DisplayName("Should activate user and enable in Keycloak on success")
        void activateUser_success() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow target = userRow(5L, "kc-target", "deactivated@example.com", 0, 1, AdminUserStatus.INACTIVE);
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(5L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            doNothing().when(userCommonRepository).activateAdminUser(5L, 1L);

            userManagementService.activateUser(5L, auth);

            verify(userCommonRepository).activateAdminUser(5L, 1L);
        }

        @Test
        @DisplayName("Should throw UnauthorizedAccessException when caller not in DB")
        void activateUser_callerNotInDB_throwsUnauthorized() {
            Authentication auth = superUserAuth("kc-unknown");
            AdminUserRow target = userRow(5L, "kc-target", "deactivated@example.com", 0, 1, AdminUserStatus.INACTIVE);

            when(userCommonRepository.findAdminUserById(5L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findAdminUserByUuid("kc-unknown")).thenReturn(Optional.empty());

            assertThrows(UnauthorizedAccessException.class,
                    () -> userManagementService.activateUser(5L, auth));
        }

        @Test
        @DisplayName("STATE_ADMIN activating user in another state should throw UnauthorizedAccessException")
        void activateUser_stateAdminCrossTenant_throwsUnauthorized() {
            Authentication auth = stateAdminAuth("kc-sa", "MP");
            // Target user is in tenant 2 (GJ), caller is in MP
            AdminUserRow target = userRow(6L, "kc-target", "other@example.com", 2, 2, AdminUserStatus.INACTIVE);
            AdminUserRow callerRow = userRow(2L, "kc-sa", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(6L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findAdminUserByUuid("kc-sa")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findTenantStateCodeById(2)).thenReturn(Optional.of("GJ"));

            assertThrows(UnauthorizedAccessException.class,
                    () -> userManagementService.activateUser(6L, auth));
        }
    }

    // ── changePassword ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("changePassword()")
    class ChangePasswordTests {

        @Test
        @DisplayName("Should throw InvalidCredentialsException when current password is wrong")
        void changePassword_wrongCurrentPassword_throwsInvalidCredentials() {
            AdminUserRow user = userRow(1L, "kc-id", "user@example.com", 0, 1, AdminUserStatus.ACTIVE);
            when(userCommonRepository.findAdminUserByUuid("kc-id")).thenReturn(Optional.of(user));
            when(keycloakClient.obtainToken("user@example.com", "wrongpass"))
                    .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

            ChangePasswordRequestDTO req = new ChangePasswordRequestDTO();
            req.setCurrentPassword("wrongpass");
            req.setNewPassword("NewPass@123");

            assertThrows(InvalidCredentialsException.class,
                    () -> userManagementService.changePassword("kc-id", req));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user not found")
        void changePassword_userNotFound_throwsResourceNotFound() {
            when(userCommonRepository.findAdminUserByUuid("kc-missing")).thenReturn(Optional.empty());

            ChangePasswordRequestDTO req = new ChangePasswordRequestDTO();
            req.setCurrentPassword("pass");
            req.setNewPassword("NewPass@123");

            assertThrows(ResourceNotFoundException.class,
                    () -> userManagementService.changePassword("kc-missing", req));
        }
    }

    // ── updateMe ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateMe()")
    class UpdateMeTests {

        @Test
        @DisplayName("SUPER_USER: should update Keycloak only (no tenant schema call)")
        void updateMe_superUser_updatesKeycloakOnly() {
            AdminUserRow user = userRow(1L, "kc-id", "user@example.com", 0, 1, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(1L, "user@example.com", "SUPER_USER");

            when(userCommonRepository.findAdminUserByUuid("kc-id")).thenReturn(Optional.of(user));
            when(keycloakProvider.getRealm()).thenReturn("test-realm");
            UserRepresentation rep = new UserRepresentation();
            rep.setFirstName("Old");
            rep.setLastName("Name");
            when(keycloakProvider.getAdminInstance().realm("test-realm").users().get("kc-id").toRepresentation())
                    .thenReturn(rep);
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(keycloakAdminHelper.buildAdminUserResponse(any())).thenReturn(dto);

            UpdateProfileRequestDTO req = new UpdateProfileRequestDTO();
            req.setFirstName("New");

            AdminUserResponseDTO result = userManagementService.updateMe("kc-id", req);

            assertNotNull(result);
            verify(userTenantRepository, org.mockito.Mockito.never()).updateUserProfile(any(), any(), any(), any());
        }

        @Test
        @DisplayName("STATE_ADMIN: should sync tenant schema when name changes")
        void updateMe_stateAdmin_updatesTenantSchema() {
            AdminUserRow user = userRow(2L, "kc-sa", "sa@example.com", 1, 2, AdminUserStatus.ACTIVE);
            AdminUserResponseDTO dto = responseDTO(2L, "sa@example.com", "STATE_ADMIN");

            when(userCommonRepository.findAdminUserByUuid("kc-sa")).thenReturn(Optional.of(user));
            when(keycloakProvider.getRealm()).thenReturn("test-realm");
            UserRepresentation rep = new UserRepresentation();
            rep.setFirstName("State");
            rep.setLastName("Admin");
            when(keycloakProvider.getAdminInstance().realm("test-realm").users().get("kc-sa").toRepresentation())
                    .thenReturn(rep);
            when(userCommonRepository.findUserTypeNameById(2)).thenReturn(Optional.of("STATE_ADMIN"));
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));
            doNothing().when(userTenantRepository).updateUserProfile(anyString(), any(), anyString(), any());
            when(keycloakAdminHelper.buildAdminUserResponse(any())).thenReturn(dto);

            UpdateProfileRequestDTO req = new UpdateProfileRequestDTO();
            req.setFirstName("Updated");

            userManagementService.updateMe("kc-sa", req);

            verify(userTenantRepository).updateUserProfile(eq("tenant_mp"), any(), anyString(), any());
        }
    }

    // ── reinviteUser ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reinviteUser()")
    class ReinviteUserTests {

        @Test
        @DisplayName("Should resend invite token and email for a PENDING user")
        void reinviteUser_success() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow target = userRow(7L, "pending-uuid", "pending@example.com", 0, 1, AdminUserStatus.PENDING);
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            AdminUserTokenRow existingToken = new AdminUserTokenRow(1L, "pending@example.com", "old-hash",
                    "INVITE", "{\"role\":\"SUPER_USER\",\"firstName\":\"New\",\"lastName\":\"User\"}",
                    Instant.now().plus(24, ChronoUnit.HOURS), null, null, Instant.now());

            when(userCommonRepository.findAdminUserById(7L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.findInviteTokenByEmail("pending@example.com")).thenReturn(Optional.of(existingToken));
            when(tokenService.generateRawToken()).thenReturn("new-raw-token");
            when(tokenService.hash("new-raw-token")).thenReturn("new-hash");
            when(inviteProperties.expiryHours()).thenReturn(24);
            doNothing().when(userCommonRepository).insertToken(
                    eq("pending@example.com"), eq("new-hash"), eq("INVITE"), anyString(), any(), eq(1));
            when(frontendProperties.baseUrl()).thenReturn("http://localhost:3000");
            when(frontendProperties.invitePath()).thenReturn("/invite");

            userManagementService.reinviteUser(7L, auth);

            verify(userCommonRepository).insertToken(
                    eq("pending@example.com"), eq("new-hash"), eq("INVITE"), anyString(), any(), eq(1));
            verify(userNotificationEventPublisher).publishInviteEmailAfterCommit(any(InviteEmailEvent.class));
        }

        @Test
        @DisplayName("Should carry over names from an expired invite token when reinviting")
        void reinviteUser_expiredToken_namesCarriedOver() {
            Authentication auth = superUserAuth("kc-super");
            AdminUserRow target = userRow(7L, "pending-uuid", "pending@example.com", 0, 1, AdminUserStatus.PENDING);
            AdminUserRow callerRow = userRow(1L, "kc-super", "super@example.com", 0, 1, AdminUserStatus.ACTIVE);

            // Token is expired (in the past) but unconsumed — names stored as encrypted values
            String encFirstName = "Rklyc3ROYW1lQ2lwaGVyVGV4dA=="; // placeholder for encrypted "Jane"
            String encLastName  = "TGFzdE5hbWVDaXBoZXJUZXh0";     // placeholder for encrypted "Doe"
            AdminUserTokenRow expiredToken = new AdminUserTokenRow(1L, "pending@example.com", "old-hash",
                    "INVITE", "{\"role\":\"SUPER_USER\",\"firstName\":\"" + encFirstName + "\",\"lastName\":\"" + encLastName + "\"}",
                    Instant.now().minus(2, ChronoUnit.HOURS), null, null, Instant.now().minus(26, ChronoUnit.HOURS));
            when(pii.safeDecrypt(encFirstName)).thenReturn("Jane");
            when(pii.safeDecrypt(encLastName)).thenReturn("Doe");

            when(userCommonRepository.findAdminUserById(7L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findAdminUserByUuid("kc-super")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(1)).thenReturn(Optional.of("SUPER_USER"));
            when(userCommonRepository.findInviteTokenByEmail("pending@example.com")).thenReturn(Optional.of(expiredToken));
            when(tokenService.generateRawToken()).thenReturn("new-raw-token");
            when(tokenService.hash("new-raw-token")).thenReturn("new-hash");
            when(inviteProperties.expiryHours()).thenReturn(24);
            doNothing().when(userCommonRepository).insertToken(
                    eq("pending@example.com"), eq("new-hash"), eq("INVITE"), anyString(), any(), eq(1));
            when(frontendProperties.baseUrl()).thenReturn("http://localhost:3000");
            when(frontendProperties.invitePath()).thenReturn("/invite");

            userManagementService.reinviteUser(7L, auth);

            // Verify that carried-over names reach the email event
            ArgumentCaptor<InviteEmailEvent> eventCaptor = ArgumentCaptor.forClass(InviteEmailEvent.class);
            verify(userNotificationEventPublisher).publishInviteEmailAfterCommit(eventCaptor.capture());
            assertEquals("Jane Doe", eventCaptor.getValue().getName());

            // Verify that insertToken was called with metadata containing firstName and lastName
            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(userCommonRepository).insertToken(
                    eq("pending@example.com"), eq("new-hash"), eq("INVITE"),
                    metadataCaptor.capture(), any(), eq(1));
            String storedMetadata = metadataCaptor.getValue();
            assertTrue(storedMetadata.contains("\"firstName\""), "metadata should contain firstName key");
            assertTrue(storedMetadata.contains("\"lastName\""), "metadata should contain lastName key");
            assertTrue(storedMetadata.contains("\"nameHash\""), "metadata should contain nameHash key for name search");
        }

        @Test
        @DisplayName("Should throw BadRequestException when user has already activated their account")
        void reinviteUser_alreadyActivated_throwsBadRequest() {
            Authentication auth = superUserAuth("kc-super");
            // status=1 means already active (not pending)
            AdminUserRow target = userRow(8L, "kc-active", "active@example.com", 0, 1, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(8L)).thenReturn(Optional.of(target));

            assertThrows(BadRequestException.class, () -> userManagementService.reinviteUser(8L, auth));
        }

        @Test
        @DisplayName("Should throw ForbiddenAccessException when STATE_ADMIN reinvites across state boundary")
        void reinviteUser_stateAdminCrossState_throwsForbidden() {
            Authentication auth = stateAdminAuth("kc-sa", "MP");
            // Target is in tenant 2 (GJ), caller is in MP
            AdminUserRow target = userRow(9L, "kc-pending", "pending@gj.com", 2, 2, AdminUserStatus.PENDING);
            AdminUserRow callerRow = userRow(2L, "kc-sa", "sa@mp.com", 1, 2, AdminUserStatus.ACTIVE);

            when(userCommonRepository.findAdminUserById(9L)).thenReturn(Optional.of(target));
            when(userCommonRepository.findAdminUserByUuid("kc-sa")).thenReturn(Optional.of(callerRow));
            when(userCommonRepository.findUserTypeNameById(2)).thenReturn(Optional.of("STATE_ADMIN"));
            when(userCommonRepository.findTenantStateCodeById(2)).thenReturn(Optional.of("GJ"));

            assertThrows(ForbiddenAccessException.class, () -> userManagementService.reinviteUser(9L, auth));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when target user does not exist")
        void reinviteUser_targetNotFound_throwsResourceNotFound() {
            Authentication auth = superUserAuth("kc-super");
            when(userCommonRepository.findAdminUserById(99L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> userManagementService.reinviteUser(99L, auth));
        }
    }
}
