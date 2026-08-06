package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateStaffRoleRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.repository.DataVersionRepository;
import org.arghyam.jalsoochak.user.repository.TenantStaffRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.serviceImpl.TenantStaffServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantStaffServiceImpl")
class TenantStaffServiceImplTest {

    @Mock private TenantStaffRepository tenantStaffRepository;
    @Mock private UserTenantRepository userTenantRepository;
    @Mock private UserCommonRepository userCommonRepository;
    @Mock private KeycloakProvider keycloakProvider;
    @Mock private UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    @Mock private StaffKeycloakService staffKeycloakService;
    @Mock private DataVersionRepository dataVersionRepository;

    private TenantStaffServiceImpl service;

    private static final TenantUserRecord SECTION_OFFICER = new TenantUserRecord(
            10L, 1, "919876543210", "officer@test.com", 3L, "SECTION_OFFICER",
            "Officer", "kc-uuid", TenantUserStatus.ACTIVE.code, null);

    @BeforeEach
    void setUp() {
        service = new TenantStaffServiceImpl(
                tenantStaffRepository, userTenantRepository, userCommonRepository,
                keycloakProvider, userAnalyticsEventPublisher, staffKeycloakService,
                dataVersionRepository);
        ReflectionTestUtils.setField(service, "allowedUpdateRoles",
                List.of("SECTION_OFFICER", "SUB_DIVISIONAL_OFFICER"));
    }

    // --- listStaff ---

    @Nested
    @DisplayName("listStaff")
    class ListStaff {

