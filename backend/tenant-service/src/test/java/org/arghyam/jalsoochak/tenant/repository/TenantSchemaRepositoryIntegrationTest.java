package org.arghyam.jalsoochak.tenant.repository;

import org.arghyam.jalsoochak.tenant.dto.internal.LanguageConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelNameDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.RegionTypeEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.LocationHierarchyStructureLockedException;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link TenantSchemaRepository} SQL queries against a real
 * PostgreSQL instance via Testcontainers.
 *
 * <p>Covers: language management, location hierarchy CRUD,
 * seeded-data guard, and advisory-lock-based structural rewrite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DisplayName("TenantSchemaRepository Integration Tests")
class TenantSchemaRepositoryIntegrationTest {

    private static final String SCHEMA = "tenant_test";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Suppress startup side-effects not under test
    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockBean
    private TenantSchedulerManager tenantSchedulerManager;

    @MockBean
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private TenantSchemaRepository tenantSchemaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanupTables() {
        jdbcTemplate.update("DELETE FROM tenant_test.language_master_table");
        jdbcTemplate.update("DELETE FROM tenant_test.location_config_master_table");
        jdbcTemplate.update("DELETE FROM tenant_test.lgd_location_master_table");
        jdbcTemplate.update("DELETE FROM tenant_test.department_location_master_table");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Builds a two-level LGD hierarchy using the canonical DTO structure. */
    private List<LocationLevelConfigDTO> twoLevelHierarchy() {
        return List.of(
                LocationLevelConfigDTO.builder()
                        .level(1)
                        .levelName(List.of(LocationLevelNameDTO.builder()
                                .languageId(1).title("State").build()))
                        .build(),
                LocationLevelConfigDTO.builder()
                        .level(2)
                        .levelName(List.of(LocationLevelNameDTO.builder()
                                .languageId(1).title("District").build()))
                        .build());
    }

    // ── schema name validation ───────────────────────────────────────────────────
    // Note: TenantSchemaRepository is a Spring @Repository bean, so the JPA
    // PersistenceExceptionTranslator wraps IllegalArgumentException in
    // InvalidDataAccessApiUsageException. Tests assert on the root cause.

    @Nested
    @DisplayName("Schema name validation")
    class SchemaNameValidation {

        @Test
        @DisplayName("throws for null schema name with descriptive message")
        void throwsOnNullSchemaName() {
            assertThatThrownBy(() -> tenantSchemaRepository.getSupportedLanguages(null))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }

        @Test
        @DisplayName("throws for schema without tenant_ prefix")
        void throwsOnSchemaMissingPrefix() {
            assertThatThrownBy(() -> tenantSchemaRepository.getSupportedLanguages("common_schema"))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws for schema with uppercase letters")
        void throwsOnSchemaWithUppercase() {
            assertThatThrownBy(() -> tenantSchemaRepository.getSupportedLanguages("Tenant_Mp"))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws for schema with SQL injection attempt")
        void throwsOnSqlInjectionAttempt() {
            assertThatThrownBy(() ->
                    tenantSchemaRepository.getSupportedLanguages("tenant_mp; DROP TABLE users"))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── language management ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Language management")
    class LanguageManagement {

        @Test
        @DisplayName("getSupportedLanguages returns empty list when table is empty")
        void getSupportedLanguages_returnsEmpty_whenNoRows() {
            List<LanguageConfigDTO> result = tenantSchemaRepository.getSupportedLanguages(SCHEMA);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getSupportedLanguages returns only active languages ordered by preference")
        void getSupportedLanguages_returnsActiveLanguages_orderedByPreference() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.language_master_table (language_name, preference, status) VALUES (?, ?, ?)",
                    "Hindi", 1, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.language_master_table (language_name, preference, status) VALUES (?, ?, ?)",
                    "English", 2, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.language_master_table (language_name, preference, status) VALUES (?, ?, ?)",
                    "Telugu", 3, 0); // inactive – must be excluded

            List<LanguageConfigDTO> result = tenantSchemaRepository.getSupportedLanguages(SCHEMA);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getLanguage()).isEqualTo("Hindi");
            assertThat(result.get(1).getLanguage()).isEqualTo("English");
        }

        @Test
        @DisplayName("setSupportedLanguages deactivates existing rows and upserts new ones")
        void setSupportedLanguages_deactivatesOldAndUpsertsNew() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.language_master_table (language_name, preference, status) VALUES (?, ?, ?)",
                    "OldLanguage", 1, 1);

            List<LanguageConfigDTO> newLanguages = List.of(
                    LanguageConfigDTO.builder().language("Hindi").preference(1).build(),
                    LanguageConfigDTO.builder().language("English").preference(2).build());

            tenantSchemaRepository.setSupportedLanguages(SCHEMA, newLanguages, 99);

            List<LanguageConfigDTO> result = tenantSchemaRepository.getSupportedLanguages(SCHEMA);
            assertThat(result).hasSize(2);
            assertThat(result).extracting(LanguageConfigDTO::getLanguage)
                    .containsExactlyInAnyOrder("Hindi", "English");

            Integer oldStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM tenant_test.language_master_table WHERE language_name = 'OldLanguage'",
                    Integer.class);
            assertThat(oldStatus).isEqualTo(0); // deactivated
        }

        @Test
        @DisplayName("setSupportedLanguages with null list throws InvalidConfigValueException")
        void setSupportedLanguages_throwsOnNullList() {
            assertThatThrownBy(() -> tenantSchemaRepository.setSupportedLanguages(SCHEMA, null, 1))
                    .isInstanceOf(InvalidConfigValueException.class);
        }
    }

