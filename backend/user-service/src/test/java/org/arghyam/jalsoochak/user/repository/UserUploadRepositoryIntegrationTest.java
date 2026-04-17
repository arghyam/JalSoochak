package org.arghyam.jalsoochak.user.repository;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("UserUploadRepository Integration Tests")
class UserUploadRepositoryIntegrationTest {

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

    @Autowired UserUploadRepository repo;
    @Autowired PiiEncryptionService pii;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM tenant_mp.user_scheme_mapping_table");
        jdbc.execute("DELETE FROM tenant_mp.scheme_master_table");
        jdbc.execute("DELETE FROM tenant_mp.user_table");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int insertUser(String email, String phone, int userType) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, email, phone_number, phone_number_hash, user_type, status,
                     email_verification_status, phone_verification_status, created_at, updated_at)
                VALUES (1, ?, ?, ?, ?, 1, true, true, NOW(), NOW())
                RETURNING id
                """, Integer.class,
                email, pii.encrypt(phone), pii.hmac(phone), userType);
    }

    private int insertScheme(String stateId, String centreId) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_mp.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status)
                VALUES (?, ?, 'Test Scheme', 1, 1)
                RETURNING id
                """, Integer.class, stateId, centreId);
    }

    // ── findUserIdByEmailOrPhone ──────────────────────────────────────────────

    @Nested
    @DisplayName("findUserIdByEmailOrPhone")
    class FindUserIdByEmailOrPhone {

        @Test
        @DisplayName("finds user by email when email is provided")
        void findsByEmail() {
            int userId = insertUser("upload@mp.gov", "91XXXXXXXXX1", 2);
            Integer found = repo.findUserIdByEmailOrPhone(SCHEMA, "upload@mp.gov", null);
            assertThat(found).isEqualTo(userId);
        }

        @Test
        @DisplayName("email lookup is case-insensitive")
        void emailCaseInsensitive() {
            int userId = insertUser("case@mp.gov", "91XXXXXXXXX2", 2);
            Integer found = repo.findUserIdByEmailOrPhone(SCHEMA, "CASE@MP.GOV", null);
            assertThat(found).isEqualTo(userId);
        }

        @Test
        @DisplayName("falls back to phone when email not found")
        void fallsBackToPhone() {
            int userId = insertUser("phone@mp.gov", "91XXXXXXXXX3", 2);
            Integer found = repo.findUserIdByEmailOrPhone(SCHEMA, "nobody@x.com", "91XXXXXXXXX3");
            assertThat(found).isEqualTo(userId);
        }

        @Test
        @DisplayName("finds user by phone only when email is null")
        void findsByPhoneOnly() {
            int userId = insertUser("phoneonly@mp.gov", "91XXXXXXXXX4", 2);
            Integer found = repo.findUserIdByEmailOrPhone(SCHEMA, null, "91XXXXXXXXX4");
            assertThat(found).isEqualTo(userId);
        }

        @Test
        @DisplayName("returns null when both email and phone are null")
        void bothNull() {
            assertThat(repo.findUserIdByEmailOrPhone(SCHEMA, null, null)).isNull();
        }

        @Test
        @DisplayName("returns null when both email and phone are blank")
        void bothBlank() {
            assertThat(repo.findUserIdByEmailOrPhone(SCHEMA, "  ", "  ")).isNull();
        }

        @Test
        @DisplayName("returns null when user not found by either identifier")
        void notFound() {
            assertThat(repo.findUserIdByEmailOrPhone(SCHEMA, "missing@x.com", "91000000000")).isNull();
        }
    }

    // ── isUserStateAdmin ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("isUserStateAdmin")
    class IsUserStateAdmin {

        @Test
        @DisplayName("returns true for a STATE_ADMIN user (user_type=2)")
        void trueForStateAdmin() {
            int userId = insertUser("admin@mp.gov", "91XXXXXXXXX5", 2); // 2 = STATE_ADMIN
            assertThat(repo.isUserStateAdmin(SCHEMA, userId)).isTrue();
        }

        @Test
        @DisplayName("returns false for a non-admin user type")
        void falseForNonAdmin() {
            int userId = insertUser("officer@mp.gov", "91XXXXXXXXX6", 3); // 3 = SECTION_OFFICER
            assertThat(repo.isUserStateAdmin(SCHEMA, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false when userId is null")
        void falseForNull() {
            assertThat(repo.isUserStateAdmin(SCHEMA, null)).isFalse();
        }
    }

    // ── findSchemeId ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findSchemeId")
    class FindSchemeId {

        @Test
        @DisplayName("finds by state_scheme_id only")
        void findsByStateId() {
            int schemeId = insertScheme("STATE-001", "CENTRE-001");
            assertThat(repo.findSchemeId(SCHEMA, "STATE-001", null)).isEqualTo(schemeId);
        }

        @Test
        @DisplayName("finds by centre_scheme_id only")
        void findsByCentreId() {
            int schemeId = insertScheme("STATE-002", "CENTRE-002");
            assertThat(repo.findSchemeId(SCHEMA, null, "CENTRE-002")).isEqualTo(schemeId);
        }

        @Test
        @DisplayName("finds by both state and centre ids")
        void findsByBoth() {
            int schemeId = insertScheme("STATE-003", "CENTRE-003");
            assertThat(repo.findSchemeId(SCHEMA, "STATE-003", "CENTRE-003")).isEqualTo(schemeId);
        }

        @Test
        @DisplayName("returns null when both identifiers are null")
        void returnsNullWhenBothNull() {
            assertThat(repo.findSchemeId(SCHEMA, null, null)).isNull();
        }

        @Test
        @DisplayName("returns null when scheme not found")
        void returnsNullWhenNotFound() {
            assertThat(repo.findSchemeId(SCHEMA, "NONEXISTENT", null)).isNull();
        }
    }

    // ── insertUserSchemeMappings ──────────────────────────────────────────────

    @Nested
    @DisplayName("insertUserSchemeMappings")
    class InsertMappings {

        @Test
        @DisplayName("inserts rows and returns affected counts")
        void insertsMappings() {
            int userId = insertUser("map@mp.gov", "91XXXXXXXXX7", 3);
            int schemeId = insertScheme("STATE-010", "CENTRE-010");

            List<UserSchemeMappingCreateRow> rows = List.of(
                    new UserSchemeMappingCreateRow(userId, schemeId)
            );
            int[] result = repo.insertUserSchemeMappings(SCHEMA, rows, userId);
            assertThat(result).hasSize(1);
            assertThat(result[0]).isEqualTo(1);
        }

        @Test
        @DisplayName("ON CONFLICT DO NOTHING does not error on duplicate insert")
        void duplicateInsertIgnored() {
            int userId = insertUser("map2@mp.gov", "91XXXXXXXXX8", 3);
            int schemeId = insertScheme("STATE-011", "CENTRE-011");

            List<UserSchemeMappingCreateRow> rows = List.of(
                    new UserSchemeMappingCreateRow(userId, schemeId)
            );
            repo.insertUserSchemeMappings(SCHEMA, rows, userId);
            // second insert should silently be ignored
            int[] result = repo.insertUserSchemeMappings(SCHEMA, rows, userId);
            assertThat(result[0]).isZero();
        }

        @Test
        @DisplayName("returns empty array for empty list")
        void emptyList() {
            int[] result = repo.insertUserSchemeMappings(SCHEMA, List.of(), 1);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty array for null list")
        void nullList() {
            int[] result = repo.insertUserSchemeMappings(SCHEMA, null, 1);
            assertThat(result).isEmpty();
        }
    }

    // ── markUserSchemeMappingsDeleted ─────────────────────────────────────────

    @Nested
    @DisplayName("markUserSchemeMappingsDeleted")
    class MarkDeleted {

        @Test
        @DisplayName("soft-deletes mappings for a given user list")
        void marksDeleted() {
            int userId = insertUser("del@mp.gov", "91XXXXXXXXX9", 3);
            int schemeId = insertScheme("STATE-020", "CENTRE-020");
            repo.insertUserSchemeMappings(SCHEMA, List.of(new UserSchemeMappingCreateRow(userId, schemeId)), userId);

            int deleted = repo.markUserSchemeMappingsDeleted(SCHEMA, List.of((long) userId), userId);
            assertThat(deleted).isEqualTo(1);

            // mapping should no longer be in active set
            assertThat(repo.findActiveSchemeIdsForUser(SCHEMA, (long) userId)).isEmpty();
        }

        @Test
        @DisplayName("returns 0 for empty user id list")
        void emptyList() {
            assertThat(repo.markUserSchemeMappingsDeleted(SCHEMA, List.of(), 1)).isZero();
        }

        @Test
        @DisplayName("returns 0 for null user id list")
        void nullList() {
            assertThat(repo.markUserSchemeMappingsDeleted(SCHEMA, null, 1)).isZero();
        }
    }

    // ── findActiveSchemeIdsForUser ────────────────────────────────────────────

    @Nested
    @DisplayName("findActiveSchemeIdsForUser")
    class FindActiveSchemes {

        @Test
        @DisplayName("returns scheme ids for user with active mappings")
        void findsActive() {
            int userId = insertUser("scheme@mp.gov", "91XXXXXXXXXА", 3);
            int schemeId = insertScheme("STATE-030", "CENTRE-030");
            repo.insertUserSchemeMappings(SCHEMA, List.of(new UserSchemeMappingCreateRow(userId, schemeId)), userId);

            List<Integer> ids = repo.findActiveSchemeIdsForUser(SCHEMA, (long) userId);
            assertThat(ids).contains(schemeId);
        }

        @Test
        @DisplayName("returns empty list for user with no mappings")
        void noMappings() {
            assertThat(repo.findActiveSchemeIdsForUser(SCHEMA, 99999L)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when userId is null")
        void nullUserId() {
            assertThat(repo.findActiveSchemeIdsForUser(SCHEMA, null)).isEmpty();
        }
    }

    // ── schema name validation ────────────────────────────────────────────────

    @Nested
    @DisplayName("Schema name validation")
    class SchemaValidation {

        @Test
        @DisplayName("rejects null schema name")
        void rejectsNull() {
            assertThatThrownBy(() -> repo.findUserIdByEmailOrPhone(null, "a@b.com", null))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects schema name with special characters")
        void rejectsSpecialChars() {
            assertThatThrownBy(() -> repo.findUserIdByEmailOrPhone("bad schema!", "a@b.com", null))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
