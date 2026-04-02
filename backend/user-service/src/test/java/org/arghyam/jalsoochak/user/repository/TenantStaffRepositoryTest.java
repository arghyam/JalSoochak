package org.arghyam.jalsoochak.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
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

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("TenantStaffRepository - Integration Tests")
class TenantStaffRepositoryTest {

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

    @Autowired TenantStaffRepository staffRepository;
    @Autowired PiiEncryptionService pii;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsers() {
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_table");
    }

    /**
     * Inserts a user directly via SQL, mirroring what UserTenantRepository.createUser() does.
     * Hashes are lowercased + trimmed for case-insensitive name search (same convention as
     * UserTenantRepository).
     */
    private void insertUser(String name, String phone, String email, int userType, int status) {
        jdbcTemplate.update("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, title, title_hash, email, user_type, phone_number, phone_number_hash, status,
                     email_verification_status, phone_verification_status, created_at, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, true, true, NOW(), NOW())
                """,
                pii.encrypt(name),
                pii.hmac(name.trim().toLowerCase(Locale.ROOT)),
                email,
                userType,
                pii.encrypt(phone),
                pii.hmac(phone),
                status);
    }

    // ── name filter ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Name filtering via title_hash")
    class NameFilter {

        @Test
        @DisplayName("returns matching user when name matches exactly (case-insensitive)")
        void matchesExactName_caseInsensitive() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, null, "RAMESH KUMAR", "id", "asc", 0, 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Ramesh Kumar");
        }

        @Test
        @DisplayName("returns empty when name does not match any user")
        void returnsEmpty_whenNoMatch() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, null, "Unknown Name", "id", "asc", 0, 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns all users when name is null (no filter)")
        void returnsAll_whenNameNull() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, null, null, "id", "asc", 0, 10);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("returns all users when name is blank (no filter)")
        void returnsAll_whenNameBlank() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, null, "   ", "id", "asc", 0, 10);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("countStaff reflects name filter")
        void countStaff_reflectsNameFilter() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            long count = staffRepository.countStaff(SCHEMA, null, null, "Suresh Patel");

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("listStaffPage reflects name filter in items and total")
        void listStaffPage_reflectsNameFilter() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            TenantStaffRepository.StaffPage page =
                    staffRepository.listStaffPage(SCHEMA, null, null, "Ramesh Kumar", "id", "asc", 0, 10);

            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items()).hasSize(1);
            assertThat(page.items().get(0).title()).isEqualTo("Ramesh Kumar");
        }

        @Test
        @DisplayName("countByRole reflects name filter")
        void countByRole_reflectsNameFilter() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1);
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            List<RoleCountDTO> counts =
                    staffRepository.countByRole(SCHEMA, null, "Ramesh Kumar");

            long total = counts.stream().mapToLong(RoleCountDTO::count).sum();
            assertThat(total).isEqualTo(1);
        }

        @Test
        @DisplayName("name filter and status filter combine correctly")
        void nameAndStatusFilterCombine() {
            insertUser("Ramesh Kumar", "91XXXXXXXXX1", "ramesh@example.com", 1, 1); // active
            insertUser("Ramesh Kumar", "91XXXXXXXXX3", "ramesh2@example.com", 1, 0); // inactive
            insertUser("Suresh Patel", "91XXXXXXXXX2", "suresh@example.com", 1, 1);

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, 1, "Ramesh Kumar", "id", "asc", 0, 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).email()).isEqualTo("ramesh@example.com");
        }
    }

    // ── no filter (sanity) ───────────────────────────────────────────────────

    @Nested
    @DisplayName("List without name filter")
    class NoFilter {

        @Test
        @DisplayName("listStaff returns all active users when no filters applied")
        void listAll_noFilters() {
            insertUser("User A", "91XXXXXXXXX1", "a@example.com", 1, 1);
            insertUser("User B", "91XXXXXXXXX2", "b@example.com", 1, 1);

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, null, null, "id", "asc", 0, 10);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("pagination limits results correctly")
        void pagination_limitsResults() {
            insertUser("User A", "91XXXXXXXXX1", "a@example.com", 1, 1);
            insertUser("User B", "91XXXXXXXXX2", "b@example.com", 1, 1);
            insertUser("User C", "91XXXXXXXXX3", "c@example.com", 1, 1);

            List<TenantStaffResponseDTO> page1 =
                    staffRepository.listStaff(SCHEMA, null, null, null, "id", "asc", 0, 2);
            List<TenantStaffResponseDTO> page2 =
                    staffRepository.listStaff(SCHEMA, null, null, null, "id", "asc", 2, 2);

            assertThat(page1).hasSize(2);
            assertThat(page2).hasSize(1);
        }
    }
}
