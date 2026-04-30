package org.arghyam.jalsoochak.scheme.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.CodeCountDTO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class SchemeDbRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]*$");
    private final ConcurrentHashMap<String, Boolean> deptTablesExistCache = new ConcurrentHashMap<>();
    private static final Map<Integer, String> WORK_STATUS_LABELS = Map.of(
            1, "Ongoing",
            2, "Completed",
            3, "Not Started",
            4, "Handed Over"
    );
    private static final Map<Integer, String> OPERATING_STATUS_LABELS = Map.of(
            1, "Operative",
            0, "Non-Operative",
            2, "Partially Operative"
    );

    public record SchemeSnapshot(
            Integer id,
            String stateSchemeId,
            String centreSchemeId,
            String schemeName,
            Integer fhtcCount,
            Integer plannedFhtc,
            Integer houseHoldCount,
            Double latitude,
            Double longitude,
            Integer workStatus,
            Integer operatingStatus
    ) {}

    public record SchemeAnalyticsRow(
            Integer schemeId,
            String stateSchemeId,
            String centreSchemeId,
            String schemeName,
            Double latitude,
            Double longitude,
            Integer workStatus,
            Integer operatingStatus,
            Integer parentLgdId,
            Integer parentDepartmentId
    ) {}

    public List<SchemeDTO> findAllSchemes(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, uuid, state_scheme_id, centre_scheme_id, scheme_name,
                       fhtc_count, planned_fhtc, house_hold_count,
                       latitude, longitude, channel, work_status, operating_status
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                ORDER BY id DESC
                """, schemaName);

        return jdbcTemplate.query(sql, (rs, rowNum) -> SchemeDTO.builder()
                .id(rs.getInt("id"))
                .uuid(rs.getString("uuid"))
                .stateSchemeId(rs.getString("state_scheme_id"))
                .centreSchemeId(rs.getString("centre_scheme_id"))
                .schemeName(rs.getString("scheme_name"))
                .fhtcCount(rs.getInt("fhtc_count"))
                .plannedFhtc(rs.getInt("planned_fhtc"))
                .houseHoldCount(rs.getInt("house_hold_count"))
                .latitude((Double) rs.getObject("latitude"))
                .longitude((Double) rs.getObject("longitude"))
                .channel(rs.getInt("channel"))
                .workStatus(workStatusLabel((Integer) rs.getObject("work_status")))
                .operatingStatus(operatingStatusLabel((Integer) rs.getObject("operating_status")))
                .build());
    }

    public List<SchemeDTO> listSchemes(
            String schemaName,
            String stateSchemeId,
            String schemeName,
            String name,
            Integer workStatus,
            Integer operatingStatus,
            String status,
            String sortBy,
            String sortDir,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);

        SqlAndArgs where = buildSchemeWhere(stateSchemeId, schemeName, name, workStatus, operatingStatus, status);
        String orderBy = schemeOrderBy(sortBy, sortDir);

        String sql = String.format("""
                SELECT id, uuid, state_scheme_id, centre_scheme_id, scheme_name,
                       fhtc_count, planned_fhtc, house_hold_count,
                       latitude, longitude, channel, work_status, operating_status
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                  %s
                %s
                LIMIT ? OFFSET ?
                """, schemaName, where.sql(), orderBy);

        List<Object> args = new ArrayList<>(where.args());
        args.add(limit);
        args.add(offset);

        return jdbcTemplate.query(sql, (rs, rowNum) -> SchemeDTO.builder()
                .id(rs.getInt("id"))
                .uuid(rs.getString("uuid"))
                .stateSchemeId(rs.getString("state_scheme_id"))
                .centreSchemeId(rs.getString("centre_scheme_id"))
                .schemeName(rs.getString("scheme_name"))
                .fhtcCount(rs.getInt("fhtc_count"))
                .plannedFhtc(rs.getInt("planned_fhtc"))
                .houseHoldCount(rs.getInt("house_hold_count"))
                .latitude((Double) rs.getObject("latitude"))
                .longitude((Double) rs.getObject("longitude"))
                .channel((Integer) rs.getObject("channel"))
                .workStatus(workStatusLabel((Integer) rs.getObject("work_status")))
                .operatingStatus(operatingStatusLabel((Integer) rs.getObject("operating_status")))
                .build(), args.toArray());
    }

    public long countSchemes(
            String schemaName,
            String stateSchemeId,
            String schemeName,
            String name,
            Integer workStatus,
            Integer operatingStatus,
            String status
    ) {
        validateSchemaName(schemaName);
        SqlAndArgs where = buildSchemeWhere(stateSchemeId, schemeName, name, workStatus, operatingStatus, status);
        String sql = String.format("""
                SELECT COUNT(1)
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                  %s
                """, schemaName, where.sql());
        Long total = jdbcTemplate.queryForObject(sql, Long.class, where.args().toArray());
        return total == null ? 0 : total;
    }

    public List<SchemeMappingDTO> listSchemeMappings(
            String schemaName,
            String name,
            Integer workStatus,
            Integer operatingStatus,
            String status,
            String villageLgdCode,
            String subDivisionName,
            String sortBy,
            String sortDir,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);

        boolean hasDept = hasDepartmentTables(schemaName);
        if (!hasDept && subDivisionName != null && !subDivisionName.isBlank()) {
            return List.of();
        }

        List<Object> args = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            clauses.add("(sm.scheme_name ILIKE ? OR sm.state_scheme_id ILIKE ?)");
            String pat = "%" + name.trim() + "%";
            args.add(pat);
            args.add(pat);
        }
        if (workStatus != null) {
            clauses.add("sm.work_status = ?");
            args.add(workStatus);
        }
        if (operatingStatus != null) {
            clauses.add("sm.operating_status = ?");
            args.add(operatingStatus);
        }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toLowerCase(Locale.ROOT);
            if ("active".equals(s)) {
                clauses.add("sm.operating_status = 1");
            } else if ("inactive".equals(s)) {
                clauses.add("sm.operating_status <> 1");
            }
        }
        if (villageLgdCode != null && !villageLgdCode.isBlank()) {
            clauses.add("lgd.lgd_code ILIKE ?");
            args.add("%" + villageLgdCode.trim() + "%");
        }
        if (hasDept && subDivisionName != null && !subDivisionName.isBlank()) {
            clauses.add("dept.title ILIKE ?");
            args.add("%" + subDivisionName.trim() + "%");
        }

        String filterSql = clauses.isEmpty() ? "" : " AND " + String.join(" AND ", clauses) + " ";

        String orderBy = mappingOrderBy(sortBy, sortDir, hasDept);
        String sql = hasDept
                ? String.format("""
                    SELECT slm.id,
                           sm.id AS scheme_id,
                           sm.state_scheme_id,
                           sm.scheme_name,
                           lgd.lgd_code AS village_lgd_code,
                           lgd.title AS village_name,
                           dept.title   AS sub_division_name
                    FROM %s.scheme_lgd_mapping_table slm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = slm.scheme_id AND sm.deleted_at IS NULL
                    JOIN %s.lgd_location_master_table lgd
                      ON lgd.id = slm.parent_lgd_id AND lgd.deleted_at IS NULL
                    LEFT JOIN %s.scheme_department_mapping_table sdm
                      ON sdm.scheme_id = sm.id AND sdm.deleted_at IS NULL
                    LEFT JOIN %s.department_location_master_table dept
                      ON dept.id = sdm.parent_department_id AND dept.deleted_at IS NULL
                    WHERE slm.deleted_at IS NULL
                      %s
                    %s
                    LIMIT ? OFFSET ?
                    """, schemaName, schemaName, schemaName, schemaName, schemaName, filterSql, orderBy)
                : String.format("""
                    SELECT slm.id,
                           sm.id AS scheme_id,
                           sm.state_scheme_id,
                           sm.scheme_name,
                           lgd.lgd_code AS village_lgd_code,
                           lgd.title AS village_name,
                           NULL::varchar AS sub_division_name
                    FROM %s.scheme_lgd_mapping_table slm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = slm.scheme_id AND sm.deleted_at IS NULL
                    JOIN %s.lgd_location_master_table lgd
                      ON lgd.id = slm.parent_lgd_id AND lgd.deleted_at IS NULL
                    WHERE slm.deleted_at IS NULL
                      %s
                    %s
                    LIMIT ? OFFSET ?
                    """, schemaName, schemaName, schemaName, filterSql, orderBy);

        args.add(limit);
        args.add(offset);

        return jdbcTemplate.query(sql, (rs, rowNum) -> SchemeMappingDTO.builder()
                .id(rs.getLong("id"))
                .schemeId((Integer) rs.getObject("scheme_id"))
                .stateSchemeId(rs.getString("state_scheme_id"))
                .schemeName(rs.getString("scheme_name"))
                .villageLgdCode(rs.getString("village_lgd_code"))
                .villageName(rs.getString("village_name"))
                .subDivisionName(rs.getString("sub_division_name"))
                .build(), args.toArray());
    }

    public long countSchemeMappings(
            String schemaName,
            String name,
            Integer workStatus,
            Integer operatingStatus,
            String status,
            String villageLgdCode,
            String subDivisionName
    ) {
        validateSchemaName(schemaName);

        boolean hasDept = hasDepartmentTables(schemaName);
        if (!hasDept && subDivisionName != null && !subDivisionName.isBlank()) {
            return 0;
        }

        List<Object> args = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            clauses.add("(sm.scheme_name ILIKE ? OR sm.state_scheme_id ILIKE ?)");
            String pat = "%" + name.trim() + "%";
            args.add(pat);
            args.add(pat);
        }
        if (workStatus != null) {
            clauses.add("sm.work_status = ?");
            args.add(workStatus);
        }
        if (operatingStatus != null) {
            clauses.add("sm.operating_status = ?");
            args.add(operatingStatus);
        }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toLowerCase(Locale.ROOT);
            if ("active".equals(s)) {
                clauses.add("sm.operating_status = 1");
            } else if ("inactive".equals(s)) {
                clauses.add("sm.operating_status <> 1");
            }
        }
        if (villageLgdCode != null && !villageLgdCode.isBlank()) {
            clauses.add("lgd.lgd_code ILIKE ?");
            args.add("%" + villageLgdCode.trim() + "%");
        }
        if (hasDept && subDivisionName != null && !subDivisionName.isBlank()) {
            clauses.add("dept.title ILIKE ?");
            args.add("%" + subDivisionName.trim() + "%");
        }

        String filterSql = clauses.isEmpty() ? "" : " AND " + String.join(" AND ", clauses) + " ";

        String sql = hasDept
                ? String.format("""
                    SELECT COUNT(1)
                    FROM %s.scheme_lgd_mapping_table slm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = slm.scheme_id AND sm.deleted_at IS NULL
                    JOIN %s.lgd_location_master_table lgd
                      ON lgd.id = slm.parent_lgd_id AND lgd.deleted_at IS NULL
                    LEFT JOIN %s.scheme_department_mapping_table sdm
                      ON sdm.scheme_id = sm.id AND sdm.deleted_at IS NULL
                    LEFT JOIN %s.department_location_master_table dept
                      ON dept.id = sdm.parent_department_id AND dept.deleted_at IS NULL
                    WHERE slm.deleted_at IS NULL
                      %s
                    """, schemaName, schemaName, schemaName, schemaName, schemaName, filterSql)
                : String.format("""
                    SELECT COUNT(1)
                    FROM %s.scheme_lgd_mapping_table slm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = slm.scheme_id AND sm.deleted_at IS NULL
                    JOIN %s.lgd_location_master_table lgd
                      ON lgd.id = slm.parent_lgd_id AND lgd.deleted_at IS NULL
                    WHERE slm.deleted_at IS NULL
                      %s
                    """, schemaName, schemaName, schemaName, filterSql);

        Long total = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public record SchemeCounts(long activeSchemes, long inactiveSchemes) {}

    public SchemeCounts countActiveInactiveSchemes(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT
                  COUNT(1) FILTER (WHERE deleted_at IS NULL AND operating_status = 1) AS active,
                  COUNT(1) FILTER (WHERE deleted_at IS NULL AND operating_status <> 1) AS inactive
                FROM %s.scheme_master_table
                """, schemaName);

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                new SchemeCounts(rs.getLong("active"), rs.getLong("inactive")));
    }

    public long countSchemesTotal(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT COUNT(1)
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                """, schemaName);
        Long total = jdbcTemplate.queryForObject(sql, Long.class);
        return total == null ? 0 : total;
    }

    public List<CodeCountDTO> countSchemesByWorkStatus(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT work_status AS code, COUNT(1) AS cnt
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                GROUP BY work_status
                ORDER BY work_status
                """, schemaName);
        return jdbcTemplate.query(sql, (rs, rowNum) -> CodeCountDTO.builder()
                .status(workStatusLabel((Integer) rs.getObject("code")))
                .count(rs.getLong("cnt"))
                .build());
    }

    public List<CodeCountDTO> countSchemesByOperatingStatus(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT operating_status AS code, COUNT(1) AS cnt
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                GROUP BY operating_status
                ORDER BY operating_status
                """, schemaName);
        return jdbcTemplate.query(sql, (rs, rowNum) -> CodeCountDTO.builder()
                .status(operatingStatusLabel((Integer) rs.getObject("code")))
                .count(rs.getLong("cnt"))
                .build());
    }

    public boolean existsSchemeById(String schemaName, Integer schemeId) {
        validateSchemaName(schemaName);
        String sql = String.format(
                "SELECT EXISTS (SELECT 1 FROM %s.scheme_master_table WHERE id = ? AND deleted_at IS NULL)",
                schemaName
        );
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schemeId);
        return Boolean.TRUE.equals(exists);
    }

    public SchemeDTO findSchemeById(String schemaName, int schemeId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, uuid, state_scheme_id, centre_scheme_id, scheme_name,
                       fhtc_count, planned_fhtc, house_hold_count,
                       latitude, longitude, channel, work_status, operating_status
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                  AND id = ?
                LIMIT 1
                """, schemaName);
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> SchemeDTO.builder()
                    .id(rs.getInt("id"))
                    .uuid(rs.getString("uuid"))
                    .stateSchemeId(rs.getString("state_scheme_id"))
                    .centreSchemeId(rs.getString("centre_scheme_id"))
                    .schemeName(rs.getString("scheme_name"))
                    .fhtcCount(rs.getInt("fhtc_count"))
                    .plannedFhtc(rs.getInt("planned_fhtc"))
                    .houseHoldCount(rs.getInt("house_hold_count"))
                    .latitude((Double) rs.getObject("latitude"))
                    .longitude((Double) rs.getObject("longitude"))
                    .channel((Integer) rs.getObject("channel"))
                    .workStatus(workStatusLabel((Integer) rs.getObject("work_status")))
                    .operatingStatus(operatingStatusLabel((Integer) rs.getObject("operating_status")))
                    .build(), schemeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private String workStatusLabel(Integer code) {
        if (code == null) {
            return "Unknown";
        }
        return WORK_STATUS_LABELS.getOrDefault(code, "Unknown");
    }

    private String operatingStatusLabel(Integer code) {
        if (code == null) {
            return "Unknown";
        }
        return OPERATING_STATUS_LABELS.getOrDefault(code, "Unknown");
    }

    /**
     * Batch existence check for large uploads (avoids N queries).
     */
    public Set<Integer> findExistingSchemeIds(String schemaName, List<Integer> schemeIds) {
        return findExistingIds(schemaName, "scheme_master_table", schemeIds);
    }

    public boolean existsLgdLocationById(String schemaName, Integer lgdId) {
        validateSchemaName(schemaName);
        String sql = String.format(
                "SELECT EXISTS (SELECT 1 FROM %s.lgd_location_master_table WHERE id = ? AND deleted_at IS NULL)",
                schemaName
        );
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, lgdId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Batch existence check for large uploads (avoids N queries).
     */
    public Set<Integer> findExistingLgdLocationIds(String schemaName, List<Integer> lgdIds) {
        return findExistingIds(schemaName, "lgd_location_master_table", lgdIds);
    }

    public boolean existsDepartmentLocationById(String schemaName, Integer departmentId) {
        validateSchemaName(schemaName);
        String sql = String.format(
                "SELECT EXISTS (SELECT 1 FROM %s.department_location_master_table WHERE id = ? AND deleted_at IS NULL)",
                schemaName
        );
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, departmentId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Batch existence check for large uploads (avoids N queries).
     */
    public Set<Integer> findExistingDepartmentLocationIds(String schemaName, List<Integer> departmentIds) {
        return findExistingIds(schemaName, "department_location_master_table", departmentIds);
    }

    /**
     * Batch lookup of internal scheme IDs by state_scheme_id (case-insensitive).
     * Returns a map keyed by lower(state_scheme_id).
     */
    public Map<String, Integer> findSchemeIdsByStateSchemeIds(String schemaName, List<String> stateSchemeIds) {
        return findIdsByLowerTextKey(schemaName, "scheme_master_table", "state_scheme_id", stateSchemeIds);
    }

    /**
     * Batch lookup of scheme details keyed by lower(state_scheme_id).
     */
    public Map<String, SchemeSnapshot> findSchemeSnapshotsByStateSchemeIds(String schemaName, List<String> stateSchemeIds) {
        validateSchemaName(schemaName);
        if (stateSchemeIds == null || stateSchemeIds.isEmpty()) {
            return Map.of();
        }

        Set<String> uniq = new HashSet<>(Math.max(16, stateSchemeIds.size()));
        for (String value : stateSchemeIds) {
            if (value != null && !value.isBlank()) {
                uniq.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (uniq.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(uniq.size(), "?"));
        String sql = String.format("""
                SELECT id, state_scheme_id, centre_scheme_id, scheme_name,
                       fhtc_count, planned_fhtc, house_hold_count,
                       latitude, longitude, work_status, operating_status
                FROM %s.scheme_master_table
                WHERE deleted_at IS NULL
                  AND lower(state_scheme_id) IN (%s)
                """, schemaName, placeholders);

        List<Object> args = new ArrayList<>(uniq);
        Map<String, SchemeSnapshot> out = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String key = rs.getString("state_scheme_id");
            if (key == null) {
                return;
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            out.put(normalized, new SchemeSnapshot(
                    rs.getInt("id"),
                    rs.getString("state_scheme_id"),
                    rs.getString("centre_scheme_id"),
                    rs.getString("scheme_name"),
                    (Integer) rs.getObject("fhtc_count"),
                    (Integer) rs.getObject("planned_fhtc"),
                    (Integer) rs.getObject("house_hold_count"),
                    (Double) rs.getObject("latitude"),
                    (Double) rs.getObject("longitude"),
                    (Integer) rs.getObject("work_status"),
                    (Integer) rs.getObject("operating_status")
            ));
        }, args.toArray());
        return out;
    }

    public List<SchemeAnalyticsRow> findSchemeAnalyticsRowsByStateSchemeIds(String schemaName, List<String> stateSchemeIds) {
        validateSchemaName(schemaName);
        if (stateSchemeIds == null || stateSchemeIds.isEmpty()) {
            return List.of();
        }

        Set<String> uniq = new HashSet<>(Math.max(16, stateSchemeIds.size()));
        for (String value : stateSchemeIds) {
            if (value != null && !value.isBlank()) {
                uniq.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (uniq.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(uniq.size(), "?"));
        String sql = String.format("""
                SELECT sm.id AS scheme_id,
                       sm.state_scheme_id,
                       sm.centre_scheme_id,
                       sm.scheme_name,
                       sm.latitude,
                       sm.longitude,
                       sm.work_status,
                       sm.operating_status,
                       slm.parent_lgd_id,
                       sdm.parent_department_id
                FROM %s.scheme_master_table sm
                LEFT JOIN LATERAL (
                    SELECT parent_lgd_id
                    FROM %s.scheme_lgd_mapping_table
                    WHERE scheme_id = sm.id
                      AND deleted_at IS NULL
                    ORDER BY id
                    LIMIT 1
                ) slm ON TRUE
                LEFT JOIN LATERAL (
                    SELECT parent_department_id
                    FROM %s.scheme_department_mapping_table
                    WHERE scheme_id = sm.id
                      AND deleted_at IS NULL
                    ORDER BY id
                    LIMIT 1
                ) sdm ON TRUE
                WHERE sm.deleted_at IS NULL
                  AND lower(sm.state_scheme_id) IN (%s)
                """, schemaName, schemaName, schemaName, placeholders);

        List<Object> args = new ArrayList<>(uniq);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SchemeAnalyticsRow(
                (Integer) rs.getObject("scheme_id"),
                rs.getString("state_scheme_id"),
                rs.getString("centre_scheme_id"),
                rs.getString("scheme_name"),
                (Double) rs.getObject("latitude"),
                (Double) rs.getObject("longitude"),
                (Integer) rs.getObject("work_status"),
                (Integer) rs.getObject("operating_status"),
                (Integer) rs.getObject("parent_lgd_id"),
                (Integer) rs.getObject("parent_department_id")
        ), args.toArray());
    }

    public List<SchemeAnalyticsRow> findSchemeAnalyticsRowsBySchemeIds(String schemaName, List<Integer> schemeIds) {
        validateSchemaName(schemaName);
        if (schemeIds == null || schemeIds.isEmpty()) {
            return List.of();
        }
        Set<Integer> uniq = new HashSet<>(schemeIds);
        if (uniq.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(uniq.size(), "?"));
        String sql = String.format("""
                SELECT sm.id AS scheme_id,
                       sm.state_scheme_id,
                       sm.centre_scheme_id,
                       sm.scheme_name,
                       sm.latitude,
                       sm.longitude,
                       sm.work_status,
                       sm.operating_status,
                       slm.parent_lgd_id,
                       sdm.parent_department_id
                FROM %s.scheme_master_table sm
                LEFT JOIN LATERAL (
                    SELECT parent_lgd_id
                    FROM %s.scheme_lgd_mapping_table
                    WHERE scheme_id = sm.id
                      AND deleted_at IS NULL
                    ORDER BY id
                    LIMIT 1
                ) slm ON TRUE
                LEFT JOIN LATERAL (
                    SELECT parent_department_id
                    FROM %s.scheme_department_mapping_table
                    WHERE scheme_id = sm.id
                      AND deleted_at IS NULL
                    ORDER BY id
                    LIMIT 1
                ) sdm ON TRUE
                WHERE sm.deleted_at IS NULL
                  AND sm.id IN (%s)
                """, schemaName, schemaName, schemaName, placeholders);

        List<Object> args = new ArrayList<>(uniq);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SchemeAnalyticsRow(
                (Integer) rs.getObject("scheme_id"),
                rs.getString("state_scheme_id"),
                rs.getString("centre_scheme_id"),
                rs.getString("scheme_name"),
                (Double) rs.getObject("latitude"),
                (Double) rs.getObject("longitude"),
                (Integer) rs.getObject("work_status"),
                (Integer) rs.getObject("operating_status"),
                (Integer) rs.getObject("parent_lgd_id"),
                (Integer) rs.getObject("parent_department_id")
        ), args.toArray());
    }

    public Integer findTenantIdByUserId(String schemaName, Integer userId) {
        validateSchemaName(schemaName);
        if (userId == null) {
            return null;
        }
        String sql = String.format("""
                SELECT tenant_id
                FROM %s.user_table
                WHERE id = ?
                  AND deleted_at IS NULL
                LIMIT 1
                """, schemaName);
        try {
            return jdbcTemplate.queryForObject(sql, Integer.class, userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * Batch lookup of LGD location IDs by lgd_code (case-insensitive).
     * Returns a map keyed by lower(lgd_code).
     */
    public Map<String, Integer> findLgdIdsByCodes(String schemaName, List<String> lgdCodes) {
        return findIdsByLowerTextKey(schemaName, "lgd_location_master_table", "lgd_code", lgdCodes);
    }

    /**
     * Batch lookup of department location IDs by title (case-insensitive).
     * Returns a map keyed by lower(title).
     */
    public Map<String, Integer> findDepartmentIdsByTitles(String schemaName, List<String> titles) {
        return findIdsByLowerTextKey(schemaName, "department_location_master_table", "title", titles);
    }

    public Map<Integer, Set<Integer>> findSchemeLgdMappingsBySchemeIds(String schemaName, List<Integer> schemeIds) {
        return findMappingIdsByScheme(schemaName, "scheme_lgd_mapping_table", "parent_lgd_id", schemeIds);
    }

    public Map<Integer, Set<Integer>> findSchemeDepartmentMappingsBySchemeIds(String schemaName, List<Integer> schemeIds) {
        return findMappingIdsByScheme(schemaName, "scheme_department_mapping_table", "parent_department_id", schemeIds);
    }

    public Integer findUserIdByEmail(String schemaName, String email) {
        validateSchemaName(schemaName);
        if (email == null || email.isBlank()) {
            return null;
        }

        String sql = String.format("""
                SELECT id
                FROM %s.user_table
                WHERE lower(email) = lower(?)
                  AND deleted_at IS NULL
                """, schemaName);

        try {
            return jdbcTemplate.queryForObject(sql, Integer.class, email);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public boolean isUserStateAdmin(String schemaName, Integer userId) {
        validateSchemaName(schemaName);
        if (userId == null) {
            return false;
        }

        String sql = String.format("""
                SELECT EXISTS (
                    SELECT 1
                    FROM %s.user_table u
                    WHERE u.id = ?
                      AND u.user_type = (
                          SELECT ut.id
                          FROM common_schema.user_type_master_table ut
                          WHERE lower(ut.c_name) = lower('STATE_ADMIN')
                          LIMIT 1
                      )
                )
                """, schemaName);

        Boolean ok = jdbcTemplate.queryForObject(sql, Boolean.class, userId);
        return Boolean.TRUE.equals(ok);
    }

    public void insertSchemes(String schemaName, List<SchemeCreateRecord> rows) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                INSERT INTO %s.scheme_master_table
                    (uuid, state_scheme_id, centre_scheme_id, scheme_name,
                     fhtc_count, planned_fhtc, house_hold_count,
                     latitude, longitude, channel, work_status, operating_status,
                     created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, NOW(), ?, NULL, NULL)
                """, schemaName);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SchemeCreateRecord row = rows.get(i);
                ps.setString(1, row.uuid());
                ps.setString(2, row.stateSchemeId());
                ps.setString(3, row.centreSchemeId());
                ps.setString(4, row.schemeName());
                ps.setInt(5, row.fhtcCount());
                ps.setInt(6, row.plannedFhtc());
                ps.setInt(7, row.houseHoldCount());
                ps.setObject(8, row.latitude());
                ps.setObject(9, row.longitude());
                ps.setObject(10, row.channel(), Types.INTEGER);
                ps.setInt(11, row.workStatus());
                ps.setInt(12, row.operatingStatus());
                ps.setInt(13, row.createdBy());
                ps.setInt(14, row.updatedBy());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public void updateSchemes(String schemaName, List<SchemeUpdateRecord> rows) {
        validateSchemaName(schemaName);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = String.format("""
                UPDATE %s.scheme_master_table
                SET state_scheme_id = ?,
                    centre_scheme_id = ?,
                    scheme_name = ?,
                    fhtc_count = ?,
                    planned_fhtc = ?,
                    house_hold_count = ?,
                    latitude = ?,
                    longitude = ?,
                    work_status = ?,
                    operating_status = ?,
                    updated_at = NOW(),
                    updated_by = ?
                WHERE id = ?
                """, schemaName);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SchemeUpdateRecord row = rows.get(i);
                ps.setString(1, row.stateSchemeId());
                ps.setString(2, row.centreSchemeId());
                ps.setString(3, row.schemeName());
                ps.setInt(4, row.fhtcCount());
                ps.setInt(5, row.plannedFhtc());
                ps.setInt(6, row.houseHoldCount());
                ps.setObject(7, row.latitude());
                ps.setObject(8, row.longitude());
                ps.setInt(9, row.workStatus());
                ps.setInt(10, row.operatingStatus());
                ps.setInt(11, row.updatedBy());
                ps.setInt(12, row.id());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public boolean updateSchemeStatusesById(
            String schemaName,
            int schemeId,
            Integer workStatus,
            Integer operatingStatus,
            int updatedBy
    ) {
        validateSchemaName(schemaName);
        List<String> updates = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (workStatus != null) {
            updates.add("work_status = ?");
            args.add(workStatus);
        }
        if (operatingStatus != null) {
            updates.add("operating_status = ?");
            args.add(operatingStatus);
        }
        if (updates.isEmpty()) {
            return false;
        }

        updates.add("updated_at = NOW()");
        updates.add("updated_by = ?");
        args.add(updatedBy);
        args.add(schemeId);

        String sql = String.format("""
                UPDATE %s.scheme_master_table
                SET %s
                WHERE id = ?
                  AND deleted_at IS NULL
                """, schemaName, String.join(", ", updates));

        return jdbcTemplate.update(sql, args.toArray()) > 0;
    }

    public void insertLgdMappings(String schemaName, List<SchemeLgdMappingCreateRecord> rows) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                INSERT INTO %s.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, created_at, updated_by, updated_at, deleted_at, deleted_by)
                VALUES (?, ?, ?, ?, NOW(), ?, NOW(), NULL, NULL)
                """, schemaName);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SchemeLgdMappingCreateRecord row = rows.get(i);
                ps.setInt(1, row.schemeId());
                ps.setInt(2, row.parentLgdId());
                ps.setString(3, String.valueOf(row.parentLgdLevel()));
                ps.setInt(4, row.createdBy());
                ps.setInt(5, row.updatedBy());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public void insertSubdivisionMappings(String schemaName, List<SchemeSubdivisionMappingCreateRecord> rows) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                INSERT INTO %s.scheme_department_mapping_table
                    (scheme_id, parent_department_id, parent_department_level, created_by, created_at, updated_by, updated_at, deleted_at, deleted_by)
                VALUES (?, ?, ?, ?, NOW(), ?, NOW(), NULL, NULL)
                """, schemaName);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SchemeSubdivisionMappingCreateRecord row = rows.get(i);
                ps.setInt(1, row.schemeId());
                ps.setInt(2, row.parentDepartmentId());
                ps.setString(3, row.parentDepartmentLevel());
                ps.setInt(4, row.createdBy());
                ps.setInt(5, row.updatedBy());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public int clearSchemeMappingsForSchemes(String schemaName, List<Integer> schemeIds, int actorUserId) {
        validateSchemaName(schemaName);
        if (schemeIds == null || schemeIds.isEmpty()) {
            return 0;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < schemeIds.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }

        String lgdSql = String.format("""
                UPDATE %s.scheme_lgd_mapping_table
                SET deleted_at = NOW(), deleted_by = ?, updated_by = ?, updated_at = NOW()
                WHERE deleted_at IS NULL
                  AND scheme_id IN (%s)
                """, schemaName, placeholders);
        String deptSql = String.format("""
                UPDATE %s.scheme_department_mapping_table
                SET deleted_at = NOW(), deleted_by = ?, updated_by = ?, updated_at = NOW()
                WHERE deleted_at IS NULL
                  AND scheme_id IN (%s)
                """, schemaName, placeholders);

        List<Object> args = new ArrayList<>(schemeIds.size() + 2);
        args.add(actorUserId);
        args.add(actorUserId);
        args.addAll(schemeIds);
        Object[] argArray = args.toArray();

        int lgdUpdated = jdbcTemplate.update(lgdSql, argArray);
        int deptUpdated = jdbcTemplate.update(deptSql, argArray);
        return lgdUpdated + deptUpdated;
    }

    /**
     * Batch existence check for scheme -> LGD mappings.
     */
    public Set<String> findExistingSchemeLgdMappingKeys(String schemaName, List<Integer> schemeIds, List<Integer> lgdIds) {
        return findExistingPairs(schemaName, "scheme_lgd_mapping_table", "scheme_id", "parent_lgd_id", schemeIds, lgdIds);
    }

    /**
     * Batch existence check for scheme -> department mappings.
     */
    public Set<String> findExistingSchemeDepartmentMappingKeys(String schemaName, List<Integer> schemeIds, List<Integer> departmentIds) {
        return findExistingPairs(schemaName, "scheme_department_mapping_table", "scheme_id", "parent_department_id", schemeIds, departmentIds);
    }

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || schemaName.isBlank() || !SAFE_SCHEMA.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    private record SqlAndArgs(String sql, List<Object> args) {}

    private SqlAndArgs buildSchemeWhere(
            String stateSchemeId,
            String schemeName,
            String name,
            Integer workStatus,
            Integer operatingStatus,
            String status
    ) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (stateSchemeId != null && !stateSchemeId.isBlank()) {
            clauses.add("lower(state_scheme_id) = lower(?)");
            args.add(stateSchemeId.trim());
        }
        if (schemeName != null && !schemeName.isBlank()) {
            clauses.add("scheme_name ILIKE ?");
            args.add("%" + schemeName.trim() + "%");
        }
        if (name != null && !name.isBlank()) {
            clauses.add("(scheme_name ILIKE ? OR state_scheme_id ILIKE ?)");
            String pat = "%" + name.trim() + "%";
            args.add(pat);
            args.add(pat);
        }
        if (workStatus != null) {
            clauses.add("work_status = ?");
            args.add(workStatus);
        }
        if (operatingStatus != null) {
            clauses.add("operating_status = ?");
            args.add(operatingStatus);
        }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toLowerCase(Locale.ROOT);
            if ("active".equals(s)) {
                clauses.add("operating_status = 1");
            } else if ("inactive".equals(s)) {
                clauses.add("operating_status <> 1");
            }
        }

        if (clauses.isEmpty()) {
            return new SqlAndArgs("", List.of());
        }
        return new SqlAndArgs(" AND " + String.join(" AND ", clauses), args);
    }

    private String schemeOrderBy(String sortBy, String sortDir) {
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String key = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        String col = switch (key) {
            case "id" -> "id";
            case "state_scheme_id" -> "state_scheme_id";
            case "centre_scheme_id", "center_scheme_id" -> "centre_scheme_id";
            case "scheme_name", "name" -> "scheme_name";
            case "work_status" -> "work_status";
            case "operating_status" -> "operating_status";
            case "created_at" -> "created_at";
            default -> "id";
        };
        return "ORDER BY " + col + " " + dir;
    }

    private String mappingOrderBy(String sortBy, String sortDir, boolean hasDept) {
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String key = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        String col = switch (key) {
            case "id" -> "slm.id";
            case "scheme_name", "name", "alphabetical" -> "LOWER(sm.scheme_name)";
            case "state_scheme_id" -> "sm.state_scheme_id";
            case "village_lgd_code" -> "lgd.lgd_code";
            case "sub_division_name" -> hasDept ? "LOWER(dept.title)" : "slm.id";
            default -> "slm.id";
        };
        return "ORDER BY " + col + " " + dir;
    }

    private boolean hasDepartmentTables(String schemaName) {
        return deptTablesExistCache.computeIfAbsent(schemaName, s -> {
            String dept = jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, s + ".department_location_master_table");
            String sdm = jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, s + ".scheme_department_mapping_table");
            return dept != null && sdm != null;
        });
    }

    private Set<Integer> findExistingIds(String schemaName, String table, List<Integer> ids) {
        validateSchemaName(schemaName);
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }

        // Deduplicate to keep the IN clause small and stable.
        Set<Integer> uniq = new HashSet<>(Math.max(16, ids.size()));
        for (Integer id : ids) {
            if (id != null) {
                uniq.add(id);
            }
        }
        if (uniq.isEmpty()) {
            return Set.of();
        }

        StringBuilder placeholders = new StringBuilder();
        int n = uniq.size();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }

        String sql = String.format(
                "SELECT id FROM %s.%s WHERE deleted_at IS NULL AND id IN (%s)",
                schemaName,
                table,
                placeholders
        );
        Object[] args = uniq.toArray();
        List<Integer> existing = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt("id"), args);
        return new HashSet<>(existing);
    }

    private Set<String> findExistingPairs(
            String schemaName,
            String table,
            String leftColumn,
            String rightColumn,
            List<Integer> leftIds,
            List<Integer> rightIds
    ) {
        validateSchemaName(schemaName);
        if (leftIds == null || leftIds.isEmpty() || rightIds == null || rightIds.isEmpty()) {
            return Set.of();
        }

        Set<Integer> left = new HashSet<>();
        for (Integer id : leftIds) {
            if (id != null) {
                left.add(id);
            }
        }
        Set<Integer> right = new HashSet<>();
        for (Integer id : rightIds) {
            if (id != null) {
                right.add(id);
            }
        }
        if (left.isEmpty() || right.isEmpty()) {
            return Set.of();
        }

        String leftPlaceholders = String.join(",", java.util.Collections.nCopies(left.size(), "?"));
        String rightPlaceholders = String.join(",", java.util.Collections.nCopies(right.size(), "?"));

        String sql = String.format(
                "SELECT %s AS l, %s AS r FROM %s.%s WHERE deleted_at IS NULL AND %s IN (%s) AND %s IN (%s)",
                leftColumn,
                rightColumn,
                schemaName,
                table,
                leftColumn,
                leftPlaceholders,
                rightColumn,
                rightPlaceholders
        );

        List<Object> args = new ArrayList<>(left.size() + right.size());
        args.addAll(left);
        args.addAll(right);

        Set<String> out = new HashSet<>();
        jdbcTemplate.query(sql, rs -> {
            int l = rs.getInt("l");
            int r = rs.getInt("r");
            out.add(l + "|" + r);
        }, args.toArray());
        return out;
    }

    private Map<Integer, Set<Integer>> findMappingIdsByScheme(
            String schemaName,
            String table,
            String childColumn,
            List<Integer> schemeIds
    ) {
        validateSchemaName(schemaName);
        if (schemeIds == null || schemeIds.isEmpty()) {
            return Map.of();
        }

        Set<Integer> uniq = new HashSet<>();
        for (Integer id : schemeIds) {
            if (id != null) {
                uniq.add(id);
            }
        }
        if (uniq.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(uniq.size(), "?"));
        String sql = String.format(
                "SELECT scheme_id, %s AS child_id FROM %s.%s WHERE deleted_at IS NULL AND scheme_id IN (%s)",
                childColumn,
                schemaName,
                table,
                placeholders
        );

        List<Object> args = new ArrayList<>(uniq);
        Map<Integer, Set<Integer>> out = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            int schemeId = rs.getInt("scheme_id");
            int childId = rs.getInt("child_id");
            out.computeIfAbsent(schemeId, k -> new HashSet<>()).add(childId);
        }, args.toArray());
        return out;
    }

    private Map<String, Integer> findIdsByLowerTextKey(String schemaName, String table, String keyColumn, List<String> values) {
        validateSchemaName(schemaName);
        if (values == null || values.isEmpty()) {
            return Map.of();
        }

        // Deduplicate normalized values to keep IN clause small and stable.
        Set<String> uniq = new HashSet<>(Math.max(16, values.size()));
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String t = v.trim();
            if (!t.isBlank()) {
                uniq.add(t.toLowerCase(Locale.ROOT));
            }
        }
        if (uniq.isEmpty()) {
            return Map.of();
        }

        StringBuilder placeholders = new StringBuilder();
        int n = uniq.size();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }

        String sql = String.format(
                "SELECT lower(%s) AS k, id FROM %s.%s WHERE deleted_at IS NULL AND lower(%s) IN (%s)",
                keyColumn,
                schemaName,
                table,
                keyColumn,
                placeholders
        );
        Object[] args = uniq.toArray();

        Map<String, Integer> out = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String k = rs.getString("k");
            int id = rs.getInt("id");
            // If there are duplicates in DB (case-insensitive), keep the first deterministically.
            out.putIfAbsent(k, id);
        }, args);
        return out;
    }
}
