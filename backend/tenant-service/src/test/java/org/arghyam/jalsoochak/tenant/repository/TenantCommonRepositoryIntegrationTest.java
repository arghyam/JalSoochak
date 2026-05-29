package org.arghyam.jalsoochak.tenant.repository;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.service.PiiEncryptionService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link TenantCommonRepository} against a real PostgreSQL
 * instance via Testcontainers.
 *
 * <p>Uses a dedicated init script ({@code sql/tenant-common-test-schema.sql}) that
 * provides the full column set required by the repository row-mappers, a stub
 * {@code create_tenant_schema()} PL/pgSQL function, and the partial unique index
 * needed by {@code upsertConfig}'s ON CONFLICT clause.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DisplayName("TenantCommonRepository Integration Tests")
class TenantCommonRepositoryIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/tenant-common-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockBean
    private TenantSchedulerManager tenantSchedulerManager;

    /** Suppress PII encryption startup — PiiEncryptionService requires env vars that are absent in tests. */
    @MockBean
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private TenantCommonRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM common_schema.tenant_config_master_table");
        jdbcTemplate.update("DELETE FROM common_schema.tenant_admin_user_master_table");
        // Keep the system tenant (id=0); remove only real tenants
        jdbcTemplate.update("DELETE FROM common_schema.tenant_master_table WHERE id != 0");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private TenantResponseDTO insertTenant(String stateCode, String title, TenantStatusEnum status) {
        Integer id = jdbcTemplate.queryForObject(
                "INSERT INTO common_schema.tenant_master_table (state_code, title, status, lgd_code) " +
                "VALUES (?, ?, ?, 1) RETURNING id",
                Integer.class, stateCode, title, status.getCode());
        return TenantResponseDTO.builder()
                .id(id)
                .stateCode(stateCode)
                .build();
    }

    private CreateTenantRequestDTO validCreateRequest(String stateCode) {
        return CreateTenantRequestDTO.builder()
                .stateCode(stateCode)
                .lgdCode(29)
                .name("Test State " + stateCode)
                .build();
    }

    // ── createTenant ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createTenant")
    class CreateTenant {

        @Test
        @DisplayName("returns tenant with correct fields after insert")
        void createTenant_returnsCorrectFields() {
            CreateTenantRequestDTO request = validCreateRequest("KA");

            Optional<TenantResponseDTO> result = repository.createTenant(request, 1);

            assertThat(result).isPresent();
            TenantResponseDTO tenant = result.get();
            assertThat(tenant.getStateCode()).isEqualTo("KA");
            assertThat(tenant.getName()).isEqualTo("Test State KA");
            assertThat(tenant.getLgdCode()).isEqualTo(29);
        }

        @Test
        @DisplayName("new tenant gets ONBOARDED status")
        void createTenant_hasOnboardedStatus() {
            Optional<TenantResponseDTO> result = repository.createTenant(validCreateRequest("MH"), 1);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(TenantStatusEnum.ONBOARDED.name());
        }

        @Test
        @DisplayName("assigns a non-null uuid to the new tenant")
        void createTenant_assignsUuid() {
            Optional<TenantResponseDTO> result = repository.createTenant(validCreateRequest("MP"), 1);

            assertThat(result).isPresent();
            assertThat(result.get().getUuid()).isNotBlank();
        }

        @Test
        @DisplayName("new tenant id is positive (not system tenant)")
        void createTenant_idIsPositive() {
            Optional<TenantResponseDTO> result = repository.createTenant(validCreateRequest("GJ"), 1);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isGreaterThan(0);
        }
    }

    // ── provisionTenantSchema ───────────────────────────────────────────────────

    @Nested
    @DisplayName("provisionTenantSchema")
    class ProvisionTenantSchema {

        @Test
        @DisplayName("succeeds with a valid lowercase schema name")
        void provisionTenantSchema_succeeds_withValidName() {
            // Uses the no-op stub function from init script — should not throw
            repository.provisionTenantSchema("tenant_ka");
        }

        @Test
        @DisplayName("throws for null schema name")
        void provisionTenantSchema_throwsOnNull() {
            assertThatThrownBy(() -> repository.provisionTenantSchema(null))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }

        @Test
        @DisplayName("throws for schema name with uppercase letters")
        void provisionTenantSchema_throwsOnUppercase() {
            assertThatThrownBy(() -> repository.provisionTenantSchema("Tenant_KA"))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws for schema name with SQL injection characters")
        void provisionTenantSchema_throwsOnSqlInjection() {
            assertThatThrownBy(() -> repository.provisionTenantSchema("tenant_ka; DROP TABLE users"))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws for schema name starting with a digit")
        void provisionTenantSchema_throwsOnDigitStart() {
            assertThatThrownBy(() -> repository.provisionTenantSchema("1tenant"))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── findByStateCode ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findByStateCode")
    class FindByStateCode {

        @Test
        @DisplayName("returns tenant when state code exists")
        void findByStateCode_returnsTenant_whenExists() {
            insertTenant("TN", "Tamil Nadu", TenantStatusEnum.ACTIVE);

            Optional<TenantResponseDTO> result = repository.findByStateCode("TN");

            assertThat(result).isPresent();
            assertThat(result.get().getStateCode()).isEqualTo("TN");
        }

        @Test
        @DisplayName("returns empty when state code does not exist")
        void findByStateCode_returnsEmpty_whenNotFound() {
            Optional<TenantResponseDTO> result = repository.findByStateCode("XX");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("excludes soft-deleted tenants")
        void findByStateCode_excludesSoftDeleted() {
            TenantResponseDTO t = insertTenant("AP", "Andhra Pradesh", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            Optional<TenantResponseDTO> result = repository.findByStateCode("AP");

            assertThat(result).isEmpty();
        }
    }

    // ── findAll() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findAll (no pagination)")
    class FindAll {

        @Test
        @DisplayName("returns all non-deleted tenants ordered by id")
        void findAll_returnsAllNonDeleted_orderedById() {
            insertTenant("TS", "Telangana", TenantStatusEnum.ACTIVE);
            insertTenant("RJ", "Rajasthan", TenantStatusEnum.ONBOARDED);

            List<TenantResponseDTO> result = repository.findAll();

            // id=0 (system tenant) is also included; actual tenants are in order
            assertThat(result).extracting(TenantResponseDTO::getStateCode)
                    .contains("TS", "RJ");
        }

        @Test
        @DisplayName("excludes soft-deleted tenants")
        void findAll_excludesSoftDeleted() {
            insertTenant("KL", "Kerala", TenantStatusEnum.ACTIVE);
            TenantResponseDTO t = insertTenant("OR", "Odisha", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            List<TenantResponseDTO> result = repository.findAll();

            assertThat(result).extracting(TenantResponseDTO::getStateCode)
                    .isNotEmpty()
                    .doesNotContain("OR");
        }
    }

    // ── findAll(limit, offset, status, search) ───────────────────────────────────

    @Nested
    @DisplayName("findAll (paginated)")
    class FindAllPaginated {

        @Test
        @DisplayName("returns page of results respecting limit and offset")
        void findAll_paginated_respectsLimitAndOffset() {
            insertTenant("S1", "State One", TenantStatusEnum.ACTIVE);
            insertTenant("S2", "State Two", TenantStatusEnum.ACTIVE);
            insertTenant("S3", "State Three", TenantStatusEnum.ACTIVE);

            List<TenantResponseDTO> page1 = repository.findAll(2, 0, null, null);
            List<TenantResponseDTO> page2 = repository.findAll(2, 2, null, null);

            assertThat(page1).hasSize(2);
            assertThat(page2).hasSize(1);
        }

        @Test
        @DisplayName("filters by status when status is provided")
        void findAll_paginated_filtersByStatus() {
            insertTenant("AC", "Active State", TenantStatusEnum.ACTIVE);
            insertTenant("IN", "Inactive State", TenantStatusEnum.INACTIVE);

            List<TenantResponseDTO> result = repository.findAll(10, 0, TenantStatusEnum.ACTIVE, null);

            assertThat(result).extracting(TenantResponseDTO::getStateCode)
                    .containsExactly("AC")
                    .doesNotContain("IN");
        }

        @Test
        @DisplayName("filters by name using case-insensitive partial match")
        void findAll_paginated_searchIsCaseInsensitive() {
            insertTenant("PB", "Punjab", TenantStatusEnum.ACTIVE);
            insertTenant("HR", "Haryana", TenantStatusEnum.ACTIVE);

            List<TenantResponseDTO> result = repository.findAll(10, 0, null, "pUNJaB");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStateCode()).isEqualTo("PB");
        }

        @Test
        @DisplayName("search with LIKE special characters does not throw")
        void findAll_paginated_escapesSearchSpecialChars() {
            insertTenant("UK", "Uttara%khand", TenantStatusEnum.ACTIVE);

            // If % were not escaped this would match all tenants; with escaping it only
            // matches the literal title containing '%'
            List<TenantResponseDTO> result = repository.findAll(10, 0, null, "%");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStateCode()).isEqualTo("UK");
        }

        @Test
        @DisplayName("excludes the system tenant (id=0)")
        void findAll_paginated_excludesSystemTenant() {
            // No real tenants inserted — only the system tenant with id=0 exists
            List<TenantResponseDTO> result = repository.findAll(10, 0, null, null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("excludes soft-deleted tenants")
        void findAll_paginated_excludesSoftDeleted() {
            insertTenant("KL", "Kerala", TenantStatusEnum.ACTIVE);
            TenantResponseDTO t = insertTenant("SK", "Sikkim", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            List<TenantResponseDTO> result = repository.findAll(10, 0, null, null);

            assertThat(result).extracting(TenantResponseDTO::getStateCode)
                    .isNotEmpty()
                    .doesNotContain("SK");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for limit <= 0")
        void findAll_paginated_throwsForNonPositiveLimit() {
            assertThatThrownBy(() -> repository.findAll(0, 0, null, null))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("limit must be greater than 0");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for negative offset")
        void findAll_paginated_throwsForNegativeOffset() {
            assertThatThrownBy(() -> repository.findAll(10, -1, null, null))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("offset must be non-negative");
        }

        @Test
        @DisplayName("blank search string is treated as no filter")
        void findAll_paginated_blankSearchMatchesAll() {
            insertTenant("MN", "Manipur", TenantStatusEnum.ACTIVE);
            insertTenant("TR", "Tripura", TenantStatusEnum.ACTIVE);

            List<TenantResponseDTO> result = repository.findAll(10, 0, null, "   ");

            assertThat(result).hasSize(2);
        }
    }

    // ── countAllTenants ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("countAllTenants")
    class CountAllTenants {

        @Test
        @DisplayName("returns zero when no real tenants exist")
        void countAllTenants_returnsZero_whenNoTenants() {
            long count = repository.countAllTenants(null, null);

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("counts all non-deleted, non-system tenants")
        void countAllTenants_returnsCorrectTotal() {
            insertTenant("C1", "Count One", TenantStatusEnum.ACTIVE);
            insertTenant("C2", "Count Two", TenantStatusEnum.ACTIVE);

            long count = repository.countAllTenants(null, null);

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("filters by status when provided")
        void countAllTenants_filtersByStatus() {
            insertTenant("FA", "Filter Active", TenantStatusEnum.ACTIVE);
            insertTenant("FO", "Filter Onboarded", TenantStatusEnum.ONBOARDED);

            long count = repository.countAllTenants(TenantStatusEnum.ACTIVE, null);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("excludes soft-deleted tenants from count")
        void countAllTenants_excludesSoftDeleted() {
            TenantResponseDTO t = insertTenant("DEL", "To Delete", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            long count = repository.countAllTenants(null, null);

            assertThat(count).isZero();
        }
    }

    // ── getTenantSummary ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getTenantSummary")
    class GetTenantSummary {

        @Test
        @DisplayName("returns zero counts when no real tenants exist")
        void getTenantSummary_returnsZeros_whenEmpty() {
            TenantSummaryResponseDTO summary = repository.getTenantSummary();

            assertThat(summary.getTotalTenants()).isZero();
            assertThat(summary.getActiveTenants()).isZero();
        }

        @Test
        @DisplayName("counts each status bucket correctly")
        void getTenantSummary_countsEachStatus() {
            insertTenant("SA1", "Active 1", TenantStatusEnum.ACTIVE);
            insertTenant("SA2", "Active 2", TenantStatusEnum.ACTIVE);
            insertTenant("SO1", "Onboarded 1", TenantStatusEnum.ONBOARDED);
            insertTenant("SS1", "Suspended 1", TenantStatusEnum.SUSPENDED);

            TenantSummaryResponseDTO summary = repository.getTenantSummary();

            assertThat(summary.getTotalTenants()).isEqualTo(4);
            assertThat(summary.getActiveTenants()).isEqualTo(2);
            assertThat(summary.getOnboardedTenants()).isEqualTo(1);
            assertThat(summary.getSuspendedTenants()).isEqualTo(1);
        }

        @Test
        @DisplayName("excludes soft-deleted tenants from summary")
        void getTenantSummary_excludesSoftDeleted() {
            TenantResponseDTO t = insertTenant("SX", "Soft Deleted", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            TenantSummaryResponseDTO summary = repository.getTenantSummary();

            assertThat(summary.getTotalTenants()).isZero();
        }
    }

    // ── findById ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("returns tenant when id exists")
        void findById_returnsTenant_whenExists() {
            TenantResponseDTO inserted = insertTenant("BI", "By Id", TenantStatusEnum.ACTIVE);

            Optional<TenantResponseDTO> result = repository.findById(inserted.getId());

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(inserted.getId());
        }

        @Test
        @DisplayName("returns empty for non-existent id")
        void findById_returnsEmpty_whenNotFound() {
            Optional<TenantResponseDTO> result = repository.findById(99999);

            assertThat(result).isEmpty();
        }
    }

    // ── findTenantStatusByTenantId ───────────────────────────────────────────────

    @Nested
    @DisplayName("findTenantStatusByTenantId")
    class FindTenantStatusByTenantId {

        @Test
        @DisplayName("returns status code for existing tenant")
        void findTenantStatusByTenantId_returnsStatus() {
            TenantResponseDTO t = insertTenant("ST", "Status Test", TenantStatusEnum.ACTIVE);

            Optional<Integer> result = repository.findTenantStatusByTenantId(t.getId());

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(TenantStatusEnum.ACTIVE.getCode());
        }

        @Test
        @DisplayName("returns empty for non-existent tenant id")
        void findTenantStatusByTenantId_returnsEmpty_whenNotFound() {
            Optional<Integer> result = repository.findTenantStatusByTenantId(99999);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("excludes soft-deleted tenants")
        void findTenantStatusByTenantId_excludesSoftDeleted() {
            TenantResponseDTO t = insertTenant("SD", "Soft Del Status", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            Optional<Integer> result = repository.findTenantStatusByTenantId(t.getId());

            assertThat(result).isEmpty();
        }
    }

    // ── updateTenant ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateTenant")
    class UpdateTenant {

        @Test
        @DisplayName("updates status and returns updated tenant")
        void updateTenant_updatesStatus() {
            TenantResponseDTO t = insertTenant("UT", "Update Test", TenantStatusEnum.ONBOARDED);
            UpdateTenantRequestDTO request = UpdateTenantRequestDTO.builder()
                    .status("ACTIVE")
                    .build();

            Optional<TenantResponseDTO> result = repository.updateTenant(t.getId(), request, 1);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(TenantStatusEnum.ACTIVE.name());
        }

        @Test
        @DisplayName("updates without status change when status is null")
        void updateTenant_updatesWithoutStatusChange_whenStatusNull() {
            TenantResponseDTO t = insertTenant("UN", "Update No Status", TenantStatusEnum.ONBOARDED);
            UpdateTenantRequestDTO request = UpdateTenantRequestDTO.builder()
                    .status(null)
                    .build();

            Optional<TenantResponseDTO> result = repository.updateTenant(t.getId(), request, 1);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(TenantStatusEnum.ONBOARDED.name());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unknown status value")
        void updateTenant_throwsForInvalidStatus() {
            TenantResponseDTO t = insertTenant("UI", "Invalid Status", TenantStatusEnum.ONBOARDED);
            UpdateTenantRequestDTO request = UpdateTenantRequestDTO.builder()
                    .status("UNKNOWN_STATUS")
                    .build();

            assertThatThrownBy(() -> repository.updateTenant(t.getId(), request, 1))
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid tenant status");
        }
    }

    // ── updateTenantStatus ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateTenantStatus")
    class UpdateTenantStatus {

        @Test
        @DisplayName("updates tenant status in place")
        void updateTenantStatus_updatesStatus() {
            TenantResponseDTO t = insertTenant("US", "Status Update", TenantStatusEnum.ONBOARDED);

            repository.updateTenantStatus(t.getId(), TenantStatusEnum.ACTIVE, 1);

            Integer storedStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM common_schema.tenant_master_table WHERE id = ?",
                    Integer.class, t.getId());
            assertThat(storedStatus).isEqualTo(TenantStatusEnum.ACTIVE.getCode());
        }

        @Test
        @DisplayName("throws IllegalStateException when tenant does not exist")
        void updateTenantStatus_throwsWhenTenantNotFound() {
            assertThatThrownBy(() -> repository.updateTenantStatus(99999, TenantStatusEnum.ACTIVE, 1))
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No tenant found");
        }
    }

    // ── deactivateTenant ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivateTenant")
    class DeactivateTenant {

        @Test
        @DisplayName("sets tenant status to INACTIVE")
        void deactivateTenant_setsInactiveStatus() {
            TenantResponseDTO t = insertTenant("DT", "Deactivate Test", TenantStatusEnum.ACTIVE);

            repository.deactivateTenant(t.getId(), 1);

            Integer status = jdbcTemplate.queryForObject(
                    "SELECT status FROM common_schema.tenant_master_table WHERE id = ?",
                    Integer.class, t.getId());
            assertThat(status).isEqualTo(TenantStatusEnum.INACTIVE.getCode());
        }

        @Test
        @DisplayName("throws IllegalArgumentException when tenant does not exist")
        void deactivateTenant_throwsWhenTenantNotFound() {
            assertThatThrownBy(() -> repository.deactivateTenant(99999, 1))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    // ── findUserIdByUuid ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findUserIdByUuid")
    class FindUserIdByUuid {

        @Test
        @DisplayName("returns user id when uuid exists")
        void findUserIdByUuid_returnsId_whenExists() {
            String uuid = "test-uuid-1234";
            Integer inserted = jdbcTemplate.queryForObject(
                    "INSERT INTO common_schema.tenant_admin_user_master_table (uuid) VALUES (?) RETURNING id",
                    Integer.class, uuid);

            Optional<Integer> result = repository.findUserIdByUuid(uuid);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(inserted);
        }

        @Test
        @DisplayName("returns empty for non-existent uuid")
        void findUserIdByUuid_returnsEmpty_whenNotFound() {
            Optional<Integer> result = repository.findUserIdByUuid("no-such-uuid");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null uuid without querying the database")
        void findUserIdByUuid_returnsEmpty_forNull() {
            Optional<Integer> result = repository.findUserIdByUuid(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty for blank uuid without querying the database")
        void findUserIdByUuid_returnsEmpty_forBlank() {
            Optional<Integer> result = repository.findUserIdByUuid("   ");

            assertThat(result).isEmpty();
        }
    }

    // ── findConfigsByTenantId ────────────────────────────────────────────────────

    @Nested
    @DisplayName("findConfigsByTenantId")
    class FindConfigsByTenantId {

        @Test
        @DisplayName("returns all non-deleted configs for a tenant")
        void findConfigsByTenantId_returnsConfigs() {
            TenantResponseDTO t = insertTenant("CF", "Config Test", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES (?,?,?)",
                    t.getId(), "KEY_ONE", "val1");
            jdbcTemplate.update(
                    "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES (?,?,?)",
                    t.getId(), "KEY_TWO", "val2");

            List<ConfigDTO> result = repository.findConfigsByTenantId(t.getId());

            assertThat(result).hasSize(2);
            assertThat(result).extracting(ConfigDTO::getConfigKey)
                    .containsExactlyInAnyOrder("KEY_ONE", "KEY_TWO");
        }

        @Test
        @DisplayName("excludes soft-deleted configs")
        void findConfigsByTenantId_excludesDeleted() {
            TenantResponseDTO t = insertTenant("CX", "Config Del", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value, deleted_at) VALUES (?,?,?,NOW())",
                    t.getId(), "DELETED_KEY", "val");

            List<ConfigDTO> result = repository.findConfigsByTenantId(t.getId());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when no configs exist for tenant")
        void findConfigsByTenantId_returnsEmpty_whenNoConfigs() {
            TenantResponseDTO t = insertTenant("CE", "Config Empty", TenantStatusEnum.ACTIVE);

            List<ConfigDTO> result = repository.findConfigsByTenantId(t.getId());

            assertThat(result).isEmpty();
        }
    }

    // ── findConfigByTenantAndKey ─────────────────────────────────────────────────

    @Nested
    @DisplayName("findConfigByTenantAndKey")
    class FindConfigByTenantAndKey {

        @Test
        @DisplayName("returns config when tenant and key match")
        void findConfigByTenantAndKey_returnsConfig() {
            TenantResponseDTO t = insertTenant("CK", "Config Key", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES (?,?,?)",
                    t.getId(), "MY_KEY", "my_value");

            Optional<ConfigDTO> result = repository.findConfigByTenantAndKey(t.getId(), "MY_KEY");

            assertThat(result).isPresent();
            assertThat(result.get().getConfigKey()).isEqualTo("MY_KEY");
            assertThat(result.get().getConfigValue()).isEqualTo("my_value");
        }

        @Test
        @DisplayName("returns empty for non-existent key")
        void findConfigByTenantAndKey_returnsEmpty_whenKeyNotFound() {
            TenantResponseDTO t = insertTenant("CM", "Config Miss", TenantStatusEnum.ACTIVE);

            Optional<ConfigDTO> result = repository.findConfigByTenantAndKey(t.getId(), "MISSING_KEY");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("excludes soft-deleted config entry")
        void findConfigByTenantAndKey_excludesDeleted() {
            TenantResponseDTO t = insertTenant("CD", "Config Del Key", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value, deleted_at) VALUES (?,?,?,NOW())",
                    t.getId(), "DEL_KEY", "val");

            Optional<ConfigDTO> result = repository.findConfigByTenantAndKey(t.getId(), "DEL_KEY");

            assertThat(result).isEmpty();
        }
    }

    // ── upsertConfig ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("upsertConfig")
    class UpsertConfig {

        @Test
        @DisplayName("inserts a new config row when key does not exist")
        void upsertConfig_insertsNewRow() {
            TenantResponseDTO t = insertTenant("UC", "Upsert Config", TenantStatusEnum.ACTIVE);

            Optional<ConfigDTO> result = repository.upsertConfig(t.getId(), "NEW_KEY", "new_val", 1);

            assertThat(result).isPresent();
            assertThat(result.get().getConfigKey()).isEqualTo("NEW_KEY");
            assertThat(result.get().getConfigValue()).isEqualTo("new_val");
        }

        @Test
        @DisplayName("updates existing config value on conflict")
        void upsertConfig_updatesExistingRow() {
            TenantResponseDTO t = insertTenant("UU", "Upsert Update", TenantStatusEnum.ACTIVE);
            repository.upsertConfig(t.getId(), "EXISTING_KEY", "original", 1);

            Optional<ConfigDTO> result = repository.upsertConfig(t.getId(), "EXISTING_KEY", "updated", 1);

            assertThat(result).isPresent();
            assertThat(result.get().getConfigValue()).isEqualTo("updated");
        }

        @Test
        @DisplayName("upsert produces exactly one row in the database")
        void upsertConfig_doesNotDuplicateRows() {
            TenantResponseDTO t = insertTenant("UD", "Upsert Dedup", TenantStatusEnum.ACTIVE);
            repository.upsertConfig(t.getId(), "DEDUP_KEY", "v1", 1);
            repository.upsertConfig(t.getId(), "DEDUP_KEY", "v2", 1);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.tenant_config_master_table WHERE tenant_id = ? AND config_key = ?",
                    Integer.class, t.getId(), "DEDUP_KEY");
            assertThat(count).isEqualTo(1);
        }
    }

    // ── countNonDeletedTenants ───────────────────────────────────────────────────

    @Nested
    @DisplayName("countNonDeletedTenants")
    class CountNonDeletedTenants {

        @Test
        @DisplayName("returns zero when no real tenants exist")
        void countNonDeletedTenants_returnsZero_whenEmpty() {
            assertThat(repository.countNonDeletedTenants()).isZero();
        }

        @Test
        @DisplayName("returns correct count of real tenants")
        void countNonDeletedTenants_returnsCorrectCount() {
            insertTenant("N1", "Non Del 1", TenantStatusEnum.ACTIVE);
            insertTenant("N2", "Non Del 2", TenantStatusEnum.ACTIVE);

            assertThat(repository.countNonDeletedTenants()).isEqualTo(2);
        }

        @Test
        @DisplayName("excludes system tenant (id=0) from count")
        void countNonDeletedTenants_excludesSystemTenant() {
            // Only the system tenant exists; should return 0
            assertThat(repository.countNonDeletedTenants()).isZero();
        }

        @Test
        @DisplayName("excludes soft-deleted tenants from count")
        void countNonDeletedTenants_excludesSoftDeleted() {
            TenantResponseDTO t = insertTenant("NS", "Soft Del", TenantStatusEnum.ACTIVE);
            jdbcTemplate.update(
                    "UPDATE common_schema.tenant_master_table SET deleted_at = NOW() WHERE id = ?",
                    t.getId());

            assertThat(repository.countNonDeletedTenants()).isZero();
        }
    }

    @Nested
    @DisplayName("upsertApiKeyHash / findByApiKeyHash")
    class ApiKeyHashTests {

        @Test
        @DisplayName("stores hash and retrieves tenant by hash")
        void upsertAndFind_roundTrip() {
            TenantResponseDTO tenant = insertTenant("AK", "API Key Tenant", TenantStatusEnum.ACTIVE);
            String hash = "a".repeat(64);

            repository.upsertApiKeyHash(tenant.getId(), hash, null);

            java.util.Optional<TenantResponseDTO> found = repository.findByApiKeyHash(hash);
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(tenant.getId());
        }

        @Test
        @DisplayName("overwriting hash invalidates old token lookup")
        void upsertApiKeyHash_overwritesPreviousHash() {
            TenantResponseDTO tenant = insertTenant("AK2", "API Key Tenant 2", TenantStatusEnum.ACTIVE);
            String oldHash = "b".repeat(64);
            String newHash = "c".repeat(64);

            repository.upsertApiKeyHash(tenant.getId(), oldHash, null);
            repository.upsertApiKeyHash(tenant.getId(), newHash, null);

            assertThat(repository.findByApiKeyHash(oldHash)).isEmpty();
            assertThat(repository.findByApiKeyHash(newHash)).isPresent();
        }

        @Test
        @DisplayName("returns empty for unknown hash")
        void findByApiKeyHash_unknownHash_returnsEmpty() {
            assertThat(repository.findByApiKeyHash("d".repeat(64))).isEmpty();
        }

        @Test
        @DisplayName("throws when tenant does not exist")
        void upsertApiKeyHash_nonExistentTenant_throws() {
            assertThatThrownBy(() -> repository.upsertApiKeyHash(99999, "e".repeat(64), null))
                    .isInstanceOf(EmptyResultDataAccessException.class);
        }
    }
}
