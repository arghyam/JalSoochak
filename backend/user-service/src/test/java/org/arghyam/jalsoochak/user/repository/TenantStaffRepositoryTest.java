package org.arghyam.jalsoochak.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeSummaryDTO;
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
        // Mappings first — they FK onto both user_table and scheme_master_table.
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_scheme_mapping_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_master_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_table");
    }

    /**
     * Inserts a user directly via SQL, mirroring what UserTenantRepository.createUser() does.
     * Hashes are lowercased + trimmed for case-insensitive name search (same convention as
     * UserTenantRepository).
     */
    private void insertUser(String name, String phone, String email, int userType, int status) {
        insertUserReturningId(name, phone, email, userType, status);
    }

    private long insertUserReturningId(String name, String phone, String email, int userType, int status) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, title, title_hash, email, user_type, phone_number, phone_number_hash, status,
                     email_verification_status, phone_verification_status, created_at, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, true, true, NOW(), NOW())
                RETURNING id
                """,
                Long.class,
                pii.encrypt(name),
                pii.hmac(name.trim().toLowerCase(Locale.ROOT)),
                email,
                userType,
                pii.encrypt(phone),
                pii.hmac(phone),
                status);
        return id;
    }

    private long insertScheme(String schemeName, int workStatus, int operatingStatus) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, "S-" + schemeName, "C-" + schemeName, schemeName, workStatus, operatingStatus);
        return id;
    }

    private void mapUserToScheme(long userId, long schemeId) {
        jdbcTemplate.update("""
                INSERT INTO tenant_mp.user_scheme_mapping_table (user_id, scheme_id, status)
                VALUES (?, ?, 1)
                """, userId, schemeId);
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

    // ── scheme attachment ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scheme attachment")
    class SchemeAttachment {

        @Test
        @DisplayName("findStaffById attaches schemes instead of throwing on an immutable row list")
        void findStaffById_attachesSchemes() {
            long userId = insertUserReturningId("Scheme Owner", "91XXXXXXXXX1", "owner@example.com", 3, 1);
            long schemeId = insertScheme("Alpha Scheme", 2, 1);
            mapUserToScheme(userId, schemeId);

            Optional<TenantStaffResponseDTO> result = staffRepository.findStaffById(SCHEMA, userId);

            assertThat(result).isPresent();
            assertThat(result.get().schemes())
                    .singleElement()
                    .satisfies(scheme -> {
                        assertThat(scheme.schemeId()).isEqualTo(schemeId);
                        assertThat(scheme.schemeName()).isEqualTo("Alpha Scheme");
                        assertThat(scheme.workStatus()).isEqualTo("Completed");
                        assertThat(scheme.operatingStatus()).isEqualTo("Operative");
                    });
        }

        @Test
        @DisplayName("findStaffById returns an empty scheme list when the user has no mappings")
        void findStaffById_noSchemes() {
            long userId = insertUserReturningId("No Schemes", "91XXXXXXXXX2", "none@example.com", 3, 1);

            Optional<TenantStaffResponseDTO> result = staffRepository.findStaffById(SCHEMA, userId);

            assertThat(result).isPresent();
            assertThat(result.get().schemes()).isEmpty();
        }

        @Test
        @DisplayName("findStaffById returns empty for an unknown id")
        void findStaffById_unknownId() {
            assertThat(staffRepository.findStaffById(SCHEMA, 999_999L)).isEmpty();
        }

        @Test
        @DisplayName("listStaff still attaches schemes per user")
        void listStaff_attachesSchemesPerUser() {
            long withScheme = insertUserReturningId("Has Scheme", "91XXXXXXXXX3", "has@example.com", 3, 1);
            long withoutScheme = insertUserReturningId("No Scheme", "91XXXXXXXXX4", "no@example.com", 3, 1);
            mapUserToScheme(withScheme, insertScheme("Beta Scheme", 1, 1));

            List<TenantStaffResponseDTO> result =
                    staffRepository.listStaff(SCHEMA, null, null, null, "id", "asc", 0, 10);

            assertThat(result).hasSize(2);
            assertThat(result)
                    .filteredOn(r -> r.id() == withScheme)
                    .singleElement()
                    .satisfies(r -> assertThat(r.schemes()).extracting(SchemeSummaryDTO::schemeName)
                            .containsExactly("Beta Scheme"));
            assertThat(result)
                    .filteredOn(r -> r.id() == withoutScheme)
                    .singleElement()
                    .satisfies(r -> assertThat(r.schemes()).isEmpty());
        }

        @Test
        @DisplayName("listAllStaffForExport attaches schemes")
        void listAllStaffForExport_attachesSchemes() {
            long userId = insertUserReturningId("Export Me", "91XXXXXXXXX5", "export@example.com", 3, 1);
            mapUserToScheme(userId, insertScheme("Gamma Scheme", 1, 1));

            List<TenantStaffResponseDTO> result =
                    staffRepository.listAllStaffForExport(SCHEMA, null, null, null);

            assertThat(result).singleElement().satisfies(r ->
                    assertThat(r.schemes()).extracting(SchemeSummaryDTO::schemeName)
                            .containsExactly("Gamma Scheme"));
        }
    }

    // ── status labels ────────────────────────────────────────────────────────

    /**
     * Every code the tenant schema can hold must map to the label the rest of the platform
     * uses for it. The codes are fixed by scheme_master_table and shared with scheme-service:
     * work_status 1..4, operating_status 0..2.
     */
    @Nested
    @DisplayName("Scheme status labels")
    class StatusLabels {

        /** Each probe needs its own user: phone_number_hash and email are unique per tenant. */
        private int probeCount;

        private String workStatusLabelFor(int code) {
            return labelsFor(code, 1).workStatus();
        }

        private String operatingStatusLabelFor(int code) {
            return labelsFor(1, code).operatingStatus();
        }

        private SchemeSummaryDTO labelsFor(int workStatus, int operatingStatus) {
            int n = ++probeCount;
            long userId = insertUserReturningId(
                    "Status Probe " + n,
                    String.format("91XXXXXXX%03d", n),
                    "probe" + n + "@example.com",
                    3, 1);
            mapUserToScheme(userId, insertScheme("Probe Scheme " + n, workStatus, operatingStatus));

            Optional<TenantStaffResponseDTO> result = staffRepository.findStaffById(SCHEMA, userId);

            assertThat(result).isPresent();
            assertThat(result.get().schemes()).hasSize(1);
            return result.get().schemes().get(0);
        }

        @Test
        @DisplayName("maps every work_status code to its canonical label")
        void mapsEveryWorkStatusCode() {
            assertThat(workStatusLabelFor(1)).isEqualTo("Ongoing");
            assertThat(workStatusLabelFor(2)).isEqualTo("Completed");
            assertThat(workStatusLabelFor(3)).isEqualTo("Not Started");
            assertThat(workStatusLabelFor(4)).isEqualTo("Handed Over");
        }

        @Test
        @DisplayName("maps every operating_status code to its canonical label")
        void mapsEveryOperatingStatusCode() {
            assertThat(operatingStatusLabelFor(0)).isEqualTo("Non-Operative");
            assertThat(operatingStatusLabelFor(1)).isEqualTo("Operative");
            assertThat(operatingStatusLabelFor(2)).isEqualTo("Partially Operative");
        }

        @Test
        @DisplayName("falls back to Unknown for a code outside the documented range")
        void unknownForUnrecognisedCode() {
            assertThat(workStatusLabelFor(99)).isEqualTo("Unknown");
            assertThat(operatingStatusLabelFor(99)).isEqualTo("Unknown");
        }
    }
}