        @Test
        @DisplayName("delegates to repository with resolved schema and default pagination")
        void delegatesToRepository() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(
                    List.of(TenantStaffResponseDTO.builder().id(1L).build()), 1L);
            when(tenantStaffRepository.listStaffPage(
                    eq("tenant_mp"), any(), any(), any(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            PageResponseDTO<TenantStaffResponseDTO> result =
                    service.listStaff("mp", 0, 20, "id", "desc", null, null, null);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getTotalElements()).isEqualTo(1L);
        }

        @Test
        @DisplayName("clamps limit below 1 to 1")
        void clampsLimitBelow1() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(any(), any(), any(), any(), any(), any(), eq(0), eq(1)))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 0, "id", "desc", null, null, null);
            verify(tenantStaffRepository).listStaffPage(any(), any(), any(), any(), any(), any(), eq(0), eq(1));
        }

        @Test
        @DisplayName("clamps limit above 100 to 100")
        void clampsLimitAbove100() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(any(), any(), any(), any(), any(), any(), eq(0), eq(100)))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 500, "id", "desc", null, null, null);
            verify(tenantStaffRepository).listStaffPage(any(), any(), any(), any(), any(), any(), eq(0), eq(100));
        }

        @Test
        @DisplayName("normalizes comma-separated role strings into individual entries")
        void normalizesCommaDelimitedRoles() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(
                    eq("tenant_mp"),
                    eq(List.of("section_officer", "sub_divisional_officer")),
                    any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 20, "id", "desc",
                    List.of("SECTION_OFFICER,SUB_DIVISIONAL_OFFICER"), null, null);

            verify(tenantStaffRepository).listStaffPage(
                    eq("tenant_mp"),
                    eq(List.of("section_officer", "sub_divisional_officer")),
                    any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("parses ACTIVE status string to integer 1")
        void parsesActiveStatus() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(
                    any(), any(), eq(1), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 20, "id", "desc", null, "ACTIVE", null);

            verify(tenantStaffRepository).listStaffPage(
                    any(), any(), eq(1), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("parses INACTIVE status string to integer 0")
        void parsesInactiveStatus() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(
                    any(), any(), eq(0), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 20, "id", "desc", null, "INACTIVE", null);

            verify(tenantStaffRepository).listStaffPage(
                    any(), any(), eq(0), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unknown status string")
        void throwsForUnknownStatus() {
            assertThatThrownBy(() ->
                    service.listStaff("mp", 0, 20, "id", "desc", null, "UNKNOWN", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown status");
        }

        @Test
        @DisplayName("parses numeric status string directly to integer")
        void parsesNumericStatus() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(
                    any(), any(), eq(3), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 20, "id", "desc", null, "3", null);
            verify(tenantStaffRepository).listStaffPage(any(), any(), eq(3), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("null status maps to null integer")
        void nullStatusMapsToNull() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(
                    any(), any(), isNull(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 20, "id", "desc", null, null, null);
            verify(tenantStaffRepository).listStaffPage(any(), any(), isNull(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("skips null and blank entries in role list normalization")
        void skipsNullAndBlankRoleEntries() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(
                    eq("tenant_mp"),
                    eq(List.of("section_officer")),
                    any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", 0, 20, "id", "desc",
                    java.util.Arrays.asList(null, "  ", "SECTION_OFFICER"), null, null);

            verify(tenantStaffRepository).listStaffPage(
                    eq("tenant_mp"),
                    eq(List.of("section_officer")),
                    any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("negative page is clamped to 0")
        void negativePage() {
            TenantStaffRepository.StaffPage staffPage = new TenantStaffRepository.StaffPage(List.of(), 0L);
            when(tenantStaffRepository.listStaffPage(any(), any(), any(), any(), any(), any(), eq(0), anyInt()))
                    .thenReturn(staffPage);

            service.listStaff("mp", -5, 10, "id", "desc", null, null, null);
            verify(tenantStaffRepository).listStaffPage(any(), any(), any(), any(), any(), any(), eq(0), anyInt());
        }
    }

    // --- countStaffByRole ---

    @Nested
    @DisplayName("countStaffByRole")
    class CountStaffByRole {

        @Test
        @DisplayName("delegates to repository")
        void delegatesToRepository() {
            List<RoleCountDTO> counts = List.of(new RoleCountDTO("SECTION_OFFICER", 5));
            when(tenantStaffRepository.countByRole("tenant_mp", null, null)).thenReturn(counts);

            assertThat(service.countStaffByRole("mp", null, null)).hasSize(1);
        }
    }

    // --- updateStaffRole ---

    @Nested
    @DisplayName("updateStaffRole")
    class UpdateStaffRole {

        private Authentication callerAuth(String tenantCode) {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv ->
                    List.of(new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase())));
            return auth;
        }

        @Test
        @DisplayName("throws ForbiddenAccessException when caller tenant does not match request tenant")
        void throwsWhenTenantMismatch() {
            Authentication auth = callerAuth("TR");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SECTION_OFFICER");

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when requested role is not in allowed list")
        void throwsWhenRoleNotAllowed() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUPER_USER");

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Role must be one of");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user not found")
        void throwsWhenUserNotFound() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");
            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when user already has the requested role")
        void throwsWhenSameRole() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SECTION_OFFICER");
            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.of(SECTION_OFFICER));

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already has role");
        }

        private UserResource mockKeycloakChain(String uuid) {
            Keycloak kc = mock(Keycloak.class);
            RealmResource realm = mock(RealmResource.class);
            UsersResource users = mock(UsersResource.class);
            UserResource userResource = mock(UserResource.class);
            UserRepresentation rep = new UserRepresentation();
            rep.setAttributes(new HashMap<>());

            when(keycloakProvider.getAdminInstance()).thenReturn(kc);
            when(keycloakProvider.getRealm()).thenReturn("jalsoochak-realm");
            when(kc.realm("jalsoochak-realm")).thenReturn(realm);
            when(realm.users()).thenReturn(users);
            when(users.get(uuid)).thenReturn(userResource);
            when(userResource.toRepresentation()).thenReturn(rep);
            return userResource;
        }

        @Test
        @DisplayName("successfully updates role and returns updated staff DTO")
        void updatesRoleSuccessfully() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            TenantStaffResponseDTO updated = TenantStaffResponseDTO.builder()
                    .id(10L).role("SUB_DIVISIONAL_OFFICER").build();

            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findUserTypeIdByName("SUB_DIVISIONAL_OFFICER")).thenReturn(Optional.of(4));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.updateUserRole("tenant_mp", 10L, 4L)).thenReturn(1);
            when(tenantStaffRepository.findStaffById("tenant_mp", 10L)).thenReturn(Optional.of(updated));
            mockKeycloakChain("kc-uuid");

            TenantStaffResponseDTO result = service.updateStaffRole(10L, req, auth);

            assertThat(result.role()).isEqualTo("SUB_DIVISIONAL_OFFICER");
            verify(userTenantRepository).findUserById("tenant_mp", 10L);
            verify(userCommonRepository).findUserTypeIdByName("SUB_DIVISIONAL_OFFICER");
            verify(userCommonRepository).findTenantIdByStateCode("mp");
            verify(userTenantRepository).updateUserRole("tenant_mp", 10L, 4L);
            verify(tenantStaffRepository).findStaffById("tenant_mp", 10L);
            verify(dataVersionRepository).bump("tenant_mp", org.arghyam.jalsoochak.user.enums.ResourceType.STAFF_USERS);
        }

        @Test
        @DisplayName("skips analytics event and logs warning when tenantId not found for stateCode")
        void skipsAnalyticsWhenTenantIdNotFound() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            TenantStaffResponseDTO updated = TenantStaffResponseDTO.builder()
                    .id(10L).role("SUB_DIVISIONAL_OFFICER").build();

            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findUserTypeIdByName("SUB_DIVISIONAL_OFFICER")).thenReturn(Optional.of(4));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.empty()); // no tenant found
            when(userTenantRepository.updateUserRole("tenant_mp", 10L, 4L)).thenReturn(1);
            when(tenantStaffRepository.findStaffById("tenant_mp", 10L)).thenReturn(Optional.of(updated));
            mockKeycloakChain("kc-uuid");

            TenantStaffResponseDTO result = service.updateStaffRole(10L, req, auth);

            assertThat(result.role()).isEqualTo("SUB_DIVISIONAL_OFFICER");
            verify(userAnalyticsEventPublisher, never()).publishStaffUserUpdatedAfterCommit(
                    anyLong(), anyInt(), anyInt(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("throws IllegalStateException when user has no Keycloak UUID")
        void throwsWhenKeycloakUuidMissing() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            TenantUserRecord noUuidUser = new TenantUserRecord(
                    10L, 1, "919876543210", "officer@test.com", 3L, "SECTION_OFFICER",
                    "Officer", null, TenantUserStatus.ACTIVE.code, null);
            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.of(noUuidUser));
            when(userCommonRepository.findUserTypeIdByName("SUB_DIVISIONAL_OFFICER")).thenReturn(Optional.of(4));

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no Keycloak UUID");
        }

        @Test
        @DisplayName("rolls back the Keycloak user_type attribute when an exception occurs mid-update, then rethrows")
        void rollsBackKeycloakOnException() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findUserTypeIdByName("SUB_DIVISIONAL_OFFICER")).thenReturn(Optional.of(4));
            UserResource userResource = mockKeycloakChain("kc-uuid");
            // Simulate failure at DB role update step
            when(userTenantRepository.updateUserRole("tenant_mp", 10L, 4L))
                    .thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB error");

            // Verify Keycloak attribute rollback: user_type restored to original role
            ArgumentCaptor<UserRepresentation> repCaptor =
                    ArgumentCaptor.forClass(UserRepresentation.class);
            verify(userResource, times(2)).update(repCaptor.capture());
            UserRepresentation rollbackRep = repCaptor.getAllValues().get(1);
            assertThat(rollbackRep.getAttributes().get("user_type")).isEqualTo(List.of("SECTION_OFFICER"));
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when DB role update affects 0 rows")
        void throwsWhenDbUpdateAffectsZeroRows() {
            Authentication auth = callerAuth("MP");
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            when(userTenantRepository.findUserById("tenant_mp", 10L)).thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findUserTypeIdByName("SUB_DIVISIONAL_OFFICER")).thenReturn(Optional.of(4));
            when(userTenantRepository.updateUserRole("tenant_mp", 10L, 4L)).thenReturn(0); // 0 rows affected
            mockKeycloakChain("kc-uuid");

            assertThatThrownBy(() -> service.updateStaffRole(10L, req, auth))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User not found during role update");
        }
    }

    // --- deactivateStaff ---

    @Nested
    @DisplayName("deactivateStaff")
    class DeactivateStaff {

        private Authentication stateAdminAuth(String tenantCode) {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv -> List.of(
                    new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase()),
                    new SimpleGrantedAuthority("ROLE_STATE_ADMIN")));
            return auth;
        }

        private Authentication superUserAuth() {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv ->
                    List.of(new SimpleGrantedAuthority("ROLE_SUPER_USER")));
            return auth;
        }

        private Authentication superStateAdminAuth() {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv ->
                    List.of(new SimpleGrantedAuthority("ROLE_SUPER_STATE_ADMIN")));
            return auth;
        }

        @Test
        @DisplayName("successfully deactivates active staff user")
        void deactivatesStaffSuccessfully() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.deactivateStaff(10L, "mp", auth);

            verify(staffKeycloakService).revokeKeycloakAccount(eq(SECTION_OFFICER), eq("tenant_mp"), isNull());
            verify(userTenantRepository).deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull());
            verify(userAnalyticsEventPublisher).publishStaffUserUpdatedAfterCommit(
                    eq(10L), eq(1), anyInt(), anyString(), anyString(), anyInt());
            verify(dataVersionRepository).bump("tenant_mp", org.arghyam.jalsoochak.user.enums.ResourceType.STAFF_USERS);
        }

        @Test
        @DisplayName("throws ForbiddenAccessException when state admin targets a different tenant")
        void throwsForbiddenWhenStateAdminDeactivatesOtherTenant() {
            Authentication auth = stateAdminAuth("TR");

            assertThatThrownBy(() -> service.deactivateStaff(10L, "mp", auth))
                    .isInstanceOf(ForbiddenAccessException.class)
                    .hasMessageContaining("own tenant");
        }

        @Test
        @DisplayName("SUPER_USER can deactivate staff across tenants without tenant check")
        void superUserCanDeactivateAcrossTenants() {
            Authentication auth = superUserAuth();
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.deactivateStaff(10L, "mp", auth);

            verify(staffKeycloakService).revokeKeycloakAccount(eq(SECTION_OFFICER), eq("tenant_mp"), isNull());
            verify(userTenantRepository).deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN can deactivate staff without tenant check")
        void superStateAdminCanDeactivate() {
            Authentication auth = superStateAdminAuth();
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.deactivateStaff(10L, "mp", auth);

            verify(staffKeycloakService).revokeKeycloakAccount(eq(SECTION_OFFICER), eq("tenant_mp"), isNull());
            verify(userTenantRepository).deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void throwsNotFoundWhenUserNotFound() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivateStaff(99L, "mp", auth))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Staff user not found");
        }

        @Test
        @DisplayName("throws BadRequestException when user is already inactive")
        void throwsBadRequestWhenUserAlreadyInactive() {
            Authentication auth = stateAdminAuth("MP");
            TenantUserRecord inactiveUser = new TenantUserRecord(
                    10L, 1, "919876543210", "officer@test.com", 3L, "SECTION_OFFICER",
                    "Officer", "kc-uuid", TenantUserStatus.INACTIVE.code, null);
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(inactiveUser));

            assertThatThrownBy(() -> service.deactivateStaff(10L, "mp", auth))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already deactivated");
        }

        @Test
        @DisplayName("skips analytics event when tenantId cannot be resolved")
        void skipsAnalyticsWhenTenantIdNotFound() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.empty());
            when(userTenantRepository.deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.deactivateStaff(10L, "mp", auth);

            verify(staffKeycloakService).revokeKeycloakAccount(eq(SECTION_OFFICER), eq("tenant_mp"), isNull());
            verify(userAnalyticsEventPublisher, never()).publishStaffUserUpdatedAfterCommit(
                    anyLong(), anyInt(), anyInt(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("logs and continues when deactivateStaffUser returns 0 (concurrent deactivation)")
        void logsAndContinuesOnConcurrentDeactivation() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.deactivateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(0); // concurrent request already deactivated

            // should not throw
            service.deactivateStaff(10L, "mp", auth);

            verify(staffKeycloakService).revokeKeycloakAccount(eq(SECTION_OFFICER), eq("tenant_mp"), isNull());
            verify(dataVersionRepository, never()).bump(any(), any());
        }
    }

    // --- activateStaff ---

    @Nested
    @DisplayName("activateStaff")
    class ActivateStaff {

        private static final TenantUserRecord INACTIVE_SECTION_OFFICER = new TenantUserRecord(
                10L, 1, "919876543210", "officer@test.com", 3L, "SECTION_OFFICER",
                "Officer", "kc-uuid", TenantUserStatus.INACTIVE.code, null);

        private Authentication stateAdminAuth(String tenantCode) {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv -> List.of(
                    new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase()),
                    new SimpleGrantedAuthority("ROLE_STATE_ADMIN")));
            return auth;
        }

        private Authentication superUserAuth() {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv ->
                    List.of(new SimpleGrantedAuthority("ROLE_SUPER_USER")));
            return auth;
        }

        private Authentication superStateAdminAuth() {
            Authentication auth = mock(Authentication.class);
            when(auth.getAuthorities()).thenAnswer(inv ->
                    List.of(new SimpleGrantedAuthority("ROLE_SUPER_STATE_ADMIN")));
            return auth;
        }

        @Test
        @DisplayName("successfully activates inactive staff user without touching Keycloak")
        void activatesStaffSuccessfully() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(INACTIVE_SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.activateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.activateStaff(10L, "mp", auth);

            verify(userTenantRepository).activateStaffUser(eq("tenant_mp"), eq(10L), isNull());
            verify(staffKeycloakService, never()).revokeKeycloakAccount(any(), any(), any());
            verify(userAnalyticsEventPublisher).publishStaffUserUpdatedAfterCommit(
                    eq(10L), eq(1), anyInt(), anyString(), anyString(), eq(TenantUserStatus.ACTIVE.code));
            verify(dataVersionRepository).bump("tenant_mp", org.arghyam.jalsoochak.user.enums.ResourceType.STAFF_USERS);
        }

        @Test
        @DisplayName("throws ForbiddenAccessException when state admin targets a different tenant")
        void throwsForbiddenWhenStateAdminActivatesOtherTenant() {
            Authentication auth = stateAdminAuth("TR");

            assertThatThrownBy(() -> service.activateStaff(10L, "mp", auth))
                    .isInstanceOf(ForbiddenAccessException.class)
                    .hasMessageContaining("own tenant");
        }

        @Test
        @DisplayName("SUPER_USER can activate staff across tenants without tenant check")
        void superUserCanActivateAcrossTenants() {
            Authentication auth = superUserAuth();
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(INACTIVE_SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.activateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.activateStaff(10L, "mp", auth);

            verify(userTenantRepository).activateStaffUser(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN can activate staff without tenant check")
        void superStateAdminCanActivate() {
            Authentication auth = superStateAdminAuth();
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(INACTIVE_SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.activateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.activateStaff(10L, "mp", auth);

            verify(userTenantRepository).activateStaffUser(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void throwsNotFoundWhenUserNotFound() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activateStaff(99L, "mp", auth))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Staff user not found");
        }

        @Test
        @DisplayName("throws BadRequestException when user is already active")
        void throwsBadRequestWhenUserAlreadyActive() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(SECTION_OFFICER));

            assertThatThrownBy(() -> service.activateStaff(10L, "mp", auth))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already active");
        }

        @Test
        @DisplayName("skips analytics event when tenantId cannot be resolved")
        void skipsAnalyticsWhenTenantIdNotFound() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(INACTIVE_SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.empty());
            when(userTenantRepository.activateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(1);

            service.activateStaff(10L, "mp", auth);

            verify(userAnalyticsEventPublisher, never()).publishStaffUserUpdatedAfterCommit(
                    anyLong(), anyInt(), anyInt(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("logs and continues when activateStaffUser returns 0 (concurrent activation)")
        void logsAndContinuesOnConcurrentActivation() {
            Authentication auth = stateAdminAuth("MP");
            when(userTenantRepository.findUserById("tenant_mp", 10L))
                    .thenReturn(Optional.of(INACTIVE_SECTION_OFFICER));
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            when(userTenantRepository.activateStaffUser(eq("tenant_mp"), eq(10L), isNull()))
                    .thenReturn(0);

            service.activateStaff(10L, "mp", auth);

            verify(userAnalyticsEventPublisher, never()).publishStaffUserUpdatedAfterCommit(
                    anyLong(), anyInt(), anyInt(), anyString(), anyString(), anyInt());
            verify(dataVersionRepository, never()).bump(any(), any());
        }
    }
}
