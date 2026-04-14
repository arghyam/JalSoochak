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

        @Test
        @DisplayName("throws KeycloakOperationException on 409 when no concurrent password and Keycloak search finds nothing")
        void throwsOn409WhenNoPasswordInDbAndOrphanSearchEmpty() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(409);
            when(usersResource.create(any())).thenReturn(response);
            when(usersResource.searchByUsername(USER.phoneNumber(), true)).thenReturn(List.of());

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("409");
        }

        @Test
        @DisplayName("recovers orphaned Keycloak account on 409 when search finds the user")
        void recoversOrphanedKeycloakAccountOn409() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response createResponse = mock(Response.class);
            when(createResponse.getStatus()).thenReturn(409);
            when(usersResource.create(any())).thenReturn(createResponse);

            UserRepresentation orphanRep = new UserRepresentation();
            orphanRep.setId("orphan-uuid");
            when(usersResource.searchByUsername(USER.phoneNumber(), true)).thenReturn(List.of(orphanRep));

            UserResource orphanResource = mock(UserResource.class);
            when(usersResource.get("orphan-uuid")).thenReturn(orphanResource);
            doNothing().when(orphanResource).resetPassword(any());

            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-recovered");

            String result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result).isNotBlank();
            verify(orphanResource).resetPassword(any());
            verify(userTenantRepository).updateKeycloakUuidAndPassword(
                    eq("tenant_mp"), eq(10L), eq("orphan-uuid"), eq("encrypted-recovered"));
        }

        @Test
        @DisplayName("throws KeycloakOperationException on 409 when orphan search returns multiple users")
        void throwsOn409WhenOrphanSearchReturnsMultiple() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty());

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response createResponse = mock(Response.class);
            when(createResponse.getStatus()).thenReturn(409);
            when(usersResource.create(any())).thenReturn(createResponse);

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
