package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("UserCommonRepository Integration Tests")
class UserCommonRepositoryIntegrationTest {

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

    @Autowired UserCommonRepository repo;
    @Autowired JdbcTemplate jdbc;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long createActiveUser(String email, String phone, int tenantId, int adminLevel) {
        return repo.createAdminUser("uuid-" + email, email, phone, tenantId, adminLevel, null);
    }

    private Long createPendingUser(String email, String phone, int tenantId, int adminLevel) {
        return repo.createAdminUserPending(email, phone, tenantId, adminLevel, null);
    }

    private void insertToken(String email, String hash, String type, String metadata, Instant expiresAt) {
        repo.insertToken(email, hash, type, metadata, expiresAt, null);
    }

    private Instant future() {
        return Instant.now().plus(1, ChronoUnit.HOURS);
    }

    private Instant past() {
        return Instant.now().minus(1, ChronoUnit.HOURS);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM common_schema.admin_user_token_table");
        jdbc.execute("DELETE FROM common_schema.tenant_admin_user_master_table");
    }

    // ── Tenant lookups ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tenant lookups")
    class TenantLookups {

        @Test
        @DisplayName("existsTenantByStateCode returns true for existing tenant (case-insensitive)")
        void existsTenant_caseInsensitive() {
            assertThat(repo.existsTenantByStateCode("MP")).isTrue();
            assertThat(repo.existsTenantByStateCode("mp")).isTrue();
        }

        @Test
        @DisplayName("existsTenantByStateCode returns false for unknown state code")
        void existsTenant_unknown() {
            assertThat(repo.existsTenantByStateCode("ZZ")).isFalse();
        }

        @Test
        @DisplayName("findTenantIdByStateCode returns id for known state code")
        void findTenantId_found() {
            Optional<Integer> id = repo.findTenantIdByStateCode("MP");
            assertThat(id).isPresent().hasValue(1);
        }

        @Test
        @DisplayName("findTenantIdByStateCode returns empty for unknown code")
        void findTenantId_notFound() {
            assertThat(repo.findTenantIdByStateCode("ZZ")).isEmpty();
        }

        @Test
        @DisplayName("findTenantStatusByTenantId returns status for known id")
        void findTenantStatus_found() {
            assertThat(repo.findTenantStatusByTenantId(1)).isPresent();
        }

        @Test
        @DisplayName("findTenantStatusByTenantId returns empty for unknown id")
        void findTenantStatus_notFound() {
            assertThat(repo.findTenantStatusByTenantId(999)).isEmpty();
        }

        @Test
        @DisplayName("findTenantStateCodeById returns state code for known id")
        void findStateCodeById_found() {
            assertThat(repo.findTenantStateCodeById(1)).isPresent().hasValue("MP");
        }

        @Test
        @DisplayName("findTenantStateCodeById returns empty for unknown id")
        void findStateCodeById_notFound() {
            assertThat(repo.findTenantStateCodeById(999)).isEmpty();
        }

        @Test
        @DisplayName("findTenantTitleByStateCode returns title")
        void findTenantTitle_found() {
            assertThat(repo.findTenantTitleByStateCode("MP")).isPresent().hasValue("Madhya Pradesh");
        }

        @Test
        @DisplayName("findTenantTitleByStateCode returns empty for unknown code")
        void findTenantTitle_notFound() {
            assertThat(repo.findTenantTitleByStateCode("ZZ")).isEmpty();
        }

        @Test
        @DisplayName("findAllTenantStateCodes returns at least one code")
        void findAllStateCodes() {
            assertThat(repo.findAllTenantStateCodes()).contains("MP");
        }

        @Test
        @DisplayName("findSingleTenant returns the only tenant when one exists")
        void findSingleTenant() {
            assertThat(repo.findSingleTenant()).isPresent();
        }
    }

    // ── User type lookups ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("User type lookups")
    class UserTypeLookups {

        @Test
        @DisplayName("findUserTypeIdByName returns id for known role (case-insensitive)")
        void findTypeId_found() {
            assertThat(repo.findUserTypeIdByName("SUPER_USER")).isPresent().hasValue(1);
            assertThat(repo.findUserTypeIdByName("super_user")).isPresent().hasValue(1);
        }

