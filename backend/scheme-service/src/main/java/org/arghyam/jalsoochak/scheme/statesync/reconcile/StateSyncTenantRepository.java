package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tenant-schema reads and writes for the state sync. Every write stamps {@code updated_by} /
 * {@code created_by} with the configured actor and touches only the columns the sync owns.
 *
 * <p>The schema name is interpolated into SQL, so it is checked against {@link #SAFE_SCHEMA} first.
 */
@Repository
public class StateSyncTenantRepository {

    /** location_config_master_table.region_type. */
    public static final int REGION_LGD = 1;
    public static final int REGION_DEPARTMENT = 2;

    private static final Pattern SAFE_SCHEMA = Pattern.compile("^tenant_[a-z0-9_]{1,32}$");

    public record LocationNode(int id, String title, int level, Integer parentId, String stateCode) {
    }

    public record UserRow(int id, String uuid, int userTypeId, String userTypeName, String titleEncrypted,
                          String phoneHash, String stateUserId, int status) {
    }

    public record SchemeRow(int id, String stateSchemeId, String centreSchemeId, String stateSchemeCode,
                            String name, int workStatus, int operatingStatus, int plannedFhtc, int fhtcCount,
                            Double latitude, Double longitude, boolean autoProvisioned) {
    }

    /** A scheme → location mapping row (LGD or department). */
    public record LocationMappingRow(int id, int schemeId, int locationId, String level) {
    }

    /** A user → scheme mapping row, live or retired. */
    public record UserMappingRow(int id, int userId, int schemeId, int status, boolean deleted) {
    }

    private final JdbcTemplate jdbc;

    public StateSyncTenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Location trees ──────────────────────────────────────────────────────

    /** level → location_config_master_table.id for one region type. */
    public Map<Integer, Integer> locationConfigIds(String schema, int regionType) {
        Map<Integer, Integer> byLevel = new HashMap<>();
        jdbc.query("SELECT id, level FROM " + s(schema) + ".location_config_master_table "
                        + "WHERE region_type = ? AND deleted_at IS NULL ORDER BY id",
                rs -> {
                    byLevel.putIfAbsent(rs.getInt("level"), rs.getInt("id"));
                }, regionType);
        return byLevel;
    }

    public List<LocationNode> lgdNodes(String schema) {
        return jdbc.query("SELECT l.id, l.title, c.level, l.parent_id, l.state_lgd_id AS state_code "
                + "FROM " + s(schema) + ".lgd_location_master_table l "
                + "JOIN " + s(schema) + ".location_config_master_table c ON c.id = l.lgd_location_config_id "
                + "WHERE l.deleted_at IS NULL AND c.region_type = " + REGION_LGD, this::node);
    }

    public List<LocationNode> departmentNodes(String schema) {
        return jdbc.query("SELECT d.id, d.title, c.level, d.parent_id, d.state_dept_id AS state_code "
                + "FROM " + s(schema) + ".department_location_master_table d "
                + "JOIN " + s(schema) + ".location_config_master_table c "
                + "  ON c.id = d.department_location_config_id "
                + "WHERE d.deleted_at IS NULL AND c.region_type = " + REGION_DEPARTMENT, this::node);
    }

    public void setLgdStateCode(String schema, int id, String code, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".lgd_location_master_table "
                + "SET state_lgd_id = ?, updated_by = ?, updated_at = NOW() WHERE id = ?", code, actor, id);
    }

    public void setDepartmentStateCode(String schema, int id, String code, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".department_location_master_table "
                + "SET state_dept_id = ?, updated_by = ?, updated_at = NOW() WHERE id = ?", code, actor, id);
    }

    public void setDepartmentParent(String schema, int id, int parentId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".department_location_master_table "
                + "SET parent_id = ?, updated_by = ?, updated_at = NOW() WHERE id = ?", parentId, actor, id);
    }

    public int insertDepartment(String schema, String title, int configId, Integer parentId, String code, int actor) {
        return jdbc.queryForObject("INSERT INTO " + s(schema) + ".department_location_master_table "
                        + "(title, department_location_config_id, parent_id, state_dept_id, status, "
                        + " created_by, updated_by) VALUES (?, ?, ?, ?, 1, ?, ?) RETURNING id",
                Integer.class, title, configId, parentId, code, actor, actor);
    }

    // ── Users ───────────────────────────────────────────────────────────────

    /** user_type_master_table: upper-cased c_name → id, live rows only. */
    public Map<String, Integer> userTypeIds() {
        Map<String, Integer> ids = new HashMap<>();
        jdbc.query("SELECT id, c_name FROM common_schema.user_type_master_table WHERE deleted_at IS NULL",
                rs -> {
                    ids.put(rs.getString("c_name").trim().toUpperCase(java.util.Locale.ROOT), rs.getInt("id"));
                });
        return ids;
    }

    public List<UserRow> liveUsers(String schema) {
        return jdbc.query("SELECT u.id, u.uuid, u.user_type, upper(ut.c_name) AS type_name, u.title, "
                        + "u.phone_number_hash, u.state_user_id, u.status "
                        + "FROM " + s(schema) + ".user_table u "
                        + "LEFT JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type "
                        + "WHERE u.deleted_at IS NULL AND u.is_auto_provisioned = FALSE",
                (rs, n) -> new UserRow(rs.getInt("id"), rs.getString("uuid"), rs.getInt("user_type"),
                        rs.getString("type_name"), rs.getString("title"), rs.getString("phone_number_hash"),
                        rs.getString("state_user_id"), rs.getInt("status")));
    }

    /**
     * Inserts a user the way the spreadsheet ingest and PumpOperatorUploadChunkProcessor do: email NULL
     * (the upstream carries none), the onboarding password literal, phone and email pre-verified.
     */
    public UserRow insertUser(String schema, int tenantId, String titleEncrypted, String titleHash, int userTypeId,
                              String userTypeName, String phoneEncrypted, String phoneHash, String stateUserId,
                              int actor) {
        return jdbc.queryForObject("INSERT INTO " + s(schema) + ".user_table "
                        + "(tenant_id, title, title_hash, email, user_type, phone_number, phone_number_hash, "
                        + " state_user_id, password, status, email_verification_status, "
                        + " phone_verification_status, created_by, updated_by) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'CSV_ONBOARDED', 1, TRUE, TRUE, ?, ?) "
                        + "RETURNING id, uuid",
                (rs, n) -> new UserRow(rs.getInt("id"), rs.getString("uuid"), userTypeId, userTypeName,
                        titleEncrypted, phoneHash, stateUserId, 1),
                tenantId, titleEncrypted, titleHash, userTypeId, phoneEncrypted, phoneHash, stateUserId,
                actor, actor);
    }

    public void updateUser(String schema, int id, String titleEncrypted, String titleHash, int userTypeId,
                           String stateUserId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".user_table SET title = ?, title_hash = ?, user_type = ?, "
                        + "state_user_id = ?, updated_by = ?, updated_at = NOW() WHERE id = ?",
                titleEncrypted, titleHash, userTypeId, stateUserId, actor, id);
    }

    public void deactivateUser(String schema, int id, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".user_table SET status = 0, updated_by = ?, updated_at = NOW() "
                + "WHERE id = ? AND status <> 0", actor, id);
    }

    // ── Schemes ─────────────────────────────────────────────────────────────

    /** Live schemes, placeholders included (flagged), so a placeholder is never matched as real. */
    public List<SchemeRow> liveSchemes(String schema) {
        return jdbc.query("SELECT id, state_scheme_id, centre_scheme_id, state_scheme_code, scheme_name, "
                + "work_status, operating_status, planned_fhtc, fhtc_count, latitude, longitude, "
                + "is_auto_provisioned FROM " + s(schema) + ".scheme_master_table WHERE deleted_at IS NULL",
                (rs, n) -> new SchemeRow(rs.getInt("id"), rs.getString("state_scheme_id"),
                        rs.getString("centre_scheme_id"), rs.getString("state_scheme_code"),
                        rs.getString("scheme_name"), rs.getInt("work_status"), rs.getInt("operating_status"),
                        rs.getInt("planned_fhtc"), rs.getInt("fhtc_count"), nullableDouble(rs, "latitude"),
                        nullableDouble(rs, "longitude"), rs.getBoolean("is_auto_provisioned")));
    }

    public int insertScheme(String schema, SchemeRow row, int actor) {
        return jdbc.queryForObject("INSERT INTO " + s(schema) + ".scheme_master_table "
                        + "(state_scheme_id, centre_scheme_id, state_scheme_code, scheme_name, work_status, "
                        + " operating_status, planned_fhtc, fhtc_count, latitude, longitude, created_by, "
                        + " updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Integer.class, row.stateSchemeId(), row.centreSchemeId(), row.stateSchemeCode(), row.name(),
                row.workStatus(), row.operatingStatus(), row.plannedFhtc(), row.fhtcCount(), row.latitude(),
                row.longitude(), actor, actor);
    }

    public void updateScheme(String schema, SchemeRow row, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".scheme_master_table SET state_scheme_id = ?, "
                        + "centre_scheme_id = ?, state_scheme_code = ?, scheme_name = ?, work_status = ?, "
                        + "operating_status = ?, planned_fhtc = ?, fhtc_count = ?, latitude = ?, longitude = ?, "
                        + "updated_by = ?, updated_at = NOW() WHERE id = ?",
                row.stateSchemeId(), row.centreSchemeId(), row.stateSchemeCode(), row.name(), row.workStatus(),
                row.operatingStatus(), row.plannedFhtc(), row.fhtcCount(), row.latitude(), row.longitude(),
                actor, row.id());
    }

    /**
     * IMIS ids of live lenient-ingestion placeholders that no real scheme carries yet, newest first.
     * Only all-digit ids: a placeholder created from a submission without one has nothing to look up.
     */
    public List<String> unresolvedPlaceholderCentreIds(String schema, int limit) {
        return jdbc.queryForList("SELECT centre_scheme_id FROM (SELECT p.centre_scheme_id, MAX(p.id) AS newest "
                        + "FROM " + s(schema) + ".scheme_master_table p "
                        + "WHERE p.is_auto_provisioned AND p.deleted_at IS NULL AND p.centre_scheme_id ~ '^[0-9]+$' "
                        + "AND NOT EXISTS (SELECT 1 FROM " + s(schema) + ".scheme_master_table r "
                        + "  WHERE r.centre_scheme_id = p.centre_scheme_id AND NOT r.is_auto_provisioned "
                        + "  AND r.deleted_at IS NULL) "
                        + "GROUP BY p.centre_scheme_id) t ORDER BY newest DESC LIMIT ?",
                String.class, limit);
    }

    /** What {@link #moveSchemeActivity} re-pointed. */
    public record MovedActivity(int readings, int anomalies) {
    }

    /**
     * LENIENT-INGEST follow-up: re-points everything telemetry recorded against a placeholder scheme to
     * the real one. Readings and anomalies are the only rows a placeholder collects — telemetry never
     * maps users, locations or pumps to it. {@code updated_at} moves on each reading, so the row's
     * version is newer than the one analytics holds and a later republish is applied.
     */
    public MovedActivity moveSchemeActivity(String schema, int fromSchemeId, int toSchemeId, int actor) {
        int readings = jdbc.update("UPDATE " + s(schema) + ".flow_reading_table SET scheme_id = ?, updated_by = ?, "
                + "updated_at = NOW() WHERE scheme_id = ?", toSchemeId, actor, fromSchemeId);
        int anomalies = jdbc.update("UPDATE " + s(schema) + ".anomaly_table SET scheme_id = ? "
                + "WHERE scheme_id = ?", toSchemeId, fromSchemeId);
        return new MovedActivity(readings, anomalies);
    }

    /** Soft-deletes an auto-provisioned placeholder; a real scheme is never touched by this. */
    public void retirePlaceholderScheme(String schema, int schemeId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".scheme_master_table SET deleted_at = NOW(), deleted_by = ?, updated_by = ?, "
                + "updated_at = NOW() WHERE id = ? AND is_auto_provisioned AND deleted_at IS NULL", actor, actor, schemeId);
    }

    public boolean hasReadingSince(String schema, int schemeId, int days) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + s(schema) + ".flow_reading_table "
                        + "WHERE scheme_id = ? AND deleted_at IS NULL "
                        + "AND reading_date >= CURRENT_DATE - make_interval(days => ?))",
                Boolean.class, schemeId, days);
        return Boolean.TRUE.equals(found);
    }

    // ── Scheme ↔ location mappings ──────────────────────────────────────────

    public List<LocationMappingRow> liveLgdMappings(String schema) {
        return jdbc.query("SELECT id, scheme_id, parent_lgd_id, parent_lgd_level FROM " + s(schema)
                        + ".scheme_lgd_mapping_table WHERE deleted_at IS NULL",
                (rs, n) -> new LocationMappingRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
    }

    public List<LocationMappingRow> liveDepartmentMappings(String schema) {
        return jdbc.query("SELECT id, scheme_id, parent_department_id, parent_department_level FROM " + s(schema)
                        + ".scheme_department_mapping_table WHERE deleted_at IS NULL",
                (rs, n) -> new LocationMappingRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
    }

    public void insertLgdMapping(String schema, int schemeId, int lgdId, String level, int actor) {
        jdbc.update("INSERT INTO " + s(schema) + ".scheme_lgd_mapping_table "
                + "(scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by) VALUES (?, ?, ?, ?, ?)",
                schemeId, lgdId, level, actor, actor);
    }

    public void insertDepartmentMapping(String schema, int schemeId, int departmentId, String level, int actor) {
        jdbc.update("INSERT INTO " + s(schema) + ".scheme_department_mapping_table "
                + "(scheme_id, parent_department_id, parent_department_level, created_by, updated_by) "
                + "VALUES (?, ?, ?, ?, ?)", schemeId, departmentId, level, actor, actor);
    }

    public void retireLgdMapping(String schema, int mappingId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".scheme_lgd_mapping_table SET deleted_at = NOW(), deleted_by = ?, "
                + "updated_by = ?, updated_at = NOW() WHERE id = ? AND deleted_at IS NULL", actor, actor, mappingId);
    }

    public void retireDepartmentMapping(String schema, int mappingId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".scheme_department_mapping_table SET deleted_at = NOW(), "
                + "deleted_by = ?, updated_by = ?, updated_at = NOW() WHERE id = ? AND deleted_at IS NULL",
                actor, actor, mappingId);
    }

    // ── User ↔ scheme mappings ──────────────────────────────────────────────

    /** Every row, live and retired, so a pair is revived rather than duplicated. */
    public List<UserMappingRow> allUserSchemeMappings(String schema) {
        return jdbc.query("SELECT id, user_id, scheme_id, status, deleted_at IS NOT NULL AS deleted FROM "
                        + s(schema) + ".user_scheme_mapping_table ORDER BY id",
                (rs, n) -> new UserMappingRow(rs.getInt("id"), rs.getInt("user_id"), rs.getInt("scheme_id"),
                        rs.getInt("status"), rs.getBoolean("deleted")));
    }

    public int insertUserSchemeMapping(String schema, int userId, int schemeId, int actor) {
        return jdbc.queryForObject("INSERT INTO " + s(schema) + ".user_scheme_mapping_table "
                        + "(user_id, scheme_id, status, created_by, updated_by) VALUES (?, ?, 1, ?, ?) RETURNING id",
                Integer.class, userId, schemeId, actor, actor);
    }

    public void reviveUserSchemeMapping(String schema, int mappingId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".user_scheme_mapping_table SET status = 1, deleted_at = NULL, "
                + "deleted_by = NULL, updated_by = ?, updated_at = NOW() WHERE id = ?", actor, mappingId);
    }

    /** Retires on both guards every read path checks: {@code status = 0} and {@code deleted_at}. */
    public void retireUserSchemeMapping(String schema, int mappingId, int actor) {
        jdbc.update("UPDATE " + s(schema) + ".user_scheme_mapping_table SET status = 0, deleted_at = NOW(), "
                + "deleted_by = ?, updated_by = ?, updated_at = NOW() WHERE id = ? AND deleted_at IS NULL",
                actor, actor, mappingId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private LocationNode node(ResultSet rs, int n) throws SQLException {
        int parent = rs.getInt("parent_id");
        return new LocationNode(rs.getInt("id"), rs.getString("title"), rs.getInt("level"),
                rs.wasNull() ? null : parent, rs.getString("state_code"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    static String s(String schema) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("unsafe tenant schema name");
        }
        return schema;
    }
}
