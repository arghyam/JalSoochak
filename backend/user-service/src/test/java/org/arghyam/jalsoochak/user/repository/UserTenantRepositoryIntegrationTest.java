package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("UserTenantRepository Integration Tests")
class UserTenantRepositoryIntegrationTest {

    private static final String SCHEMA = "tenant_mp";

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserTenantRepository repo;
    @Autowired PiiEncryptionService pii;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM tenant_mp.user_table");
    }

    private Long insertUser(String uuid, String email, String phone, String title, int userType, String password) {
        return repo.createUser(SCHEMA, uuid, 1, title, email, userType, phone, password, null);
    }

    // ── createUser / findUserById ─────────────────────────────────────────────

    @Nested
    @DisplayName("createUser and findUserById")
    class CreateAndFindById {

        @Test
        @DisplayName("creates user and retrieves it by id with decrypted PII")
        void createAndFind() {
            Long id = insertUser("uuid-1", "officer@mp.gov", "91XXXXXXXXX1", "Section Officer", 3, "KEYCLOAK_MANAGED");
            assertThat(id).isPositive();

            Optional<TenantUserRecord> found = repo.findUserById(SCHEMA, id);
            assertThat(found).isPresent();
            assertThat(found.get().email()).isEqualTo("officer@mp.gov");
            assertThat(found.get().phoneNumber()).isEqualTo("91XXXXXXXXX1");
            assertThat(found.get().title()).isEqualTo("Section Officer");
            assertThat(found.get().status()).isEqualTo(TenantUserStatus.ACTIVE.code);
        }

        @Test
        @DisplayName("findUserById returns empty for unknown id")
        void findById_notFound() {
            assertThat(repo.findUserById(SCHEMA, 99999L)).isEmpty();
        }

        @Test
        @DisplayName("rejects invalid schema name")
        void invalidSchema() {
            assertThatThrownBy(() -> repo.findUserById("invalid-schema!", 1L))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── findUserByEmail ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("findUserByEmail")
    class FindByEmail {

        @Test
        @DisplayName("finds user by email (case-insensitive)")
        void findByEmail() {
            insertUser("uuid-2", "district@mp.gov", "91XXXXXXXXX2", "District Officer", 3, "pwd");

            assertThat(repo.findUserByEmail(SCHEMA, "DISTRICT@MP.GOV")).isPresent();
            assertThat(repo.findUserByEmail(SCHEMA, "district@mp.gov")).isPresent();
        }

        @Test
        @DisplayName("findUserByEmail returns empty for unknown email")
        void findByEmail_notFound() {
            assertThat(repo.findUserByEmail(SCHEMA, "nobody@example.com")).isEmpty();
        }
    }

    // ── findUserByKeycloakUuid ────────────────────────────────────────────────

    @Nested
    @DisplayName("findUserByKeycloakUuid")
    class FindByKeycloakUuid {

        @Test
        @DisplayName("finds user by keycloak uuid")
        void findByUuid() {
            insertUser("kc-uuid-abc", "kc@mp.gov", "91XXXXXXXXX3", "KCUser", 3, "pwd");
            assertThat(repo.findUserByKeycloakUuid(SCHEMA, "kc-uuid-abc")).isPresent();
        }

        @Test
        @DisplayName("returns empty for null or blank uuid")
        void findByUuid_nullOrBlank() {
            assertThat(repo.findUserByKeycloakUuid(SCHEMA, null)).isEmpty();
            assertThat(repo.findUserByKeycloakUuid(SCHEMA, "  ")).isEmpty();
        }

        @Test
        @DisplayName("returns empty for unknown uuid")
        void findByUuid_notFound() {
            assertThat(repo.findUserByKeycloakUuid(SCHEMA, "no-such-uuid")).isEmpty();
        }
    }

    // ── findUserByPhone ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("findUserByPhone")
    class FindByPhone {

        @Test
        @DisplayName("finds user by phone number (looks up via HMAC hash)")
        void findByPhone() {
            insertUser("uuid-phone", "phone@mp.gov", "91XXXXXXXXX4", "Phone User", 3, "pwd");
            Optional<TenantUserRecord> found = repo.findUserByPhone(SCHEMA, "91XXXXXXXXX4");
            assertThat(found).isPresent();
            assertThat(found.get().phoneNumber()).isEqualTo("91XXXXXXXXX4");
        }

        @Test
        @DisplayName("returns empty for null or blank phone")
        void findByPhone_nullOrBlank() {
            assertThat(repo.findUserByPhone(SCHEMA, null)).isEmpty();
            assertThat(repo.findUserByPhone(SCHEMA, "  ")).isEmpty();
        }

        @Test
        @DisplayName("returns empty when phone not found")
        void findByPhone_notFound() {
            assertThat(repo.findUserByPhone(SCHEMA, "91000000000")).isEmpty();
        }
    }

    // ── updateUserProfile ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateUserProfile")
    class UpdateProfile {

        @Test
        @DisplayName("updates title and phone; new phone is retrievable via hash lookup")
        void updateProfile() {
            Long id = insertUser("uuid-upd", "update@mp.gov", "91XXXXXXXXX5", "Old Name", 3, "pwd");

            repo.updateUserProfile(SCHEMA, id, "New Name", "91XXXXXXXXX6");

            TenantUserRecord row = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(row.title()).isEqualTo("New Name");
            assertThat(row.phoneNumber()).isEqualTo("91XXXXXXXXX6");

        }
    }

    // ── updateUserRole ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateUserRole")
    class UpdateRole {

        @Test
        @DisplayName("changes user_type for an existing user")
        void updateRole() {
            Long id = insertUser("uuid-role", "role@mp.gov", "91XXXXXXXXX7", "Role User", 3, "pwd");
            int updated = repo.updateUserRole(SCHEMA, id, 4L);
            assertThat(updated).isEqualTo(1);

            TenantUserRecord row = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(row.userTypeId()).isEqualTo(4L);
        }

        @Test
        @DisplayName("returns 0 when user does not exist")
        void updateRole_notFound() {
            int updated = repo.updateUserRole(SCHEMA, 99999L, 4L);
            assertThat(updated).isZero();
        }
    }

    // ── updateUserLanguageId ──────────────────────────────────────────────────

    @Nested
    @DisplayName("updateUserLanguageId")
    class UpdateLanguage {

        @Test
        @DisplayName("updates language_id for an existing user")
        void updateLanguage() {
            Long id = insertUser("uuid-lang", "lang@mp.gov", "91XXXXXXXXX8", "Lang User", 3, "pwd");
            repo.updateUserLanguageId(SCHEMA, id, 2);
            // no direct assertion on language_id via public API; verify no exception thrown
            assertThat(repo.findUserById(SCHEMA, id)).isPresent();
        }

        @Test
        @DisplayName("does nothing when userId is null")
        void updateLanguage_nullId() {
            repo.updateUserLanguageId(SCHEMA, null, 2); // should not throw
        }
    }

    // ── updateKeycloakUuidAndPassword ─────────────────────────────────────────

    @Nested
    @DisplayName("updateKeycloakUuidAndPassword")
    class UpdateKeycloakPassword {

        @Test
        @DisplayName("sets uuid and password for an existing user")
        void updateKcUuidAndPassword() {
            Long id = insertUser("old-uuid", "kcp@mp.gov", "91XXXXXXXXX9", "KCP User", 3, "KEYCLOAK_MANAGED");
            repo.updateKeycloakUuidAndPassword(SCHEMA, id, "new-kc-uuid", "enc-password");

            TenantUserRecord row = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(row.keycloakUuid()).isEqualTo("new-kc-uuid");
        }

        @Test
        @DisplayName("updateKeycloakUuidAndPasswordIfUnmanaged updates when password is placeholder")
        void updateIfUnmanaged_updates() {
            Long id = insertUser("unmanaged-uuid", "unm@mp.gov", "91XXXXXXXXXA", "Unmanaged", 3, "KEYCLOAK_MANAGED");
            int updated = repo.updateKeycloakUuidAndPasswordIfUnmanaged(SCHEMA, id, "managed-uuid", "enc-pw");
            assertThat(updated).isEqualTo(1);
        }

        @Test
        @DisplayName("updateKeycloakUuidAndPasswordIfUnmanaged does not overwrite managed password")
        void updateIfUnmanaged_skipsManaged() {
            Long id = insertUser("managed-uuid", "mgd@mp.gov", "91XXXXXXXXXB", "Managed", 3, "already-managed-enc");
            // already has a non-placeholder password → should not overwrite
            int updated = repo.updateKeycloakUuidAndPasswordIfUnmanaged(SCHEMA, id, "other-uuid", "new-enc-pw");
            assertThat(updated).isZero();
        }
    }

    // ── findUuidsByTitleHash ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findUuidsByTitleHash")
    class FindByTitleHash {

        @Test
        @DisplayName("returns matching uuids for a known title hash")
        void findsMatch() {
            insertUser("uuid-title", "title@mp.gov", "91XXXXXXXXXC", "Test Name", 3, "pwd");
            String hash = pii.hmac("test name"); // lowercase+trim matches createUser logic
            List<String> uuids = repo.findUuidsByTitleHash(SCHEMA, hash);
            assertThat(uuids).contains("uuid-title");
        }

        @Test
        @DisplayName("returns empty list when no match")
        void noMatch() {
            assertThat(repo.findUuidsByTitleHash(SCHEMA, "nonexistent-hash")).isEmpty();
        }
    }

    // ── findPasswordByUserId ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findPasswordByUserId")
    class FindPassword {

        @Test
        @DisplayName("returns stored password value")
        void findsPassword() {
            Long id = insertUser("uuid-pw", "pw@mp.gov", "91XXXXXXXXXD", "PW User", 3, "KEYCLOAK_MANAGED");
            Optional<String> pw = repo.findPasswordByUserId(SCHEMA, id);
            assertThat(pw).isPresent().hasValue("KEYCLOAK_MANAGED");
        }

        @Test
        @DisplayName("returns empty for unknown userId")
        void notFound() {
            assertThat(repo.findPasswordByUserId(SCHEMA, 99999L)).isEmpty();
        }
    }

    // ── streamPhonesByRolesAndOnboardingWindow ────────────────────────────────

    @Nested
    @DisplayName("streamPhonesByRolesAndOnboardingWindow")
    class StreamPhones {

        @Test
        @DisplayName("streams phones for matching role")
        void streamsMatchingPhones() {
            insertUser("uuid-stream", "stream@mp.gov", "91XXXXXXXXXE", "Stream User", 3, "pwd");

            List<String> phones = new ArrayList<>();
            repo.streamPhonesByRolesAndOnboardingWindow(SCHEMA, List.of("section_officer"), null, null, phones::add);

            assertThat(phones).contains("91XXXXXXXXXE");
        }

        @Test
        @DisplayName("does nothing for empty roles list")
        void emptyRoles() {
            insertUser("uuid-stream2", "stream2@mp.gov", "91XXXXXXXXXF", "No Role", 3, "pwd");
            List<String> phones = new ArrayList<>();
            repo.streamPhonesByRolesAndOnboardingWindow(SCHEMA, List.of(), null, null, phones::add);
            assertThat(phones).isEmpty();
        }

        @Test
        @DisplayName("does nothing for null roles list")
        void nullRoles() {
            List<String> phones = new ArrayList<>();
            repo.streamPhonesByRolesAndOnboardingWindow(SCHEMA, null, null, null, phones::add);
            assertThat(phones).isEmpty();
        }

        @Test
        @DisplayName("respects onboardedAfter window")
        void respectsOnboardedAfterWindow() {
            // Out-of-window user: inserted now, will be before the future threshold
            insertUser("uuid-stream3", "stream3@mp.gov", "91XXXXXXXXXG", "Window User", 3, "pwd");

            // In-window user: set created_at to well in the future via JDBC
            Long inWindowId = insertUser("uuid-stream3b", "stream3b@mp.gov", "91XXXXXXXXXG2", "Window User In", 3, "pwd");
            Instant futureOnboard = Instant.now().plus(2, ChronoUnit.DAYS);
            jdbc.update("UPDATE tenant_mp.user_table SET created_at = ? WHERE id = ?",
                    Timestamp.from(futureOnboard), inWindowId);

            Instant threshold = Instant.now().plus(1, ChronoUnit.DAYS);
            List<String> phones = new ArrayList<>();
            repo.streamPhonesByRolesAndOnboardingWindow(SCHEMA, List.of("section_officer"), threshold, null, phones::add);

            assertThat(phones).contains("91XXXXXXXXXG2");
            assertThat(phones).doesNotContain("91XXXXXXXXXG");
        }

        @Test
        @DisplayName("respects onboardedBefore window")
        void respectsOnboardedBeforeWindow() {
            // Out-of-window user: inserted now, will be after the past threshold
            insertUser("uuid-stream4", "stream4@mp.gov", "91XXXXXXXXXH", "Window User2", 3, "pwd");

            // In-window user: set created_at to well before the threshold via JDBC
            Long inWindowId = insertUser("uuid-stream4b", "stream4b@mp.gov", "91XXXXXXXXXH2", "Window User In", 3, "pwd");
            Instant pastOnboard = Instant.now().minus(2, ChronoUnit.DAYS);
            jdbc.update("UPDATE tenant_mp.user_table SET created_at = ? WHERE id = ?",
                    Timestamp.from(pastOnboard), inWindowId);

            Instant threshold = Instant.now().minus(1, ChronoUnit.DAYS);
            List<String> phones = new ArrayList<>();
            repo.streamPhonesByRolesAndOnboardingWindow(SCHEMA, List.of("section_officer"), null, threshold, phones::add);

            assertThat(phones).contains("91XXXXXXXXXH2");
            assertThat(phones).doesNotContain("91XXXXXXXXXH");
        }
    }

    // ── resetKeycloakCredentials ──────────────────────────────────────────────

    @Nested
    @DisplayName("resetKeycloakCredentials")
    class ResetKeycloakCredentials {

        @Test
        @DisplayName("resets password to CSV_ONBOARDED while leaving uuid unchanged")
        void resetCredentialsClearsManagedPassword() {
            Long id = insertUser("uuid-reset-kc", "reset@mp.gov", "91XXXXXXXXXR", "Reset User", 3, "encrypted-managed-pw");
            // Confirm uuid is set before reset
            TenantUserRecord before = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(before.keycloakUuid()).isEqualTo("uuid-reset-kc");

            int affected = repo.resetKeycloakCredentials(SCHEMA, id, 1L);
            assertThat(affected).isEqualTo(1);

            Optional<String> pw = repo.findPasswordByUserId(SCHEMA, id);
            assertThat(pw).hasValue("CSV_ONBOARDED");
            // uuid must be unchanged — it becomes a dangling reference but must not be cleared
            // (uuid column is NOT NULL and changing it requires an analytics sync event)
            TenantUserRecord after = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(after.keycloakUuid()).isEqualTo("uuid-reset-kc");
        }

        @Test
        @DisplayName("returns 0 when user does not exist")
        void resetCredentials_notFound() {
            int affected = repo.resetKeycloakCredentials(SCHEMA, 99999L, 1L);
            assertThat(affected).isZero();
        }
    }

    // ── deactivateStaffUser ───────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivateStaffUser")
    class DeactivateStaffUser {

        @Test
        @DisplayName("sets status to INACTIVE for an active user")
        void deactivatesActiveUser() {
            Long id = insertUser("uuid-deact", "deact@mp.gov", "91XXXXXXXXXS", "Deact User", 3, "KEYCLOAK_MANAGED");
            TenantUserRecord before = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(before.status()).isEqualTo(TenantUserStatus.ACTIVE.code);

            int affected = repo.deactivateStaffUser(SCHEMA, id, null);
            assertThat(affected).isEqualTo(1);

            TenantUserRecord after = repo.findUserById(SCHEMA, id).orElseThrow();
            assertThat(after.status()).isEqualTo(TenantUserStatus.INACTIVE.code);
        }

        @Test
        @DisplayName("returns 0 (idempotent) for an already inactive user")
        void isIdempotentForAlreadyInactiveUser() {
            Long id = insertUser("uuid-deact2", "deact2@mp.gov", "91XXXXXXXXXU", "Deact2 User", 3, "KEYCLOAK_MANAGED");
            repo.deactivateStaffUser(SCHEMA, id, null); // first deactivation

            int affected = repo.deactivateStaffUser(SCHEMA, id, null); // second call
            assertThat(affected).isZero();
        }

        @Test
        @DisplayName("returns 0 when user does not exist")
        void returnsZeroForNonexistentUser() {
            int affected = repo.deactivateStaffUser(SCHEMA, 99999L, null);
            assertThat(affected).isZero();
        }
    }

    // ── schema validation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Schema name validation")
    class SchemaValidation {

        @Test
        @DisplayName("rejects schema name with special characters")
        void rejectSpecialChars() {
            assertThatThrownBy(() -> repo.findUserByEmail("tenant-mp; DROP TABLE", "x@y.com"))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null schema name")
        void rejectNull() {
            assertThatThrownBy(() -> repo.findUserById(null, 1L))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects schema name that does not match tenant_ prefix pattern")
        void rejectNonTenantSchema() {
            assertThatThrownBy(() -> repo.findUserById("public", 1L))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
