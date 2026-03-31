package org.arghyam.jalsoochak.tenant.repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * Repository for operations on {@code common_schema} tables.
 * Uses {@link JdbcTemplate} with explicit schema-qualified SQL
 * to avoid dependence on the connection's current {@code search_path}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class TenantCommonRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Row mapper for {@code common_schema.tenant_master_table}.
     */
    private static final RowMapper<TenantResponseDTO> TENANT_ROW_MAPPER = (rs, rowNum) -> TenantResponseDTO.builder()
            .id(rs.getInt("id"))
            .uuid(rs.getString("uuid"))
            .stateCode(rs.getString("state_code"))
            .lgdCode(rs.getInt("lgd_code"))
            .name(rs.getString("title"))
            .status(TenantStatusEnum.fromCode(rs.getInt("status")).name())
            .createdAt(rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime()
                    : null)
            .createdBy((Integer) rs.getObject("created_by"))
            .onboardedAt(rs.getTimestamp("onboarded_at") != null
                    ? rs.getTimestamp("onboarded_at").toLocalDateTime()
                    : null)
            .updatedAt(rs.getTimestamp("updated_at") != null
                    ? rs.getTimestamp("updated_at").toLocalDateTime()
                    : null)
            .updatedBy((Integer) rs.getObject("updated_by"))
            .build();

    /**
     * Row mapper for {@code common_schema.tenant_config_master_table}.
     */
    private static final RowMapper<ConfigDTO> CONFIG_ROW_MAPPER = (rs, rowNum) -> ConfigDTO
            .builder()
            .id(rs.getInt("id"))
            .uuid(rs.getString("uuid"))
            .tenantId(rs.getInt("tenant_id"))
            .configKey(rs.getString("config_key"))
            .configValue(rs.getString("config_value"))
            .createdAt(rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime()
                    : null)
            .createdBy((Integer) rs.getObject("created_by"))
            .updatedAt(rs.getTimestamp("updated_at") != null
                    ? rs.getTimestamp("updated_at").toLocalDateTime()
                    : null)
            .updatedBy((Integer) rs.getObject("updated_by"))
            .build();

    /**
     * Inserts a new tenant into {@code common_schema.tenant_master_table}.
     */
    public Optional<TenantResponseDTO> createTenant(CreateTenantRequestDTO request, Integer currentUserId) {
        String sql = """
                INSERT INTO common_schema.tenant_master_table
                    (state_code, lgd_code, title, created_by, status, created_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                RETURNING *
                """;

        List<TenantResponseDTO> results = jdbcTemplate.query(sql, TENANT_ROW_MAPPER,
                request.getStateCode(),
                request.getLgdCode(),
                request.getName(),
                currentUserId,
                TenantStatusEnum.ONBOARDED.getCode());
        return results.stream().findFirst();
    }

    /**
     * Calls the {@code create_tenant_schema()} PL/pgSQL function to provision
     * all tenant-specific tables and indexes in a new schema.
     */
    public void provisionTenantSchema(String schemaName) {
        validateSchemaName(schemaName);
        log.info("Provisioning tenant schema: {}", schemaName);

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            // Explicitly cast to text to match PostgreSQL function signature
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT common_schema.create_tenant_schema(?::text)")) {
                ps.setString(1, schemaName);
                ps.execute();
            }
            return null;
        });

        // Make password nullable (Keycloak owns credentials). No IF EXISTS: missing table
        // indicates a provisioning failure that should surface immediately.
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "ALTER TABLE " + schemaName + ".user_table ALTER COLUMN password DROP NOT NULL")) {
                ps.execute();
            }
            return null;
        });
    }

    /**
     * Finds a tenant by its state code.
     */
    public Optional<TenantResponseDTO> findByStateCode(String stateCode) {
        String sql = "SELECT * FROM common_schema.tenant_master_table WHERE state_code = ? AND deleted_at IS NULL";
        List<TenantResponseDTO> results = jdbcTemplate.query(sql, TENANT_ROW_MAPPER, stateCode);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Lists all tenants in the common_schema.tenant_master_table (no pagination).
     * Excludes soft-deleted tenants.
     */
    public List<TenantResponseDTO> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM common_schema.tenant_master_table WHERE deleted_at IS NULL ORDER BY id",
                TENANT_ROW_MAPPER);
    }

    /**
     * Lists all non-system tenants with pagination and optional filters.
     * The system tenant (id = 0) is excluded from results.
     *
     * @param limit   Page size.
     * @param offset  Row offset.
     * @param status  Optional status filter; {@code null} means all statuses.
     * @param search  Optional case-insensitive partial match on tenant name; {@code null} or blank means no filter.
     */
    public List<TenantResponseDTO> findAll(int limit, long offset, TenantStatusEnum status, String search) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }

        FilterClause filter = buildTenantFilterClause(status, search);
        List<Object> params = new ArrayList<>(Arrays.asList(filter.params()));
        params.add(limit);
        params.add(offset);

        String sql = "SELECT * FROM common_schema.tenant_master_table " + filter.whereClause()
                + " ORDER BY id LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, TENANT_ROW_MAPPER, params.toArray());
    }

    /**
     * Counts the total number of non-system tenants with optional filters.
     * The system tenant (id = 0) and soft-deleted tenants are excluded from the count.
     *
     * @param status  Optional status filter; {@code null} means all statuses.
     * @param search  Optional case-insensitive partial match on tenant name; {@code null} or blank means no filter.
     */
    public long countAllTenants(TenantStatusEnum status, String search) {
        FilterClause filter = buildTenantFilterClause(status, search);
        String sql = "SELECT COUNT(*) FROM common_schema.tenant_master_table " + filter.whereClause();
        return jdbcTemplate.queryForObject(sql, Long.class, filter.params());
    }

    private record FilterClause(String whereClause, Object[] params) {}

    private FilterClause buildTenantFilterClause(TenantStatusEnum status, String search) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE id != 0 AND deleted_at IS NULL");
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.getCode());
        }
        if (search != null && !search.isBlank()) {
            String escapedSearch = search.strip()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            where.append(" AND title ILIKE ? ESCAPE '\\'");
            params.add("%" + escapedSearch + "%");
        }
        return new FilterClause(where.toString(), params.toArray());
    }

    /**
     * Returns an aggregate status summary for all non-system tenants in a single query.
     * Uses PostgreSQL conditional aggregation to count per status in one round-trip.
     */
    public TenantSummaryResponseDTO getTenantSummary() {
        String sql = """
                SELECT
                    COUNT(*)                                       AS total,
                    COUNT(*) FILTER (WHERE status = ?)            AS onboarded,
                    COUNT(*) FILTER (WHERE status = ?)            AS configured,
                    COUNT(*) FILTER (WHERE status = ?)            AS active,
                    COUNT(*) FILTER (WHERE status = ?)            AS inactive,
                    COUNT(*) FILTER (WHERE status = ?)            AS suspended,
                    COUNT(*) FILTER (WHERE status = ?)            AS degraded,
                    COUNT(*) FILTER (WHERE status = ?)            AS archived
                FROM common_schema.tenant_master_table
                WHERE id != 0 AND deleted_at IS NULL
                """;
        return jdbcTemplate.queryForObject(sql,
                (rs, rn) -> TenantSummaryResponseDTO.builder()
                        .totalTenants(rs.getLong("total"))
                        .onboardedTenants(rs.getLong("onboarded"))
                        .configuredTenants(rs.getLong("configured"))
                        .activeTenants(rs.getLong("active"))
                        .inactiveTenants(rs.getLong("inactive"))
                        .suspendedTenants(rs.getLong("suspended"))
                        .degradedTenants(rs.getLong("degraded"))
                        .archivedTenants(rs.getLong("archived"))
                        .build(),
                TenantStatusEnum.ONBOARDED.getCode(),
                TenantStatusEnum.CONFIGURED.getCode(),
                TenantStatusEnum.ACTIVE.getCode(),
                TenantStatusEnum.INACTIVE.getCode(),
                TenantStatusEnum.SUSPENDED.getCode(),
                TenantStatusEnum.DEGRADED.getCode(),
                TenantStatusEnum.ARCHIVED.getCode());
    }

    /**
     * Finds a tenant by its ID.
     */
    public Optional<TenantResponseDTO> findById(Integer tenantId) {
        String sql = "SELECT * FROM common_schema.tenant_master_table WHERE id = ?";
        List<TenantResponseDTO> results = jdbcTemplate.query(sql, TENANT_ROW_MAPPER, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Updates tenant status. Only non-null fields are applied.
     */
    public Optional<TenantResponseDTO> updateTenant(Integer tenantId, UpdateTenantRequestDTO request,
            Integer currentUserId) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE common_schema.tenant_master_table SET updated_at = NOW()");

        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            TenantStatusEnum statusEnum;
            try {
                statusEnum = TenantStatusEnum.valueOf(request.getStatus().toUpperCase());
            } catch (IllegalArgumentException e) {
                String validValues = Arrays.stream(TenantStatusEnum.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException(
                        "Invalid tenant status '" + request.getStatus() + "'. Valid values: " + validValues, e);
            }
            sql.append(", status = ?");
            params.add(statusEnum.getCode());
        }

        sql.append(", updated_by = ?");
        params.add(currentUserId);

        sql.append(" WHERE id = ? RETURNING *");
        params.add(tenantId);

        List<TenantResponseDTO> results = jdbcTemplate.query(sql.toString(), TENANT_ROW_MAPPER, params.toArray());
        return results.stream().findFirst();
    }

    /**
     * Deactivated a tenant by setting status to INACTIVE and recording updated_at.
     */
    public void deactivateTenant(Integer tenantId, Integer currentUserId) {
        String sql = """
                UPDATE common_schema.tenant_master_table
                SET status = ?, updated_at = NOW(), updated_by = ?
                WHERE id = ?
                """;
        int rows = jdbcTemplate.update(sql, TenantStatusEnum.INACTIVE.getCode(), currentUserId,
                tenantId);
        if (rows == 0) {
            throw new IllegalArgumentException("Tenant with tenantId " + tenantId + " does not exist");
        }
    }

    /**
     * Finds a tenant admin user by its UUID.
     */
    public Optional<Integer> findUserIdByUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty())
            return Optional.empty();
        String sql = "SELECT id FROM common_schema.tenant_admin_user_master_table WHERE uuid = ?";
        List<Integer> ids = jdbcTemplate.queryForList(sql, Integer.class, uuid);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * Finds all configurations for a given tenant.
     */
    public List<ConfigDTO> findConfigsByTenantId(Integer tenantId) {
        String sql = "SELECT * FROM common_schema.tenant_config_master_table WHERE tenant_id = ? AND deleted_at IS NULL";
        return jdbcTemplate.query(sql, CONFIG_ROW_MAPPER, tenantId);
    }

    /**
     * Finds a specific configuration for a tenant by key name.
     */
    public Optional<ConfigDTO> findConfigByTenantAndKey(Integer tenantId, String keyName) {
        String sql = "SELECT * FROM common_schema.tenant_config_master_table WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NULL";
        List<ConfigDTO> results = jdbcTemplate.query(sql, CONFIG_ROW_MAPPER, tenantId, keyName);
        return results.stream().findFirst();
    }

    /**
     * Upserts configuration atomically using INSERT ... ON CONFLICT DO UPDATE.
     * Relies on the partial unique index uq_tenant_config_key on (tenant_id, config_key)
     * WHERE deleted_at IS NULL defined in V14 migration.
     */
    public Optional<ConfigDTO> upsertConfig(Integer tenantId, String keyName,
            String value, Integer currentUserId) {
        String sql = """
                INSERT INTO common_schema.tenant_config_master_table
                    (tenant_id, config_key, config_value, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, config_key) WHERE deleted_at IS NULL
                DO UPDATE SET
                    config_value = EXCLUDED.config_value,
                    updated_at   = NOW(),
                    updated_by   = ?
                RETURNING *
                """;
        List<ConfigDTO> results = jdbcTemplate.query(sql, CONFIG_ROW_MAPPER,
                tenantId, keyName, value, currentUserId, currentUserId, currentUserId);
        return results.stream().findFirst();
    }

    /**
     * Validates a schema name.
     */
    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }
}
