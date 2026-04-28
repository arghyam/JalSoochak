package org.arghyam.jalsoochak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.UUID;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.exceptions.KeycloakOperationException;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.StaffKeycloakService.ProvisionResult;
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

    private static final String NEW_KC_UUID    = "aaaaaaaa-bbbb-cccc-dddd-000000000001";
    private static final String ORPHAN_KC_UUID = "aaaaaaaa-bbbb-cccc-dddd-000000000002";

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

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.managedPassword()).isEqualTo("plain-pw");
            assertThat(result.keycloakUuid()).isNull();
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
            when(response.getLocation()).thenReturn(URI.create("http://kc/users/" + NEW_KC_UUID));
            when(usersResource.create(any())).thenReturn(response);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get(NEW_KC_UUID)).thenReturn(userResource);
            doNothing().when(userResource).resetPassword(any());

            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-new");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(NEW_KC_UUID));
            verify(userTenantRepository).updateKeycloakUuidAndPassword(
                    eq("tenant_mp"), eq(10L), eq(NEW_KC_UUID), eq("encrypted-new"));
        }

        @Test
        @DisplayName("falls through to provisioning when managed password decryption fails")
        void fallsThroughToProvisioningWhenDecryptFails() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.of("corrupted-encrypted-pw"));
            when(passwordCipher.decrypt("corrupted-encrypted-pw"))
                    .thenThrow(new IllegalStateException("decryption failed"));

            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(201);
            when(response.getLocation()).thenReturn(URI.create("http://kc/users/" + NEW_KC_UUID));
            when(usersResource.create(any())).thenReturn(response);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get(NEW_KC_UUID)).thenReturn(userResource);
            doNothing().when(userResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-new");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            // decrypt failure must trigger re-provisioning, not fast-path return
            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(NEW_KC_UUID));
            verify(userTenantRepository).updateKeycloakUuidAndPassword(
                    eq("tenant_mp"), eq(10L), eq(NEW_KC_UUID), eq("encrypted-new"));
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
            when(response.getLocation()).thenReturn(URI.create("http://kc/users/" + NEW_KC_UUID));
            when(usersResource.create(any())).thenReturn(response);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get(NEW_KC_UUID)).thenReturn(userResource);
            doThrow(new RuntimeException("Keycloak down")).when(userResource).resetPassword(any());

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(RuntimeException.class);

            verify(keycloakAdminHelper).deleteUser(NEW_KC_UUID);
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

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.managedPassword()).isEqualTo("concurrent-plain-pw");
            assertThat(result.keycloakUuid()).isNull();
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
            searchResult.setId(ORPHAN_KC_UUID);
            when(usersResource.searchByUsername(USER.phoneNumber(), true)).thenReturn(List.of(searchResult));

            UserRepresentation fullRep = new UserRepresentation();
            fullRep.setId(ORPHAN_KC_UUID);
            fullRep.setAttributes(attrs);

            UserResource orphanResource = mock(UserResource.class);
            when(usersResource.get(ORPHAN_KC_UUID)).thenReturn(orphanResource);
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
                    eq("tenant_mp"), eq(10L), eq(ORPHAN_KC_UUID), eq("encrypted-recovered"))).thenReturn(1);

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.managedPassword()).isNotBlank();
            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(ORPHAN_KC_UUID));
            verify(orphanResource).resetPassword(any());
            verify(userTenantRepository).updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq(ORPHAN_KC_UUID), eq("encrypted-recovered"));
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
                    eq("tenant_mp"), eq(10L), eq(ORPHAN_KC_UUID), eq("encrypted-recovered"))).thenReturn(1);

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.managedPassword()).isNotBlank();
            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(ORPHAN_KC_UUID));
            verify(orphanResource).resetPassword(any());
            verify(userTenantRepository).updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq(ORPHAN_KC_UUID), eq("encrypted-recovered"));
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
        @DisplayName("throws on orphan recovery when database_user_id belongs to a different user who still holds the same phone (genuine conflict)")
        void throwsOnOrphanRecoveryWhenDatabaseUserIdMismatch() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            // Tenant matches but database_user_id=99, not 10 — and user 99 is still active with same phone
            UserResource orphanResource = stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            // Genuine conflict: user 99 still holds the same phone number
            TenantUserRecord conflictingUser = new TenantUserRecord(
                    99L, 1, USER.phoneNumber(), null, 3L, "SECTION_OFFICER", "Other Officer", null, 1, null);
            when(userTenantRepository.findUserById("tenant_mp", 99L))
                    .thenReturn(Optional.of(conflictingUser));

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("database user");
            verify(orphanResource, never()).resetPassword(any());
        }

        @Test
        @DisplayName("deletes stale orphan and provisions fresh account when old owner is INACTIVE (deactivated without Keycloak cleanup)")
        void deletesStaleOrphanAndProvisionsFreshAccountWhenOwnerInactive() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            // Old owner exists in DB but is INACTIVE — should be treated as stale, not a genuine conflict
            TenantUserRecord inactiveOwner = new TenantUserRecord(
                    99L, 1, USER.phoneNumber(), null, 3L, "SECTION_OFFICER", "Old Officer", null, 0, null);
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.of(inactiveOwner));

            String freshUuid = "cccccccc-dddd-eeee-ffff-000000000004";
            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response freshResponse = mock(Response.class);
            when(freshResponse.getStatus()).thenReturn(201);
            when(freshResponse.getLocation()).thenReturn(URI.create("http://kc/users/" + freshUuid));
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(freshResponse);

            UserResource freshResource = mock(UserResource.class);
            when(usersResource.get(freshUuid)).thenReturn(freshResource);
            doNothing().when(freshResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-fresh-inactive");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(freshUuid));
            assertThat(result.managedPassword()).isNotBlank();
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
            verify(userTenantRepository).updateKeycloakUuidAndPassword(
                    eq("tenant_mp"), eq(10L), eq(freshUuid), eq("encrypted-fresh-inactive"));
        }

        @Test
        @DisplayName("deletes stale orphan and provisions fresh account when old owner no longer holds the phone")
        void deletesStaleOrphanAndProvisionsFreshAccountWhenOwnerGone() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            // database_user_id=99 doesn't match current user (id=10); old owner is gone from DB
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.empty());

            // First create call returns 409 (triggers orphan recovery); second returns 201 (fresh account)
            String freshUuid = "cccccccc-dddd-eeee-ffff-000000000003";
            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response freshResponse = mock(Response.class);
            when(freshResponse.getStatus()).thenReturn(201);
            when(freshResponse.getLocation()).thenReturn(URI.create("http://kc/users/" + freshUuid));
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(freshResponse);

            UserResource freshResource = mock(UserResource.class);
            when(usersResource.get(freshUuid)).thenReturn(freshResource);
            doNothing().when(freshResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-fresh");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(freshUuid));
            assertThat(result.managedPassword()).isNotBlank();
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
            verify(userTenantRepository).updateKeycloakUuidAndPassword(
                    eq("tenant_mp"), eq(10L), eq(freshUuid), eq("encrypted-fresh"));
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
                    eq("tenant_mp"), eq(10L), eq(ORPHAN_KC_UUID), eq("encrypted-this-thread"))).thenReturn(0);

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.managedPassword()).isEqualTo("plain-concurrent");
            assertThat(result.keycloakUuid()).isNull();
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

        // ── stale orphan detection – ownership attribute edge cases ─────────────

        @Test
        @DisplayName("treats orphan as stale when old owner has a different phone number")
        void deletesStaleOrphanWhenOwnerHasDifferentPhone() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            // Old owner exists but holds a different phone → STALE (not a genuine conflict)
            TenantUserRecord differentPhoneOwner = new TenantUserRecord(
                    99L, 1, "919999999999", null, 3L, "SECTION_OFFICER", "Old Owner", null, 1, null);
            when(userTenantRepository.findUserById("tenant_mp", 99L))
                    .thenReturn(Optional.of(differentPhoneOwner));

            String freshUuid = "dddddddd-eeee-ffff-0000-000000000005";
            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response freshResponse = mock(Response.class);
            when(freshResponse.getStatus()).thenReturn(201);
            when(freshResponse.getLocation()).thenReturn(URI.create("http://kc/users/" + freshUuid));
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(freshResponse);

            UserResource freshResource = mock(UserResource.class);
            when(usersResource.get(freshUuid)).thenReturn(freshResource);
            doNothing().when(freshResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-fresh-diff-phone");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(freshUuid));
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
        }

        @Test
        @DisplayName("treats orphan as stale when database_user_id attribute is an empty list")
        void deletesStaleOrphanWhenDatabaseUserIdIsEmptyList() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            // database_user_id key present but list is empty → storedIdStr = null → STALE
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of()));

            String freshUuid = "eeeeeeee-ffff-0000-1111-000000000006";
            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response freshResponse = mock(Response.class);
            when(freshResponse.getStatus()).thenReturn(201);
            when(freshResponse.getLocation()).thenReturn(URI.create("http://kc/users/" + freshUuid));
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(freshResponse);

            UserResource freshResource = mock(UserResource.class);
            when(usersResource.get(freshUuid)).thenReturn(freshResource);
            doNothing().when(freshResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-fresh-empty-id");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(freshUuid));
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
            // storedUserId was null → no DB lookup for old owner
            verify(userTenantRepository, never()).findUserById(eq("tenant_mp"), anyLong());
        }

        @Test
        @DisplayName("treats orphan as stale when database_user_id attribute is not a valid Long")
        void deletesStaleOrphanWhenDatabaseUserIdIsNotParseable() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("not-a-number")));

            String freshUuid = "ffffffff-0000-1111-2222-000000000007";
            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response freshResponse = mock(Response.class);
            when(freshResponse.getStatus()).thenReturn(201);
            when(freshResponse.getLocation()).thenReturn(URI.create("http://kc/users/" + freshUuid));
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(freshResponse);

            UserResource freshResource = mock(UserResource.class);
            when(usersResource.get(freshUuid)).thenReturn(freshResource);
            doNothing().when(freshResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-fresh-bad-id");

            ProvisionResult result = service.ensureKeycloakAccount(USER, "MP", "tenant_mp");

            assertThat(result.keycloakUuid()).isEqualTo(UUID.fromString(freshUuid));
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
            verify(userTenantRepository, never()).findUserById(eq("tenant_mp"), anyLong());
        }

        // ── createFreshAccountAfterStaleDeletion – error paths ───────────────────

        @Test
        @DisplayName("throws KeycloakOperationException when re-create after stale deletion returns non-201")
        void throwsWhenStaleOrphanReCreationFails() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.empty());

            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response failureResponse = mock(Response.class);
            when(failureResponse.getStatus()).thenReturn(500);
            when(failureResponse.hasEntity()).thenReturn(false);
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(failureResponse);

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("re-provision");
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
        }

        @Test
        @DisplayName("throws KeycloakOperationException when re-create after stale deletion returns no Location header")
        void throwsWhenStaleOrphanReCreationReturnsNoLocation() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.empty());

            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response noLocationResponse = mock(Response.class);
            when(noLocationResponse.getStatus()).thenReturn(201);
            when(noLocationResponse.getLocation()).thenReturn(null);
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(noLocationResponse);

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("Location");
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
        }

        @Test
        @DisplayName("deletes fresh account as compensation when setPassword fails after stale orphan deletion")
        void compensatesWithDeleteWhenSetPasswordFailsAfterStaleDeletion() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L)).thenReturn(Optional.empty());
            UsersResource usersResource = stubKeycloakAdmin();
            stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("99")));
            when(userTenantRepository.findUserById("tenant_mp", 99L)).thenReturn(Optional.empty());

            String freshUuid = "bbbbbbbb-cccc-dddd-eeee-000000000008";
            Response conflictResponse = mock(Response.class);
            when(conflictResponse.getStatus()).thenReturn(409);
            Response freshResponse = mock(Response.class);
            when(freshResponse.getStatus()).thenReturn(201);
            when(freshResponse.getLocation()).thenReturn(URI.create("http://kc/users/" + freshUuid));
            when(usersResource.create(any())).thenReturn(conflictResponse).thenReturn(freshResponse);

            UserResource freshResource = mock(UserResource.class);
            when(usersResource.get(freshUuid)).thenReturn(freshResource);
            doThrow(new RuntimeException("Keycloak password reset failed")).when(freshResource).resetPassword(any());

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Keycloak password reset failed");

            // Stale orphan deleted, then fresh account also cleaned up on failure
            verify(keycloakAdminHelper).deleteUser(ORPHAN_KC_UUID);
            verify(keycloakAdminHelper).deleteUser(freshUuid);
        }

        @Test
        @DisplayName("throws KeycloakOperationException when orphan conditional DB update returns 0 and concurrent password decrypt fails")
        void throwsWhenOrphanConditionalDbUpdateFinds0RowsAndDecryptFails() {
            when(userTenantRepository.findPasswordByUserId("tenant_mp", 10L))
                    .thenReturn(Optional.empty())            // 1st: fast path
                    .thenReturn(Optional.empty())            // 2nd: Path 1 concurrent check (inside 409 handler)
                    .thenReturn(Optional.of("bad-encrypted")); // 3rd: inside affected==0 handler
            when(passwordCipher.decrypt("bad-encrypted"))
                    .thenThrow(new IllegalStateException("corrupt ciphertext"));

            UsersResource usersResource = stubKeycloakAdmin();
            stubKeycloak409(usersResource);
            UserResource orphanResource = stubOrphan(usersResource, Map.of(
                    "tenant_state_code", List.of("MP"),
                    "database_user_id", List.of("10")));
            doNothing().when(orphanResource).resetPassword(any());
            when(passwordCipher.encrypt(anyString())).thenReturn("encrypted-this-thread");
            when(userTenantRepository.updateKeycloakUuidAndPasswordIfUnmanaged(
                    eq("tenant_mp"), eq(10L), eq(ORPHAN_KC_UUID), eq("encrypted-this-thread"))).thenReturn(0);

            assertThatThrownBy(() -> service.ensureKeycloakAccount(USER, "MP", "tenant_mp"))
                    .isInstanceOf(KeycloakOperationException.class)
                    .hasMessageContaining("Failed to sync orphan recovery to DB");
        }
    }

    @Nested
    @DisplayName("revokeKeycloakAccount")
    class RevokeKeycloakAccount {

        private static final String EXISTING_UUID = "ffffffff-0000-1111-2222-000000000001";

        @Test
        @DisplayName("deletes Keycloak user and resets DB credentials when uuid is present")
        void deletesKeycloakUserAndResetCredentials() {
            TenantUserRecord provisionedUser = new TenantUserRecord(
                    10L, 1, "919876543210", null, 3L, "SECTION_OFFICER",
                    "Test Officer", EXISTING_UUID, 1, null);
            when(userTenantRepository.resetKeycloakCredentials(eq("tenant_mp"), eq(10L), any())).thenReturn(1);

            service.revokeKeycloakAccount(provisionedUser, "tenant_mp", null);

            verify(keycloakAdminHelper).deleteUser(EXISTING_UUID);
            verify(userTenantRepository).resetKeycloakCredentials(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("skips Keycloak deletion and only resets DB when uuid is null and phone search returns empty")
        void skipsKeycloakDeletionWhenUuidIsNull() {
            TenantUserRecord unprovisionedUser = new TenantUserRecord(
                    10L, 1, "919876543210", null, 3L, "SECTION_OFFICER",
                    "Test Officer", null, 1, null);
            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);
            when(usersResource.searchByUsername("919876543210", true)).thenReturn(List.of());
            when(userTenantRepository.resetKeycloakCredentials(eq("tenant_mp"), eq(10L), any())).thenReturn(1);

            service.revokeKeycloakAccount(unprovisionedUser, "tenant_mp", null);

            verify(keycloakAdminHelper, never()).deleteUser(anyString());
            verify(userTenantRepository).resetKeycloakCredentials(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("deletes orphaned Keycloak user found by phone when uuid is null")
        void deletesOrphanedKeycloakUserFoundByPhone() {
            String foundId = "cccccccc-0000-1111-2222-000000000003";
            TenantUserRecord unprovisionedUser = new TenantUserRecord(
                    10L, 1, "919876543210", null, 3L, "SECTION_OFFICER",
                    "Test Officer", null, 1, null);
            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class);
            UserRepresentation found = new UserRepresentation();
            found.setId(foundId);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);
            when(usersResource.searchByUsername("919876543210", true)).thenReturn(List.of(found));
            when(userTenantRepository.resetKeycloakCredentials(eq("tenant_mp"), eq(10L), any())).thenReturn(1);

            service.revokeKeycloakAccount(unprovisionedUser, "tenant_mp", null);

            verify(keycloakAdminHelper).deleteUser(foundId);
            verify(userTenantRepository).resetKeycloakCredentials(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("still resets DB credentials even when Keycloak deletion fails")
        void stillResetDbEvenWhenKeycloakDeletionFails() {
            TenantUserRecord provisionedUser = new TenantUserRecord(
                    10L, 1, "919876543210", null, 3L, "SECTION_OFFICER",
                    "Test Officer", EXISTING_UUID, 1, null);
            // keycloakAdminHelper.deleteUser is best-effort (swallows exceptions internally),
            // so we verify the DB reset still happens regardless.
            when(userTenantRepository.resetKeycloakCredentials(eq("tenant_mp"), eq(10L), any())).thenReturn(1);

            service.revokeKeycloakAccount(provisionedUser, "tenant_mp", null);

            verify(keycloakAdminHelper).deleteUser(EXISTING_UUID);
            verify(userTenantRepository).resetKeycloakCredentials(eq("tenant_mp"), eq(10L), isNull());
        }

        @Test
        @DisplayName("skips Keycloak deletion and only resets DB when uuid is blank and phone search returns empty")
        void skipsKeycloakDeletionWhenUuidIsBlank() {
            TenantUserRecord blankUuidUser = new TenantUserRecord(
                    10L, 1, "919876543210", null, 3L, "SECTION_OFFICER",
                    "Test Officer", "   ", 1, null);
            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            UsersResource usersResource = mock(UsersResource.class);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("realm");
            when(mockAdmin.realm("realm").users()).thenReturn(usersResource);
            when(usersResource.searchByUsername("919876543210", true)).thenReturn(List.of());
            when(userTenantRepository.resetKeycloakCredentials(eq("tenant_mp"), eq(10L), any())).thenReturn(1);

            service.revokeKeycloakAccount(blankUuidUser, "tenant_mp", null);

            verify(keycloakAdminHelper, never()).deleteUser(anyString());
            verify(userTenantRepository).resetKeycloakCredentials(eq("tenant_mp"), eq(10L), isNull());
        }
    }
}
