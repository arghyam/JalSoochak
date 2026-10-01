package org.arghyam.jalsoochak.scheme.kafka;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds {@code SCHEME_DIMENSION_REPLACED}, the event analytics rebuilds a scheme's whole
 * {@code dim_scheme_table} set from. Every path that changes a scheme or its locations sends it — the
 * scheme and mapping CSV uploads, the status PATCH and the state sync — so they all describe a scheme
 * the same way.
 *
 * <p>One row per (live LGD mapping × live department mapping) of the scheme; a scheme with no
 * department mapping gets one row per LGD mapping with a {@code null} department. The tenant DB stores
 * the two mapping kinds separately, so the cross product is the most it can say. Each row carries the
 * ancestor id at levels 1–6 of both trees ({@code null} where a tree has no node at that level), read
 * with one recursive query per tree for the schemes asked about only.
 *
 * <p>{@code locationsKnown} is always {@code true}: the rows were read from the tenant DB, so an empty
 * list means the scheme has no live LGD mapping and analytics drops the location rows it still holds
 * for it. An event without the flag (none is sent today) only realigns attributes.
 */
@Component
public class SchemeDimensionEvents {

    public static final String EVENT_TYPE = "SCHEME_DIMENSION_REPLACED";

    private static final Pattern SAFE_SCHEMA = Pattern.compile("^tenant_[a-z0-9_]{1,32}$");
    private static final int LEVELS = 6;
    private static final int CHUNK = 1000;
    /** A parent cycle in a location tree must not spin the recursive query forever. */
    private static final int MAX_DEPTH = 12;

    private record Attributes(int schemeId, String name, String stateSchemeId, String centreSchemeId,
                              Double latitude, Double longitude, int operatingStatus, int workStatus,
                              int fhtcCount, int plannedFhtc, int houseHoldCount) {
    }

    private final JdbcTemplate jdbc;

