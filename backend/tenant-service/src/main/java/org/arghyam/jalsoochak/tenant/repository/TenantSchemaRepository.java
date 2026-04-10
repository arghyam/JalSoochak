package org.arghyam.jalsoochak.tenant.repository;

import java.util.List;

import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LanguageConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelNameDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationConfigDTO;
import org.arghyam.jalsoochak.tenant.enums.RegionTypeEnum;
import org.arghyam.jalsoochak.tenant.enums.StatusEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.LocationHierarchyStructureLockedException;

import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * Repository for tenant-schema-scoped queries.
 *
 * <p>Every method accepts the target schema name explicitly so that the caller (service layer)
 * controls which tenant's data is accessed.</p>
 *
 * <p><b>Security note (java:S2077):</b> PostgreSQL schema and table names are SQL
 * <em>identifiers</em>, not values, so they cannot be supplied via JDBC bind parameters
 * ({@code ?}). Every public method calls {@link #validateSchemaName(String)} before any
 * schema name is interpolated via {@code String.format}; the regex
 * {@code ^tenant_[a-z0-9][a-z0-9_]{0,29}$} ensures only safe characters reach the query.
 * Table names are resolved through {@link #locationTableFor(RegionTypeEnum)}, an exhaustive
 * switch over a closed enum — no user input can affect the table name.
 * All runtime data values are bound as {@code ?} parameters.</p>
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class TenantSchemaRepository {

        private final JdbcTemplate jdbcTemplate;
        private final ObjectMapper objectMapper;

        /**
         * Row mapper for {@code tenant_schema.lgd_location_master_table}.
         */
        private static final RowMapper<LocationResponseDTO> LGD_LOCATION_ROW_MAPPER = (rs,
                        rowNum) -> LocationResponseDTO.builder()
                                        .id(rs.getInt("id"))
                                        .uuid(rs.getString("uuid"))
                                        .title(rs.getString("title"))
                                        .lgdCode(rs.getString("lgd_code"))
                                        .parentId((Integer) rs.getObject("parent_id"))
                                        .status(rs.getInt("status"))
                                        .build();

        /**
         * Row mapper for {@code tenant_schema.department_location_master_table} as LocationResponseDTO.
         */
        private static final RowMapper<LocationResponseDTO> DEPT_LOCATION_ROW_MAPPER = (rs,
                        rowNum) -> LocationResponseDTO.builder()
                                        .id(rs.getInt("id"))
                                        .uuid(rs.getString("uuid"))
                                        .title(rs.getString("title"))
                                        .parentId((Integer) rs.getObject("parent_id"))
                                        .status(rs.getInt("status"))
                                        .build();

        /**
         * Validates the schema name.
         * Only accepts tenant schemas in the canonical form {@code tenant_<stateCode>}
         * where stateCode is 1–30 lowercase alphanumeric characters or underscores,
         * and may start with a digit (consistent with {@link org.arghyam.jalsoochak.tenant.config.TenantInterceptor}
         * which accepts {@code ^[A-Za-z0-9_]{1,32}$} before lowercasing and prefixing).
         * This prevents SQL injection and ensures callers cannot accidentally target
         * shared schemas (common_schema, analytics_schema, etc.).
         */
        private void validateSchemaName(String schemaName) {
                if (schemaName == null || !schemaName.matches("^tenant_[a-z0-9][a-z0-9_]{0,29}$")) {
                        throw new IllegalArgumentException("Invalid schema name: " + schemaName);
                }
        }

        /**
         * Maps a {@link RegionTypeEnum} to its corresponding table name.
         * Using an explicit switch ensures only the finite set of known-safe table names
         * can ever be interpolated into SQL — no user input reaches this method.
         */
        private String locationTableFor(RegionTypeEnum regionType) {
                return switch (regionType) {
                        case LGD -> "lgd_location_master_table";
                        case DEPARTMENT -> "department_location_master_table";
                };
        }

        /**
         * Fetches all supported languages from the specified schema.
         */
        @SuppressWarnings("java:S2077")
        public List<LanguageConfigDTO> getSupportedLanguages(String schemaName) {
                validateSchemaName(schemaName);
                String sql = String.format(
                                "SELECT language_name, preference FROM %s.language_master_table WHERE status = ? ORDER BY preference",
                                schemaName);
                return jdbcTemplate.query(sql, (rs, rowNum) -> LanguageConfigDTO.builder()
                                .language(rs.getString("language_name"))
                                .preference((Integer) rs.getObject("preference"))
                                .build(), StatusEnum.ACTIVE.getCode());
        }

        /**
         * Updates supported languages for a tenant by synchronizing the
         * language_master_table.
         * mark all as inactive (status=0) and then upsert new ones.
         */
        @SuppressWarnings("java:S2077")
        public void setSupportedLanguages(String schemaName, List<LanguageConfigDTO> languages, Integer currentUserId) {
                validateSchemaName(schemaName);
                if (languages == null) {
                        throw new InvalidConfigValueException("Languages cannot be null");
                }
                // Mark all as inactive
                String deactivateSql = String.format(
                                "UPDATE %s.language_master_table SET status = ?, updated_by = ?, updated_at = NOW()",
                                schemaName);
                jdbcTemplate.update(deactivateSql, StatusEnum.INACTIVE.getCode(), currentUserId);

                // Upsert new languages
                String upsertSql = String.format(
                                """
                                                INSERT INTO %s.language_master_table (language_name, preference, status, created_by, updated_by)
                                                VALUES (?, ?, ?, ?, ?)
                                                ON CONFLICT (language_name) DO UPDATE SET status = ?, preference = EXCLUDED.preference, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                                                """,
                                schemaName);

                for (LanguageConfigDTO lang : languages) {
                        jdbcTemplate.update(upsertSql, lang.getLanguage(), lang.getPreference(),
                                        StatusEnum.ACTIVE.getCode(), currentUserId, currentUserId,
                                        StatusEnum.ACTIVE.getCode());
                }
        }

        /**
         * Fetches location hierarchy configuration from the specified schema for a
         * given region type.
         */
        @SuppressWarnings("java:S2077")
        public LocationConfigDTO getLocationHierarchy(String schemaName, RegionTypeEnum regionType) {
                validateSchemaName(schemaName);
                String sql = String.format(
                                "SELECT level, level_name FROM %s.location_config_master_table WHERE region_type = ? ORDER BY level",
                                schemaName);
                List<LocationLevelConfigDTO> levels = jdbcTemplate.query(sql, (rs, rowNum) -> {
                        try {
                                List<LocationLevelNameDTO> names = objectMapper.readValue(
                                        rs.getString("level_name"),
                                        objectMapper.getTypeFactory().constructCollectionType(List.class, LocationLevelNameDTO.class));
                                return LocationLevelConfigDTO.builder()
                                        .level(rs.getInt("level"))
                                        .levelName(names)
                                        .build();
                        } catch (JsonProcessingException e) {
                                throw new InvalidConfigValueException("Malformed level_name JSON in location_config_master_table", e);
                        }
                }, regionType.getCode());
                return LocationConfigDTO.builder().locationHierarchy(levels).build();
        }

        /**
         * Replaces location hierarchy configuration for a given region type.
         * Deletes all existing entries for the region type, then inserts the new ones.
         */
        @SuppressWarnings("java:S2077")
        public void setLocationHierarchy(String schemaName, RegionTypeEnum regionType,
                        List<LocationLevelConfigDTO> hierarchy,
                        Integer currentUserId) {
                validateSchemaName(schemaName);
                if (hierarchy == null) {
                        throw new InvalidConfigValueException("Hierarchy cannot be null");
                }

                String deleteSql = String.format(
                                "DELETE FROM %s.location_config_master_table WHERE region_type = ?",
                                schemaName);
                jdbcTemplate.update(deleteSql, regionType.getCode());

                String insertSql = String.format(
                                """
                                                INSERT INTO %s.location_config_master_table (region_type, level, level_name, created_by, updated_by)
                                                VALUES (?, ?, ?, ?, ?)
                                                """,
                                schemaName);

                for (LocationLevelConfigDTO config : hierarchy) {
                        try {
                                String levelNameJson = objectMapper.writeValueAsString(config.getLevelName());
                                jdbcTemplate.update(insertSql, ps -> {
                                        ps.setObject(1, regionType.getCode());
                                        ps.setInt(2, config.getLevel());
                                        ps.setObject(3, levelNameJson, Types.OTHER);
                                        ps.setObject(4, currentUserId);
                                        ps.setObject(5, currentUserId);
                                });
                        } catch (JsonProcessingException e) {
                                throw new InvalidConfigValueException("Failed to serialize levelName for level " + config.getLevel(), e);
                        }
                }
        }

        /**
         * Counts seeded records in the location master table for the given hierarchy type.
         * Used to determine whether structural changes to the hierarchy are permitted.
         *
         * @param schemaName Schema name (e.g., "tenant_mp")
         * @param regionType LGD or DEPARTMENT
         * @return Total count of records in the corresponding master table
         */
        @SuppressWarnings("java:S2077")
        public long countSeededLocationData(String schemaName, RegionTypeEnum regionType) {
                validateSchemaName(schemaName);
                String sql = String.format("SELECT COUNT(*) FROM %s.%s", schemaName, locationTableFor(regionType));
                Long count = jdbcTemplate.queryForObject(sql, Long.class);
                return count != null ? count : 0L;
        }

        /**
         * Atomically checks for seeded location data and, if none exists, replaces the hierarchy
         * configuration. Acquires a transaction-scoped PostgreSQL advisory lock keyed on the
         * schema+regionType pair to prevent a concurrent seeding transaction from racing between
         * the count check and the delete/insert. The lock is auto-released on commit/rollback.
         *
         * @param schemaName    Schema name (e.g., "tenant_mp")
         * @param regionType    LGD or DEPARTMENT
         * @param hierarchy     New level definitions
         * @param currentUserId Audit user ID
         * @throws LocationHierarchyStructureLockedException when seeded rows exist
         */
        @SuppressWarnings("java:S2077")
        public void rewriteLocationHierarchyIfNoSeededData(String schemaName, RegionTypeEnum regionType,
                        List<LocationLevelConfigDTO> hierarchy, Integer currentUserId) {
                validateSchemaName(schemaName);

                // Advisory lock uses two int keys for better distribution and no string-concatenation SQL injection.
                // pg_advisory_xact_lock(int, int) serialises concurrent structural-change attempts for the same
                // tenant hierarchy and is auto-released when the surrounding transaction ends.
                jdbcTemplate.query(
                        "SELECT pg_advisory_xact_lock(?, ?)",
                        ps -> {
                            ps.setInt(1, schemaName.hashCode());
                            ps.setInt(2, regionType.ordinal());
                        },
                        rs -> null);

                String countSql = String.format("SELECT COUNT(*) FROM %s.%s", schemaName, locationTableFor(regionType));
                Long seededCount = jdbcTemplate.queryForObject(countSql, Long.class);
                long count = seededCount != null ? seededCount : 0L;
                if (count > 0) {
                        throw new LocationHierarchyStructureLockedException(regionType.name(), count);
                }

                setLocationHierarchy(schemaName, regionType, hierarchy, currentUserId);
        }

        /**
         * Updates only the level_name fields for existing hierarchy levels.
         * Used when seeded data is present and structural changes are not allowed.
         * Each level in the incoming list must match an existing level number in the DB.
         *
         * @param schemaName   Schema name (e.g., "tenant_mp")
         * @param regionType   LGD or DEPARTMENT
         * @param hierarchy    New level definitions (same level numbers, updated names)
         * @param currentUserId Audit user ID
         */
        @SuppressWarnings("java:S2077")
        public void updateLevelNames(String schemaName, RegionTypeEnum regionType,
                        List<LocationLevelConfigDTO> hierarchy, Integer currentUserId) {
                validateSchemaName(schemaName);
                if (hierarchy == null) {
                        throw new InvalidConfigValueException("Hierarchy cannot be null");
                }
                String updateSql = String.format(
                                """
                                UPDATE %s.location_config_master_table
                                SET level_name = ?, updated_by = ?, updated_at = NOW()
                                WHERE region_type = ? AND level = ?
                                """,
                                schemaName);
                for (LocationLevelConfigDTO config : hierarchy) {
                        try {
                                String levelNameJson = objectMapper.writeValueAsString(config.getLevelName());
                                int rowsAffected = jdbcTemplate.update(updateSql, ps -> {
                                        ps.setObject(1, levelNameJson, Types.OTHER);
                                        ps.setObject(2, currentUserId);
                                        ps.setObject(3, regionType.getCode());
                                        ps.setInt(4, config.getLevel());
                                });
                                if (rowsAffected == 0) {
                                        throw new InvalidConfigValueException(
                                                        "No matching level found to update for regionType="
                                                                        + regionType.getCode() + ", level=" + config.getLevel());
                                }
                        } catch (JsonProcessingException e) {
                                throw new InvalidConfigValueException(
                                                "Failed to serialize levelName for level " + config.getLevel(), e);
                        }
                }
        }

        /**
         * Fetches child locations by parent ID from lgd_location_master_table.
         * 
         * @param schemaName Schema name (e.g., "tenant_mp")
         * @param parentId   Parent location ID (or null for root-level locations)
         * @return List of child locations ordered by title
         */
        @SuppressWarnings("java:S2077")
        public List<LocationResponseDTO> findLgdLocationsByParentId(String schemaName, Integer parentId) {
                validateSchemaName(schemaName);
                
                String sql;
                Object[] params;
                
                if (parentId == null) {
                        sql = String.format(
                                "SELECT * FROM %s.lgd_location_master_table WHERE parent_id IS NULL AND status = ? ORDER BY title",
                                schemaName);
                        params = new Object[]{StatusEnum.ACTIVE.getCode()};
                } else {
                        sql = String.format(
                                "SELECT * FROM %s.lgd_location_master_table WHERE parent_id = ? AND status = ? ORDER BY title",
                                schemaName);
                        params = new Object[]{parentId, StatusEnum.ACTIVE.getCode()};
                }
                
                log.debug("Fetching LGD locations from schema: {} with parentId: {}", schemaName, parentId);
                return jdbcTemplate.query(sql, LGD_LOCATION_ROW_MAPPER, params);
        }

        /**
         * Fetches child locations by parent ID from department_location_master_table.
         * 
         * @param schemaName Schema name (e.g., "tenant_mp")
         * @param parentId   Parent location ID (or null for root-level locations)
         * @return List of child locations ordered by title
         */
        @SuppressWarnings("java:S2077")
        public List<LocationResponseDTO> findDepartmentLocationsByParentId(String schemaName, Integer parentId) {
                validateSchemaName(schemaName);
                
                String sql;
                Object[] params;
                
                if (parentId == null) {
                        sql = String.format(
                                "SELECT * FROM %s.department_location_master_table WHERE parent_id IS NULL AND status = ? ORDER BY title",
                                schemaName);
                        params = new Object[]{StatusEnum.ACTIVE.getCode()};
                } else {
                        sql = String.format(
                                "SELECT * FROM %s.department_location_master_table WHERE parent_id = ? AND status = ? ORDER BY title",
                                schemaName);
                        params = new Object[]{parentId, StatusEnum.ACTIVE.getCode()};
                }
                
                log.debug("Fetching department locations from schema: {} with parentId: {}", schemaName, parentId);
                return jdbcTemplate.query(sql, DEPT_LOCATION_ROW_MAPPER, params);
        }
}