    // ── location hierarchy ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Location hierarchy")
    class LocationHierarchy {

        @Test
        @DisplayName("getLocationHierarchy returns empty when no rows exist for region type")
        void getLocationHierarchy_returnsEmpty_whenNoRows() {
            LocationConfigDTO result = tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD);

            assertThat(result.getLocationHierarchy()).isEmpty();
        }

        @Test
        @DisplayName("setLocationHierarchy inserts rows and getLocationHierarchy retrieves them")
        void setAndGetLocationHierarchy_roundTrip() {
            tenantSchemaRepository.setLocationHierarchy(SCHEMA, RegionTypeEnum.LGD, twoLevelHierarchy(), 1);

            LocationConfigDTO result = tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD);

            assertThat(result.getLocationHierarchy()).hasSize(2);
            assertThat(result.getLocationHierarchy().get(0).getLevel()).isEqualTo(1);
            assertThat(result.getLocationHierarchy().get(1).getLevel()).isEqualTo(2);
        }

        @Test
        @DisplayName("setLocationHierarchy deletes existing rows before inserting new ones")
        void setLocationHierarchy_replacesExistingRows() {
            tenantSchemaRepository.setLocationHierarchy(SCHEMA, RegionTypeEnum.LGD, twoLevelHierarchy(), 1);

            List<LocationLevelConfigDTO> singleLevel = List.of(
                    LocationLevelConfigDTO.builder()
                            .level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder()
                                    .languageId(1).title("State").build()))
                            .build());
            tenantSchemaRepository.setLocationHierarchy(SCHEMA, RegionTypeEnum.LGD, singleLevel, 1);

            LocationConfigDTO result = tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD);
            assertThat(result.getLocationHierarchy()).hasSize(1);
        }

        @Test
        @DisplayName("setLocationHierarchy does not affect rows for a different region type")
        void setLocationHierarchy_isolatedByRegionType() {
            tenantSchemaRepository.setLocationHierarchy(SCHEMA, RegionTypeEnum.LGD, twoLevelHierarchy(), 1);
            tenantSchemaRepository.setLocationHierarchy(SCHEMA, RegionTypeEnum.DEPARTMENT, twoLevelHierarchy(), 1);

            assertThat(tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD)
                    .getLocationHierarchy()).hasSize(2);
            assertThat(tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.DEPARTMENT)
                    .getLocationHierarchy()).hasSize(2);
        }

        @Test
        @DisplayName("setLocationHierarchy with null hierarchy throws InvalidConfigValueException")
        void setLocationHierarchy_throwsOnNullHierarchy() {
            assertThatThrownBy(() ->
                    tenantSchemaRepository.setLocationHierarchy(SCHEMA, RegionTypeEnum.LGD, null, 1))
                    .isInstanceOf(InvalidConfigValueException.class);
        }
    }

    // ── seeded data guard ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Seeded location data guard")
    class SeededDataGuard {

        @Test
        @DisplayName("countSeededLocationData returns 0 when table is empty")
        void countSeededLocationData_returnsZero_whenEmpty() {
            long count = tenantSchemaRepository.countSeededLocationData(SCHEMA, RegionTypeEnum.LGD);

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("countSeededLocationData returns correct row count after insert")
        void countSeededLocationData_returnsCount_afterInsert() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, status) VALUES (?, ?, ?)",
                    "Test State", "01", 1);

            long count = tenantSchemaRepository.countSeededLocationData(SCHEMA, RegionTypeEnum.LGD);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("rewriteLocationHierarchyIfNoSeededData sets hierarchy when master table is empty")
        void rewriteIfNoSeededData_succeedsWhenEmpty() {
            List<LocationLevelConfigDTO> hierarchy = List.of(
                    LocationLevelConfigDTO.builder()
                            .level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder()
                                    .languageId(1).title("State").build()))
                            .build());

            tenantSchemaRepository.rewriteLocationHierarchyIfNoSeededData(
                    SCHEMA, RegionTypeEnum.LGD, hierarchy, 1);

            assertThat(tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD)
                    .getLocationHierarchy()).hasSize(1);
        }

        @Test
        @DisplayName("rewriteLocationHierarchyIfNoSeededData throws when seeded rows exist")
        void rewriteIfNoSeededData_throwsWhenSeededRowsExist() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, status) VALUES (?, ?, ?)",
                    "Test State", "01", 1);

            List<LocationLevelConfigDTO> hierarchy = List.of(
                    LocationLevelConfigDTO.builder()
                            .level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder()
                                    .languageId(1).title("State").build()))
                            .build());

            assertThatThrownBy(() ->
                    tenantSchemaRepository.rewriteLocationHierarchyIfNoSeededData(
                            SCHEMA, RegionTypeEnum.LGD, hierarchy, 1))
                    .isInstanceOf(LocationHierarchyStructureLockedException.class);
        }
    }

    // ── updateLevelNames ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateLevelNames")
    class UpdateLevelNames {

        @Test
        @DisplayName("updates level_name for an existing level")
        void updateLevelNames_updatesExistingLevel() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.location_config_master_table (region_type, level, level_name) VALUES (?, ?, ?::jsonb)",
                    RegionTypeEnum.LGD.getCode(), 1,
                    "[{\"languageId\":1,\"title\":\"State\"}]");

            List<LocationLevelConfigDTO> update = List.of(
                    LocationLevelConfigDTO.builder()
                            .level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder()
                                    .languageId(2).title("राज्य").build()))
                            .build());

            tenantSchemaRepository.updateLevelNames(SCHEMA, RegionTypeEnum.LGD, update, 1);

            LocationConfigDTO result = tenantSchemaRepository.getLocationHierarchy(SCHEMA, RegionTypeEnum.LGD);
            assertThat(result.getLocationHierarchy()).hasSize(1);
            assertThat(result.getLocationHierarchy().get(0).getLevelName().get(0).getTitle())
                    .isEqualTo("राज्य");
        }

        @Test
        @DisplayName("throws InvalidConfigValueException when level does not exist")
        void updateLevelNames_throwsWhenLevelNotFound() {
            List<LocationLevelConfigDTO> update = List.of(
                    LocationLevelConfigDTO.builder()
                            .level(99)
                            .levelName(List.of(LocationLevelNameDTO.builder()
                                    .languageId(1).title("Nonexistent").build()))
                            .build());

            assertThatThrownBy(() ->
                    tenantSchemaRepository.updateLevelNames(SCHEMA, RegionTypeEnum.LGD, update, 1))
                    .isInstanceOf(InvalidConfigValueException.class);
        }

        @Test
        @DisplayName("throws InvalidConfigValueException for null hierarchy")
        void updateLevelNames_throwsOnNullHierarchy() {
            assertThatThrownBy(() ->
                    tenantSchemaRepository.updateLevelNames(SCHEMA, RegionTypeEnum.LGD, null, 1))
                    .isInstanceOf(InvalidConfigValueException.class);
        }
    }

    // ── location lookups ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Location lookups")
    class LocationLookups {

        @Test
        @DisplayName("findLgdLocationsByParentId returns root locations when parentId is null")
        void findLgdLocationsByParentId_returnsRootLocations_whenParentNull() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, parent_id, status) VALUES (?, ?, ?, ?)",
                    "State A", "01", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, parent_id, status) VALUES (?, ?, ?, ?)",
                    "State B", "02", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, parent_id, status, deleted_at) VALUES (?, ?, ?, ?, NOW())",
                    "Deleted State", "03", null, 1); // soft-deleted – must be excluded

            List<LocationResponseDTO> result =
                    tenantSchemaRepository.findLgdLocationsByParentId(SCHEMA, null);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(LocationResponseDTO::getTitle)
                    .containsExactly("State A", "State B"); // ordered by title
        }

        @Test
        @DisplayName("findLgdLocationsByParentId returns active children for given parent id")
        void findLgdLocationsByParentId_returnsActiveChildren_forGivenParent() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (id, title, lgd_code, parent_id, status) VALUES (?, ?, ?, ?, ?)",
                    100, "Parent State", "01", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, parent_id, status) VALUES (?, ?, ?, ?)",
                    "Child District", "02", 100, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, parent_id, status) VALUES (?, ?, ?, ?)",
                    "Inactive District", "03", 100, 0); // inactive – must be excluded
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.lgd_location_master_table (title, lgd_code, parent_id, status, deleted_at) VALUES (?, ?, ?, ?, NOW())",
                    "Deleted District", "04", 100, 1); // soft-deleted – must be excluded

            List<LocationResponseDTO> result =
                    tenantSchemaRepository.findLgdLocationsByParentId(SCHEMA, 100);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Child District");
        }

        @Test
        @DisplayName("findDepartmentLocationsByParentId returns root locations when parentId is null")
        void findDepartmentLocationsByParentId_returnsRootLocations_whenParentNull() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status) VALUES (?, ?, ?)",
                    "Zone A", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status) VALUES (?, ?, ?)",
                    "Zone B", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status, deleted_at) VALUES (?, ?, ?, NOW())",
                    "Deleted Zone", null, 1); // soft-deleted – must be excluded

            List<LocationResponseDTO> result =
                    tenantSchemaRepository.findDepartmentLocationsByParentId(SCHEMA, null);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(LocationResponseDTO::getTitle)
                    .containsExactly("Zone A", "Zone B"); // ordered by title
        }

        @Test
        @DisplayName("findDepartmentLocationsByParentId excludes inactive records")
        void findDepartmentLocationsByParentId_excludesInactiveRecords() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status) VALUES (?, ?, ?)",
                    "Active Zone", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status) VALUES (?, ?, ?)",
                    "Inactive Zone", null, 0);

            List<LocationResponseDTO> result =
                    tenantSchemaRepository.findDepartmentLocationsByParentId(SCHEMA, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Active Zone");
        }

        @Test
        @DisplayName("findDepartmentLocationsByParentId excludes soft-deleted records for given parent id")
        void findDepartmentLocationsByParentId_excludesDeletedRecords_forGivenParent() {
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (id, title, parent_id, status) VALUES (?, ?, ?, ?)",
                    200, "Parent Zone", null, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status) VALUES (?, ?, ?)",
                    "Child Circle", 200, 1);
            jdbcTemplate.update(
                    "INSERT INTO tenant_test.department_location_master_table (title, parent_id, status, deleted_at) VALUES (?, ?, ?, NOW())",
                    "Deleted Circle", 200, 1); // soft-deleted – must be excluded

            List<LocationResponseDTO> result =
                    tenantSchemaRepository.findDepartmentLocationsByParentId(SCHEMA, 200);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Child Circle");
        }
    }
}