    public SchemeDimensionEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One event per live scheme among {@code schemeIds}; deleted or unknown ids are skipped. */
    public List<Map<String, Object>> build(String schema, Integer tenantId, Collection<Integer> schemeIds) {
        String s = safe(schema);
        List<Integer> ids = new ArrayList<>(new LinkedHashSet<>(schemeIds));
        List<Map<String, Object>> events = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            Integer[] chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK)).toArray(new Integer[0]);
            Map<Integer, List<Integer>> lgdByScheme = mappings(s, "scheme_lgd_mapping_table", "parent_lgd_id", chunk);
            Map<Integer, List<Integer>> deptByScheme =
                    mappings(s, "scheme_department_mapping_table", "parent_department_id", chunk);
            Map<Integer, Map<Integer, Integer>> lgdAncestors =
                    ancestors(s, "lgd_location_master_table", "lgd_location_config_id", lgdByScheme);
            Map<Integer, Map<Integer, Integer>> deptAncestors =
                    ancestors(s, "department_location_master_table", "department_location_config_id", deptByScheme);

            for (Attributes a : attributes(s, chunk)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                List<Integer> departments = deptByScheme.getOrDefault(a.schemeId(), List.of());
                for (Integer lgdId : lgdByScheme.getOrDefault(a.schemeId(), List.of())) {
                    for (Integer deptId : departments.isEmpty() ? java.util.Collections.<Integer>singletonList(null) : departments) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("parentLgdLocationId", lgdId);
                        row.put("lgdLevels", levels(lgdAncestors.get(lgdId)));
                        row.put("parentDepartmentLocationId", deptId);
                        row.put("deptLevels", deptId == null ? null : levels(deptAncestors.get(deptId)));
                        rows.add(row);
                    }
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventType", EVENT_TYPE);
                event.put("tenantId", tenantId);
                event.put("schemeId", a.schemeId());
                event.put("schemeName", a.name());
                event.put("stateSchemeId", safeParseInt(a.stateSchemeId()));
                event.put("centreSchemeId", safeParseInt(a.centreSchemeId()));
                event.put("latitude", a.latitude());
                event.put("longitude", a.longitude());
                event.put("operatingStatus", a.operatingStatus());
                event.put("workStatus", a.workStatus());
                event.put("fhtcCount", a.fhtcCount());
                event.put("plannedFhtc", a.plannedFhtc());
                event.put("houseHoldCount", a.houseHoldCount());
                event.put("locationsKnown", true);
                event.put("rows", rows);
                events.add(event);
            }
        }
        return events;
    }

    private List<Attributes> attributes(String s, Integer[] ids) {
        return jdbc.query("SELECT id, scheme_name, state_scheme_id, centre_scheme_id, latitude, longitude, operating_status, "
                        + "work_status, fhtc_count, planned_fhtc, house_hold_count FROM " + s + ".scheme_master_table "
                        + "WHERE deleted_at IS NULL AND id = ANY (?) ORDER BY id",
                (rs, n) -> new Attributes(rs.getInt("id"), rs.getString("scheme_name"), rs.getString("state_scheme_id"),
                        rs.getString("centre_scheme_id"), nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"),
                        rs.getInt("operating_status"), rs.getInt("work_status"), rs.getInt("fhtc_count"),
                        rs.getInt("planned_fhtc"), rs.getInt("house_hold_count")),
                (Object) ids);
    }

    /** scheme id → its live location ids, in mapping order, de-duplicated. */
    private Map<Integer, List<Integer>> mappings(String s, String table, String column, Integer[] ids) {
        Map<Integer, List<Integer>> byScheme = new HashMap<>();
        jdbc.query("SELECT scheme_id, " + column + " FROM " + s + "." + table
                        + " WHERE deleted_at IS NULL AND scheme_id = ANY (?) ORDER BY id",
                rs -> {
                    List<Integer> list = byScheme.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>());
                    if (!list.contains(rs.getInt(2))) {
                        list.add(rs.getInt(2));
                    }
                },
                (Object) ids);
        return byScheme;
    }

    /** location id → (level → ancestor id), own level included, for every location the schemes use. */
    private Map<Integer, Map<Integer, Integer>> ancestors(String s, String table, String configColumn,
                                                          Map<Integer, List<Integer>> locationsByScheme) {
        LinkedHashSet<Integer> leaves = new LinkedHashSet<>();
        locationsByScheme.values().forEach(leaves::addAll);
        Map<Integer, Map<Integer, Integer>> byLeaf = new HashMap<>();
        if (leaves.isEmpty()) {
            return byLeaf;
        }
        jdbc.query("""
                        WITH RECURSIVE chain AS (
                            SELECT n.id AS leaf, n.id, n.parent_id, n.%2$s AS config_id, 1 AS depth
                            FROM %1$s.%3$s n WHERE n.id = ANY (?)
                            UNION ALL
                            SELECT c.leaf, p.id, p.parent_id, p.%2$s, c.depth + 1
                            FROM chain c JOIN %1$s.%3$s p ON p.id = c.parent_id
                            WHERE c.depth < %4$d
                        )
                        SELECT chain.leaf, chain.id, cfg.level
                        FROM chain JOIN %1$s.location_config_master_table cfg ON cfg.id = chain.config_id
                        """.formatted(s, configColumn, table, MAX_DEPTH),
                rs -> {
                    byLeaf.computeIfAbsent(rs.getInt("leaf"), k -> new HashMap<>())
                            .putIfAbsent(rs.getInt("level"), rs.getInt("id"));
                },
                (Object) leaves.toArray(new Integer[0]));
        return byLeaf;
    }

    private static List<Integer> levels(Map<Integer, Integer> byLevel) {
        List<Integer> levels = new ArrayList<>(LEVELS);
        for (int level = 1; level <= LEVELS; level++) {
            levels.add(byLevel == null ? null : byLevel.get(level));
        }
        return levels;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    static Integer safeParseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String safe(String schema) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("unsafe tenant schema name");
        }
        return schema;
    }
}
