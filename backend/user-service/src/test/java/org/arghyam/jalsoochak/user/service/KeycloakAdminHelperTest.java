package org.arghyam.jalsoochak.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.keycloak.admin.client.resource.AttackDetectionResource;
import org.springframework.test.util.ReflectionTestUtils;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakAdminHelper - Unit Tests")
class KeycloakAdminHelperTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private KeycloakProvider keycloakProvider;

    @Mock
    private UserCommonRepository userCommonRepository;

    @Mock
    private PiiEncryptionService pii;

    private KeycloakAdminHelper helper;

    @BeforeEach
    void setUp() {
        // Default: safeDecrypt returns the raw value (mimics legacy-plaintext path).
        // Lenient because not all tests exercise the decrypt path.
        lenient().when(pii.safeDecrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        MetadataDecryptionHelper metadataDecryptionHelper = new MetadataDecryptionHelper(new ObjectMapper(), pii);
        helper = new KeycloakAdminHelper(keycloakProvider, userCommonRepository, metadataDecryptionHelper);
    }

    private AdminUserRow row(Long id, String uuid, String email, AdminUserStatus status) {
        return new AdminUserRow(id, uuid, email, "91XXXXXXXXXX", 1, 2, "STATE_ADMIN", status, 0, null);
    }

    private AdminUserTokenRow tokenRow(String email, String metadata) {
        return new AdminUserTokenRow(1L, email, "hash", "INVITE", metadata,
                Instant.now().plusSeconds(3600), null, null, Instant.now());
    }

    @Nested
    @DisplayName("PENDING user — names from invite token metadata")
    class PendingUser {

        @Test
        void returnsNamesFromTokenMetadata() {
            AdminUserRow user = row(1L, "placeholder-uuid", "admin@example.com", AdminUserStatus.PENDING);
            String metadata = "{\"firstName\":\"Alice\",\"lastName\":\"Smith\",\"role\":\"STATE_ADMIN\"}";
            when(userCommonRepository.findInviteTokenByEmail("admin@example.com"))
                    .thenReturn(Optional.of(tokenRow("admin@example.com", metadata)));
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));

            AdminUserResponseDTO result = helper.buildAdminUserResponse(user);

            assertEquals("Alice", result.getFirstName());
            assertEquals("Smith", result.getLastName());
            assertEquals("PENDING", result.getStatus());
            verifyNoInteractions(keycloakProvider);
        }

        @Test
        void returnsNullNamesWhenNoTokenExists() {
            AdminUserRow user = row(2L, "placeholder-uuid", "notoken@example.com", AdminUserStatus.PENDING);
            when(userCommonRepository.findInviteTokenByEmail("notoken@example.com"))
                    .thenReturn(Optional.empty());
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));

            AdminUserResponseDTO result = helper.buildAdminUserResponse(user);

            assertNull(result.getFirstName());
            assertNull(result.getLastName());
            verifyNoInteractions(keycloakProvider);
        }

        @Test
        void returnsDecryptedNamesFromEncryptedTokenMetadata() {
            AdminUserRow user = row(6L, "placeholder-uuid", "enc@example.com", AdminUserStatus.PENDING);
            // Simulate metadata where names were stored encrypted
            String encFirstName = "RU5DX0FsaWNl"; // placeholder ciphertext-like token for "Alice"
            String encLastName  = "RU5DX1NtaXRo"; // placeholder ciphertext-like token for "Smith"
            String metadata = "{\"firstName\":\"" + encFirstName + "\",\"lastName\":\"" + encLastName + "\",\"role\":\"STATE_ADMIN\"}";
            when(userCommonRepository.findInviteTokenByEmail("enc@example.com"))
                    .thenReturn(Optional.of(tokenRow("enc@example.com", metadata)));
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));
            when(pii.safeDecrypt(encFirstName)).thenReturn("Alice");
            when(pii.safeDecrypt(encLastName)).thenReturn("Smith");

            AdminUserResponseDTO result = helper.buildAdminUserResponse(user);

            assertEquals("Alice", result.getFirstName());
            assertEquals("Smith", result.getLastName());
            verifyNoInteractions(keycloakProvider);
        }

        @Test
        void returnsNullNamesWhenMetadataIsMalformed() {
            AdminUserRow user = row(3L, "placeholder-uuid", "bad@example.com", AdminUserStatus.PENDING);
            when(userCommonRepository.findInviteTokenByEmail("bad@example.com"))
                    .thenReturn(Optional.of(tokenRow("bad@example.com", "not-valid-json{")));
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));

            AdminUserResponseDTO result = helper.buildAdminUserResponse(user);

            assertNull(result.getFirstName());
            assertNull(result.getLastName());
            verifyNoInteractions(keycloakProvider);
        }
    }

    @Nested
    @DisplayName("ACTIVE user — names from Keycloak")
    class ActiveUser {

        @Test
        void returnsNamesFromKeycloak() {
            AdminUserRow user = row(4L, "keycloak-uuid-123", "active@example.com", AdminUserStatus.ACTIVE);
            UserRepresentation rep = new UserRepresentation();
            rep.setFirstName("Bob");
            rep.setLastName("Jones");
            when(keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm())
                    .users().get("keycloak-uuid-123").toRepresentation()).thenReturn(rep);
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));

            AdminUserResponseDTO result = helper.buildAdminUserResponse(user);

            assertEquals("Bob", result.getFirstName());
            assertEquals("Jones", result.getLastName());
            assertEquals("ACTIVE", result.getStatus());
        }

        @Test
        void returnsNullNamesWhenKeycloakFails() {
            AdminUserRow user = row(5L, "keycloak-uuid-456", "active2@example.com", AdminUserStatus.ACTIVE);
            when(keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm())
                    .users().get("keycloak-uuid-456").toRepresentation())
                    .thenThrow(new RuntimeException("Keycloak unavailable"));
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));

            AdminUserResponseDTO result = helper.buildAdminUserResponse(user);

            assertNull(result.getFirstName());
            assertNull(result.getLastName());
        }
    }

    @Nested
    @DisplayName("isTemporarilyLockedByBruteForce — short-TTL probe cache")
    class BruteForceLockCache {

        private AttackDetectionResource attackDetection;

        @BeforeEach
        void configureCache() {
            // The helper is built with `new` in the outer setUp, so the @Value fields default to 0
            // (cache disabled). Set realistic values for these tests.
            ReflectionTestUtils.setField(helper, "lockoutProbeCacheTtlSeconds", 15L);
            ReflectionTestUtils.setField(helper, "lockoutProbeCacheMaxSize", 10_000);
            attackDetection = keycloakProvider.getAdminInstance()
                    .realm(keycloakProvider.getRealm())
                    .attackDetection();
        }

        @Test
        @DisplayName("blank/null id short-circuits without any admin call")
        void blankIdSkipsProbe() {
            assertFalse(helper.isTemporarilyLockedByBruteForce(null));
            assertFalse(helper.isTemporarilyLockedByBruteForce("  "));
            verifyNoInteractions(attackDetection);
        }

        @Test
        @DisplayName("a locked result is cached — the second call within TTL makes no second probe")
        void cachesLockedResultWithinTtl() {
            String uuid = "kc-locked-1";
            when(attackDetection.bruteForceUserStatus(uuid)).thenReturn(Map.of("disabled", Boolean.TRUE));

            assertTrue(helper.isTemporarilyLockedByBruteForce(uuid));
            assertTrue(helper.isTemporarilyLockedByBruteForce(uuid));

            verify(attackDetection, times(1)).bruteForceUserStatus(uuid);
        }

        @Test
        @DisplayName("an unlocked result is also cached — avoids a probe on every wrong password")
        void cachesUnlockedResultWithinTtl() {
            String uuid = "kc-unlocked-1";
            when(attackDetection.bruteForceUserStatus(uuid)).thenReturn(Map.of("disabled", Boolean.FALSE));

            assertFalse(helper.isTemporarilyLockedByBruteForce(uuid));
            assertFalse(helper.isTemporarilyLockedByBruteForce(uuid));

            verify(attackDetection, times(1)).bruteForceUserStatus(uuid);
        }

        @Test
        @DisplayName("cache is per-user — distinct ids each probe once")
        void cacheIsKeyedByUser() {
            when(attackDetection.bruteForceUserStatus("kc-a")).thenReturn(Map.of("disabled", Boolean.TRUE));
            when(attackDetection.bruteForceUserStatus("kc-b")).thenReturn(Map.of("disabled", Boolean.FALSE));

            assertTrue(helper.isTemporarilyLockedByBruteForce("kc-a"));
            assertFalse(helper.isTemporarilyLockedByBruteForce("kc-b"));
            assertTrue(helper.isTemporarilyLockedByBruteForce("kc-a"));

            verify(attackDetection, times(1)).bruteForceUserStatus("kc-a");
            verify(attackDetection, times(1)).bruteForceUserStatus("kc-b");
        }

        @Test
        @DisplayName("fails open — an admin-API error returns false and is not cached")
        void failsOpenAndDoesNotCacheOnError() {
            String uuid = "kc-error-1";
            when(attackDetection.bruteForceUserStatus(uuid))
                    .thenThrow(new RuntimeException("admin API down"))
                    .thenReturn(Map.of("disabled", Boolean.TRUE));

            // First call: probe throws → fail open to false.
            assertFalse(helper.isTemporarilyLockedByBruteForce(uuid));
            // A false-on-error must NOT be cached, so the next call re-probes and now sees the lock.
            assertTrue(helper.isTemporarilyLockedByBruteForce(uuid));

            verify(attackDetection, times(2)).bruteForceUserStatus(uuid);
        }

        @Test
        @DisplayName("cache stays correct even when full of live entries (re-probes instead of caching)")
        void staysCorrectWhenCacheFull() {
            ReflectionTestUtils.setField(helper, "lockoutProbeCacheMaxSize", 1);
            when(attackDetection.bruteForceUserStatus("kc-full-a")).thenReturn(Map.of("disabled", Boolean.TRUE));
            when(attackDetection.bruteForceUserStatus("kc-full-b")).thenReturn(Map.of("disabled", Boolean.TRUE));

            // Fills the single slot with kc-full-a.
            assertTrue(helper.isTemporarilyLockedByBruteForce("kc-full-a"));
            // kc-full-b cannot be cached (slot full of a live entry) but must still return the correct value.
            assertTrue(helper.isTemporarilyLockedByBruteForce("kc-full-b"));
            assertTrue(helper.isTemporarilyLockedByBruteForce("kc-full-b"));

            // kc-full-a served from cache (1 probe); kc-full-b re-probes every time (never cached).
            verify(attackDetection, times(1)).bruteForceUserStatus("kc-full-a");
            verify(attackDetection, times(2)).bruteForceUserStatus("kc-full-b");
        }
    }
}