        @Test
        @DisplayName("findUserTypeIdByName returns empty for unknown role")
        void findTypeId_notFound() {
            assertThat(repo.findUserTypeIdByName("UNKNOWN_ROLE")).isEmpty();
        }

        @Test
        @DisplayName("findUserTypeNameById returns name for known id")
        void findTypeName_found() {
            assertThat(repo.findUserTypeNameById(1)).isPresent().hasValue("SUPER_USER");
        }

        @Test
        @DisplayName("findUserTypeNameById returns empty for unknown id")
        void findTypeName_notFound() {
            assertThat(repo.findUserTypeNameById(999)).isEmpty();
        }
    }

    // ── Admin user CRUD ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Admin user CRUD")
    class AdminUserCrud {

        @Test
        @DisplayName("createAdminUser returns a positive id and is findable by uuid")
        void createAndFindByUuid() {
            Long id = createActiveUser("admin@example.com", "91XXXXXXXXX1", 1, 2);
            assertThat(id).isPositive();

            Optional<AdminUserRow> found = repo.findAdminUserByUuid("uuid-admin@example.com");
            assertThat(found).isPresent();
            assertThat(found.get().email()).isEqualTo("admin@example.com");
            assertThat(found.get().status()).isEqualTo(AdminUserStatus.ACTIVE);
        }

        @Test
        @DisplayName("findAdminUserByUuid returns empty for unknown uuid")
        void findByUuid_notFound() {
            assertThat(repo.findAdminUserByUuid("nonexistent-uuid")).isEmpty();
        }

        @Test
        @DisplayName("findAdminUserByEmail finds user (case-insensitive)")
        void findByEmail() {
            createActiveUser("test@example.com", "91XXXXXXXXX1", 1, 2);

            assertThat(repo.findAdminUserByEmail("TEST@EXAMPLE.COM")).isPresent();
            assertThat(repo.findAdminUserByEmail("test@example.com")).isPresent();
        }

        @Test
        @DisplayName("findAdminUserByEmail returns empty for unknown email")
        void findByEmail_notFound() {
            assertThat(repo.findAdminUserByEmail("nobody@example.com")).isEmpty();
        }

        @Test
        @DisplayName("findAdminUserById returns the correct user")
        void findById() {
            Long id = createActiveUser("byid@example.com", "91XXXXXXXXX1", 1, 2);
            Optional<AdminUserRow> row = repo.findAdminUserById(id);
            assertThat(row).isPresent();
            assertThat(row.get().email()).isEqualTo("byid@example.com");
        }

        @Test
        @DisplayName("findAdminUserById returns empty for unknown id")
        void findById_notFound() {
            assertThat(repo.findAdminUserById(99999L)).isEmpty();
        }

        @Test
        @DisplayName("existsAdminUserByEmail returns true after creation")
        void existsByEmail() {
            createActiveUser("exists@example.com", "91XXXXXXXXX1", 1, 2);
            assertThat(repo.existsAdminUserByEmail("exists@example.com")).isTrue();
            assertThat(repo.existsAdminUserByEmail("nope@example.com")).isFalse();
        }

        @Test
        @DisplayName("existsActiveAdminUserByEmail excludes PENDING users")
        void existsActiveByEmail_excludesPending() {
            createPendingUser("pending@example.com", "91XXXXXXXXX2", 1, 2);
            assertThat(repo.existsActiveAdminUserByEmail("pending@example.com")).isFalse();
            assertThat(repo.existsAdminUserByEmail("pending@example.com")).isTrue();
        }

        @Test
        @DisplayName("existsActiveAdminUserByEmail returns true for active user")
        void existsActiveByEmail_trueForActive() {
            createActiveUser("active@example.com", "91XXXXXXXXX1", 1, 2);
            assertThat(repo.existsActiveAdminUserByEmail("active@example.com")).isTrue();
        }

        @Test
        @DisplayName("createAdminUserPending creates a PENDING user (status=2)")
        void createPending() {
            Long id = createPendingUser("pending2@example.com", "91XXXXXXXXX3", 1, 2);
            AdminUserRow row = repo.findAdminUserById(id).orElseThrow();
            assertThat(row.status()).isEqualTo(AdminUserStatus.PENDING);
        }

