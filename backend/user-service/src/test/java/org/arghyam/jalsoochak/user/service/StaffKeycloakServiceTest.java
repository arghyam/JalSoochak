package org.arghyam.jalsoochak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.exceptions.KeycloakOperationException;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.util.PasswordCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaffKeycloakService")
class StaffKeycloakServiceTest {

    @Mock KeycloakProvider keycloakProvider;
    @Mock KeycloakAdminHelper keycloakAdminHelper;
    @Mock UserTenantRepository userTenantRepository;
    @Mock PasswordCipher passwordCipher;

    StaffKeycloakService service;

    private static final TenantUserRecord USER = new TenantUserRecord(
            10L, 1, "919876543210", null, 3L, "SECTION_OFFICER",
            "Test Officer", null, 1, null);

    @BeforeEach
    void setUp() {
        service = new StaffKeycloakService(keycloakProvider, keycloakAdminHelper,
                userTenantRepository, passwordCipher);
    }

    @Nested
    @DisplayName("ensureKeycloakAccount – fast path")
    class FastPath {

        @Test
        @DisplayName("decrypts and returns existing managed password")
        void returnExistingPassword() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.of("encrypted-pw"));
            when(passwordCipher.decrypt("encrypted-pw")).thenReturn("plain-pw");

            String result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result).isEqualTo("plain-pw");
            verify(keycloakProvider, never()).getAdminInstance();
        }

        @Test
        @DisplayName("falls through to provisioning for CSV_ONBOARDED placeholder")
        void fallsThroughForCsvOnboarded() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.of("CSV_ONBOARDED"));

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(201);
            when(response.getLocation()).thenReturn(URI.create("http://kc/users/new-uuid"));
            when(usersResource.create(any())).thenReturn(response);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get("new-uuid")).thenReturn(userResource);
            doNothing().when(userResource).resetPassword(any());

            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-new");

            service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            verify(userTenantRepository).updateKeycloakUuidAndPassword(
                    eq("tenant_mp"), eq(10L), eq("new-uuid"), eq("encrypted-new"));
        }
    }

    @Nested
    @DisplayName("ensureKeycloakAccount – slow path")
    class SlowPath {

        @Test
        @DisplayName("compensates by deleting Keycloak user on provisioning failure")
        void compensatesOnFailure() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(201);
            when(response.getLocation()).thenReturn(URI.create("http://kc/users/new-uuid"));
            when(usersResource.create(any())).thenReturn(response);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get("new-uuid")).thenReturn(userResource);
            doThrow(new RuntimeException("Keycloak down")).when(userResource).resetPassword(any());

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(RuntimeException.class);

            verify(keycloakAdminHelper).deleteUser("new-uuid");
        }

        @Test
        @DisplayName("throws KeycloakOperationException when Keycloak create returns 201 but no Location header")
        void throwsOnMissingLocationHeader() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(201);
            when(response.getLocation()).thenReturn(null);
            when(usersResource.create(any())).thenReturn(response);

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("Location");
        }

        @Test
        @DisplayName("does not call deleteUser when usersResource.create throws before UUID is assigned")
        void doesNotDeleteUserWhenCreateThrows() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);
            when(usersResource.create(any())).thenThrow(new RuntimeException("Keycloak create failed"));

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Keycloak create failed");

            verify(keycloakAdminHelper, never()).deleteUser(anyString());
        }

        @Test
        @DisplayName("throws KeycloakOperationException when Keycloak create returns non-201 (e.g. 500)")
        void throwsOnNon201Response() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(500);
            when(response.hasEntity()).thenReturn(false);
            when(usersResource.create(any())).thenReturn(response);

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class);
        }

        @Test
        @DisplayName("recovers from 409 duplicate-user by returning the concurrent writer's password")
        void recoversFrom409WhenConcurrentWriterSetPassword() {
            // Initial fast-path read: placeholder (no managed password yet)
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.of("CSV_ONBOARDED"))
                    // Second call (inside 409 handler): concurrent writer has stored the password
                    .thenReturn(Optional.of("encrypted-concurrent-pw"));
            when(passwordCipher.decrypt("encrypted-concurrent-pw")).thenReturn("concurrent-plain-pw");

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(409);
            when(usersResource.create(any())).thenReturn(response);

            String result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result).isEqualTo("concurrent-plain-pw");
            verify(keycloakAdminHelper, never()).deleteUser(anyString());
        }

        // ── helpers ──────────────────────────────────────────────────────────────

        private UsersResource stubKeycloakAdmin() {
            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);
            return usersResource;
        }

        private void stubKeycloak409(UsersResource usersResource) {
            Response createResponse = mock(Response.class);
            when(createResponse.getStatus()).thenReturn(409);
            when(usersResource.create(any())).thenReturn(createResponse);
        }

        private UserResource stubOrphan(UsersResource usersResource, Map<String, List<String>> attrs) {
            UserRepresentation searchResult = new UserRepresentation();
            searchResult.setId("orphan-uuid");
            when(usersResource.searchByUsername(USER.phoneNumber(), true)).thenReturn(List.of(searchResult));

            UserRepresentation fullRep = new UserRepresentation();
            fullRep.setId("orphan-uuid");
            fullRep.setAttributes(attrs);

            UserResource orphanResource = mock(UserResource.class);
            when(usersResource.get("orphan-uuid")).thenReturn(orphanResource);
            when(orphanResource.toRepresentation()).thenReturn(fullRep);
            return orphanResource;
        }

        // ── orphan recovery – ownership checks ───────────────────────────────────

        @Test
        @DisplayName("recovers orphaned account when both tenant_state_code and database_user_id match")
        void recoversOrphanedKeycloakAccountOn409() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            UserResource orphanResource = stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("10")));
            doNothing().when(orphanResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-recovered");
            when(userTenantRepository.updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq("orphan-uuid"), eq("encrypted-recovered"))).thenReturn(1);

            String result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result).isNotBlank();
            verify(orphanResource).resetPassword(any());
            verify(userTenantRepository).updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq("orphan-uuid"), eq("encrypted-recovered"));
        }

        @Test
        @DisplayName("recovers orphaned account via tenant_state_code fallback when database_user_id is absent (pre-fix users)")
        void recoversOrphanedAccountViaFallbackWhenDatabaseUserIdAbsent() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            // Pre-fix user: only tenant_state_code, no database_user_id
            UserResource orphanResource = stubOrphan(usersResource, Map.of("tenant_state_code", List.of("MP")));
            doNothing().when(orphanResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-recovered");
            when(userTenantRepository.updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq("orphan-uuid"), eq("encrypted-recovered"))).thenReturn(1);

            String result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result).isNotBlank();
            verify(orphanResource).resetPassword(any());
            verify(userTenantRepository).updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq("orphan-uuid"), eq("encrypted-recovered"));
        }

        @Test
        @DisplayName("throws on orphan recovery when tenant_state_code does not match — prevents cross-tenant reset")
        void throwsOnOrphanRecoveryWhenTenantStateCodeMismatch() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            // Keycloak user belongs to a different tenant
            UserResource orphanResource = stubOrphan(usersResource, Map.of("tenant_state_code", List.of("TR")));

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("tenant");
            verify(orphanResource, never()).resetPassword(any());
        }

        @Test
        @DisplayName("throws on orphan recovery when database_user_id is present but belongs to a different user in the same tenant")
        void throwsOnOrphanRecoveryWhenDatabaseUserIdMismatch() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            // Tenant matches but database_user_id belongs to a different user (id=99, not 10)
            UserResource orphanResource = stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("database user");
            verify(orphanResource, never()).resetPassword(any());
        }

        // ── orphan recovery – conditional DB write ───────────────────────────────

        @Test
        @DisplayName("orphan recovery returns concurrent winner's password when conditional DB update finds 0 rows")
        void orphanRecoveryReturnsConcurrentPasswordWhenDbUpdateFinds0Rows() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty())   // 1st call: fast path — no password yet
                    .thenReturn(Optional.empty())   // 2nd call: Path 1 concurrent check — still empty, falls through to orphan recovery
                    // 3rd call: inside affected==0 handler — concurrent writer stored a password
                    .thenReturn(Optional.of("encrypted-concurrent"));
            when(passwordCipher.decrypt("encrypted-concurrent")).thenReturn("plain-concurrent");

            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            UserResource orphanResource = stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("10")));
            doNothing().when(orphanResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-this-thread");
            when(userTenantRepository.updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq("orphan-uuid"), eq("encrypted-this-thread"))).thenReturn(0);

            String result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result).isEqualTo("plain-concurrent");
        }

        // ── orphan recovery – search result count ────────────────────────────────

        @Test
        @DisplayName("throws KeycloakOperationException on 409 when orphan search finds nothing")
        void throwsOn409WhenNoPasswordInDbAndOrphanSearchEmpty() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            when(usersResource.searchByUsername(USER.phoneNumber(), true)).thenReturn(List.of());

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("409");
        }

        @Test
        @DisplayName("throws KeycloakOperationException on 409 when orphan search returns multiple users")
        void throwsOn409WhenOrphanSearchReturnsMultiple() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            UserRepresentation rep1 = new UserRepresentation();
            rep1.setId("uuid-1");
            UserRepresentation rep2 = new UserRepresentation();
            rep2.setId("uuid-2");
            when(usersResource.searchByUsername(USER.phoneNumber(), true)).thenReturn(List.of(rep1, rep2));

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("409");
        }
    }
}