        @Test
        @DisplayName("activatePendingAdminUser transitions PENDING → ACTIVE with new uuid and phone")
        void activatePending() {
            Long id = createPendingUser("activate@example.com", "91XXXXXXXXX4", 1, 2);
            repo.activatePendingAdminUser(id, "real-keycloak-uuid", "91XXXXXXXXX5");

            AdminUserRow row = repo.findAdminUserById(id).orElseThrow();
            assertThat(row.status()).isEqualTo(AdminUserStatus.ACTIVE);
            assertThat(row.uuid()).isEqualTo("real-keycloak-uuid");
        }

        @Test
        @DisplayName("activatePendingAdminUser throws when user is not PENDING")
        void activatePending_throwsIfNotPending() {
            Long id = createActiveUser("notpending@example.com", "91XXXXXXXXX1", 1, 2);
            assertThatThrownBy(() -> repo.activatePendingAdminUser(id, "uuid", "91XXXXXXXXX9"))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("deactivateAdminUser sets status to INACTIVE (0)")
        void deactivate() {
            Long id = createActiveUser("deactivate@example.com", "91XXXXXXXXX1", 1, 2);
            repo.deactivateAdminUser(id, id);

            AdminUserRow row = repo.findAdminUserById(id).orElseThrow();
            assertThat(row.status()).isEqualTo(AdminUserStatus.INACTIVE);
        }

        @Test
        @DisplayName("activateAdminUser re-enables an INACTIVE user")
        void reactivate() {
            Long id = createActiveUser("reactivate@example.com", "91XXXXXXXXX1", 1, 2);
            repo.deactivateAdminUser(id, id);
            repo.activateAdminUser(id, id);

            AdminUserRow row = repo.findAdminUserById(id).orElseThrow();
            assertThat(row.status()).isEqualTo(AdminUserStatus.ACTIVE);
        }

        @Test
        @DisplayName("updateAdminUserProfile changes phone number")
        void updateProfile() {
            Long id = createActiveUser("update@example.com", "91XXXXXXXXX1", 1, 2);

            Long updatedByBefore = jdbc.queryForObject(
                    "SELECT updated_by FROM common_schema.tenant_admin_user_master_table WHERE id = ?",
                    Long.class, id);

            repo.updateAdminUserProfile(id, "91XXXXXXXXX9", id);

            Long updatedByAfter = jdbc.queryForObject(
                    "SELECT updated_by FROM common_schema.tenant_admin_user_master_table WHERE id = ?",
                    Long.class, id);

            assertThat(updatedByAfter).isEqualTo(id);
            assertThat(updatedByAfter).isNotEqualTo(updatedByBefore);
            assertThat(repo.findAdminUserById(id)).isPresent();
        }
    }

    // ── Counting ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Counting active users")
    class Counting {

        @Test
        @DisplayName("countActiveSuperUsers counts users with tenant_id=0")
        void countSuperUsers() {
            int before = repo.countActiveSuperUsers();
            createActiveUser("su@example.com", "91XXXXXXXXX1", 0, 1);
            assertThat(repo.countActiveSuperUsers()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("countActiveStateAdminsForTenant counts users for a specific tenant")
        void countStateAdmins() {
            int before = repo.countActiveStateAdminsForTenant(1);
            createActiveUser("sa@example.com", "91XXXXXXXXX1", 1, 2);
            assertThat(repo.countActiveStateAdminsForTenant(1)).isEqualTo(before + 1);
        }
    }

    // ── Listing (pagination) ──────────────────────────────────────────────────

    @Nested
    @DisplayName("listSuperUsers pagination")
    class ListSuperUsers {

        @Test
        @DisplayName("returns all super users when status is null")
        void listAll() {
            createActiveUser("su1@example.com", "91XXXXXXXXX1", 0, 1);
            createActiveUser("su2@example.com", "91XXXXXXXXX2", 0, 1);
            List<AdminUserRow> rows = repo.listSuperUsers(null, 0, 10);
            assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("filters by ACTIVE status")
        void filterByActiveStatus() {
            createActiveUser("active-su@example.com", "91XXXXXXXXX1", 0, 1);
            Long deactivatedId = createActiveUser("inactive-su@example.com", "91XXXXXXXXX2", 0, 1);
            repo.deactivateAdminUser(deactivatedId, deactivatedId);

            List<AdminUserRow> active = repo.listSuperUsers(AdminUserStatus.ACTIVE, 0, 10);
            assertThat(active).isNotEmpty().allMatch(r -> r.status() == AdminUserStatus.ACTIVE);
        }

        @Test
        @DisplayName("countSuperUsers without status returns all")
        void countAll() {
            createActiveUser("su3@example.com", "91XXXXXXXXX3", 0, 1);
            long count = repo.countSuperUsers(null);
            assertThat(count).isPositive();
        }

        @Test
        @DisplayName("countSuperUsers with status filters correctly")
        void countByStatus() {
            createActiveUser("su4@example.com", "91XXXXXXXXX4", 0, 1);
            long activeCount = repo.countSuperUsers(AdminUserStatus.ACTIVE);
            assertThat(activeCount).isPositive();
        }

        @Test
        @DisplayName("throws BadRequestException when limit is zero")
        void throwsOnZeroLimit() {
            assertThatThrownBy(() -> repo.listSuperUsers(null, 0, 0))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("throws BadRequestException when offset is negative")
        void throwsOnNegativeOffset() {
            assertThatThrownBy(() -> repo.listSuperUsers(null, -1, 10))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("listStateAdminsByTenant")
    class ListStateAdmins {

        @Test
        @DisplayName("lists state admins for a specific tenant")
        void listForTenant() {
            createActiveUser("sa1@example.com", "91XXXXXXXXX1", 1, 2);
            List<AdminUserRow> rows = repo.listStateAdminsByTenant(1, null, null, 0, 10);
            assertThat(rows).isNotEmpty();
        }

        @Test
        @DisplayName("lists all state admins when tenantId is null")
        void listAllTenants() {
            createActiveUser("sa2@example.com", "91XXXXXXXXX2", 1, 2);
            List<AdminUserRow> rows = repo.listStateAdminsByTenant(null, null, null, 0, 10);
            assertThat(rows).isNotEmpty();
        }

        @Test
        @DisplayName("filters by status")
        void filterByStatus() {
            createActiveUser("sa3@example.com", "91XXXXXXXXX3", 1, 2);
            List<AdminUserRow> rows = repo.listStateAdminsByTenant(1, AdminUserStatus.ACTIVE, null, 0, 10);
            assertThat(rows).isNotEmpty().allMatch(r -> r.status() == AdminUserStatus.ACTIVE);
        }

        @Test
        @DisplayName("filters by uuid set (name filter)")
        void filterByUuidSet() {
            Long id = createActiveUser("sa4@example.com", "91XXXXXXXXX4", 1, 2);
            String uuid = repo.findAdminUserById(id).orElseThrow().uuid();

            List<AdminUserRow> rows = repo.listStateAdminsByTenant(1, null, Set.of(uuid), 0, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).uuid()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("countStateAdminsByTenant returns correct count with filters")
        void countFiltered() {
            createActiveUser("sa5@example.com", "91XXXXXXXXX5", 1, 2);
            long count = repo.countStateAdminsByTenant(1, AdminUserStatus.ACTIVE, null);
            assertThat(count).isPositive();
        }

        @Test
        @DisplayName("countStateAdminsByTenant with null tenantId counts all non-zero tenants")
        void countAllTenants() {
            createActiveUser("sa6@example.com", "91XXXXXXXXX6", 1, 2);
            long count = repo.countStateAdminsByTenant(null, null, null);
            assertThat(count).isPositive();
        }

        @Test
        @DisplayName("throws BadRequestException when limit is zero")
        void throwsOnZeroLimit() {
            assertThatThrownBy(() -> repo.listStateAdminsByTenant(1, null, null, 0, 0))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("throws BadRequestException when offset is negative")
        void throwsOnNegativeOffset() {
            assertThatThrownBy(() -> repo.listStateAdminsByTenant(1, null, null, -1, 10))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ── userBelongsToTenant ───────────────────────────────────────────────────

    @Nested
    @DisplayName("userBelongsToTenant")
    class UserBelongsToTenant {

        @Test
        @DisplayName("returns true when user belongs to the given active tenant")
        void belongsToTenant() {
            Long id = createActiveUser("member@example.com", "91XXXXXXXXX1", 1, 2);
            assertThat(repo.userBelongsToTenant(id, "MP")).isTrue();
        }

        @Test
        @DisplayName("returns false when user belongs to a different tenant")
        void doesNotBelongToTenant() {
            Long id = createActiveUser("member2@example.com", "91XXXXXXXXX2", 1, 2);
            assertThat(repo.userBelongsToTenant(id, "TR")).isFalse();
        }

        @Test
        @DisplayName("returns false for non-existent user")
        void nonExistentUser() {
            assertThat(repo.userBelongsToTenant(99999L, "MP")).isFalse();
        }
    }

    // ── findPendingAdminUuidsByNameHash ───────────────────────────────────────

    @Nested
    @DisplayName("findPendingAdminUuidsByNameHash")
    class PendingAdminByNameHash {

        @Test
        @DisplayName("returns uuid when PENDING user has matching name hash in token metadata")
        void findsMatchingPendingUser() {
            Long id = createPendingUser("named@example.com", "91XXXXXXXXX1", 1, 2);
            String uuid = repo.findAdminUserById(id).orElseThrow().uuid();
            String nameHash = "abc123hash";
            insertToken("named@example.com", "tok-hash-1", "INVITE",
                    "{\"nameHash\":\"" + nameHash + "\"}", future());

            Set<String> uuids = repo.findPendingAdminUuidsByNameHash(nameHash, 1);
            assertThat(uuids).contains(uuid);
        }

        @Test
        @DisplayName("returns empty set when no pending user matches")
        void returnsEmptyWhenNoMatch() {
            Set<String> uuids = repo.findPendingAdminUuidsByNameHash("nomatch-hash", 1);
            assertThat(uuids).isEmpty();
        }

        @Test
        @DisplayName("scopes to all tenants when tenantId is null")
        void allTenantsWhenNull() {
            Long id = createPendingUser("allten@example.com", "91XXXXXXXXX2", 1, 2);
            String uuid = repo.findAdminUserById(id).orElseThrow().uuid();
            String nameHash = "allten-hash";
            insertToken("allten@example.com", "tok-hash-2", "INVITE",
                    "{\"nameHash\":\"" + nameHash + "\"}", future());

            Set<String> uuids = repo.findPendingAdminUuidsByNameHash(nameHash, null);
            assertThat(uuids).contains(uuid);
        }
    }

    // ── Token lifecycle ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Token lifecycle")
    class TokenLifecycle {

        @Test
        @DisplayName("findActiveTokenByHash finds a valid (non-expired) token")
        void findActive_found() {
            insertToken("tok@example.com", "hash-valid", "RESET", null, future());
            Optional<AdminUserTokenRow> row = repo.findActiveTokenByHash("hash-valid");
            assertThat(row).isPresent();
            assertThat(row.get().email()).isEqualTo("tok@example.com");
        }

        @Test
        @DisplayName("findActiveTokenByHash returns empty for expired token")
        void findActive_expired() {
            jdbc.update("""
                    INSERT INTO common_schema.admin_user_token_table
                        (email, token_hash, token_type, expires_at)
                    VALUES (?, ?, 'RESET', ?)
                    """, "expired@example.com", "hash-expired",
                    java.sql.Timestamp.from(past()));
            assertThat(repo.findActiveTokenByHash("hash-expired")).isEmpty();
        }

        @Test
        @DisplayName("findActiveTokenByHash returns empty for unknown hash")
        void findActive_unknown() {
            assertThat(repo.findActiveTokenByHash("nonexistent-hash")).isEmpty();
        }

        @Test
        @DisplayName("findInviteTokenByEmail finds unconsumed INVITE token regardless of expiry")
        void findInviteByEmail() {
            insertToken("invite@example.com", "hash-invite", "INVITE",
                    "{\"nameHash\":\"x\"}", future());
            Optional<AdminUserTokenRow> row = repo.findInviteTokenByEmail("invite@example.com");
            assertThat(row).isPresent();
            assertThat(row.get().tokenType()).isEqualTo("INVITE");
        }

        @Test
        @DisplayName("findInviteTokenByEmail returns empty when no invite token exists")
        void findInviteByEmail_notFound() {
            assertThat(repo.findInviteTokenByEmail("notoken@example.com")).isEmpty();
        }

        @Test
        @DisplayName("findActiveInviteTokenByEmail returns token when not expired")
        void findActiveInvite() {
            insertToken("activeinvite@example.com", "hash-ai", "INVITE",
                    "{\"nameHash\":\"y\"}", future());
            assertThat(repo.findActiveInviteTokenByEmail("activeinvite@example.com")).isPresent();
        }

        @Test
        @DisplayName("findActiveInviteTokenByEmail returns empty for expired INVITE token")
        void findActiveInvite_expired() {
            jdbc.update("""
                    INSERT INTO common_schema.admin_user_token_table
                        (email, token_hash, token_type, metadata, expires_at)
                    VALUES (?, ?, 'INVITE', ?::jsonb, ?)
                    """, "exinvite@example.com", "hash-ei",
                    "{\"nameHash\":\"z\"}",
                    java.sql.Timestamp.from(past()));
            assertThat(repo.findActiveInviteTokenByEmail("exinvite@example.com")).isEmpty();
        }

        @Test
        @DisplayName("consumeActiveToken marks token as used and returns it")
        void consumeToken() {
            insertToken("consume@example.com", "hash-consume", "RESET", null, future());
            Optional<AdminUserTokenRow> consumed = repo.consumeActiveToken("hash-consume");
            assertThat(consumed).isPresent();
            assertThat(consumed.get().usedAt()).isNotNull();
            // second consume should return empty
            assertThat(repo.consumeActiveToken("hash-consume")).isEmpty();
        }

        @Test
        @DisplayName("consumeActiveToken returns empty for expired token")
        void consumeToken_expired() {
            jdbc.update("""
                    INSERT INTO common_schema.admin_user_token_table
                        (email, token_hash, token_type, expires_at)
                    VALUES (?, ?, 'RESET', ?)
                    """, "exp@example.com", "hash-exp",
                    java.sql.Timestamp.from(past()));
            assertThat(repo.consumeActiveToken("hash-exp")).isEmpty();
        }

        @Test
        @DisplayName("consumeActiveTokenOfType matches on type and marks used")
        void consumeTokenOfType_match() {
            insertToken("typed@example.com", "hash-typed-r", "RESET", null, future());
            Optional<AdminUserTokenRow> consumed = repo.consumeActiveTokenOfType("hash-typed-r", "RESET");
            assertThat(consumed).isPresent();
        }

        @Test
        @DisplayName("consumeActiveTokenOfType returns empty when type does not match")
        void consumeTokenOfType_mismatch() {
            insertToken("mismatch@example.com", "hash-mis", "RESET", null, future());
            assertThat(repo.consumeActiveTokenOfType("hash-mis", "INVITE")).isEmpty();
            // token should still be unconsumed
            assertThat(repo.findActiveTokenByHash("hash-mis")).isPresent();
        }

        @Test
        @DisplayName("consumeActiveToken supersedes sibling tokens for same email+type")
        void consumeSupersidesSiblings() {
            // insert two RESET tokens for the same email
            jdbc.update("""
                    INSERT INTO common_schema.admin_user_token_table
                        (email, token_hash, token_type, expires_at)
                    VALUES (?, ?, 'RESET', ?)
                    """, "siblings@example.com", "hash-sib-1",
                    java.sql.Timestamp.from(future()));
            jdbc.update("""
                    INSERT INTO common_schema.admin_user_token_table
                        (email, token_hash, token_type, expires_at)
                    VALUES (?, ?, 'RESET', ?)
                    """, "siblings@example.com", "hash-sib-2",
                    java.sql.Timestamp.from(future()));

            repo.consumeActiveToken("hash-sib-1");

            // sibling should now be deleted (superseded)
            assertThat(repo.findActiveTokenByHash("hash-sib-2")).isEmpty();
        }

        @Test
        @DisplayName("revokeToken marks token as deleted")
        void revokeToken() {
            Long userId = createActiveUser("revoker@example.com", "91XXXXXXXXX1", 1, 2);
            insertToken("revoker@example.com", "hash-revoke", "RESET", null, future());
            repo.revokeToken("hash-revoke", userId);
            assertThat(repo.findActiveTokenByHash("hash-revoke")).isEmpty();
        }
    }
}
