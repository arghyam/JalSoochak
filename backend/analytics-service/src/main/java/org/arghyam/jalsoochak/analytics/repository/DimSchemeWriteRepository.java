package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeMappingsReplacedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeMappingsReplacedEvent.Location;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Writes to {@code dim_scheme_table} that Spring Data cannot express. A scheme has one row per
 * village and sub-division pair, and its details (name, ids, coordinates, statuses, FHTC counts)
 * belong on every one of them, so they are written to all of a scheme's rows at once. Every write runs
 * in the caller's transaction and holds the scheme's advisory lock until that transaction ends.
 *
 * <p>A scheme with no villages sits under its state: the tenant's level-1 location (the lowest id if
 * there are several, as the national dashboard picks it). When the tenant's locations are not loaded
 * yet, {@code parent_lgd_location_id}, which is NOT NULL, falls back to 0. Its lower level ids are left
 * NULL.
 */
@Repository
@RequiredArgsConstructor
public class DimSchemeWriteRepository {

    /**
     * Advisory-lock namespace for a scheme's rows. The number itself is arbitrary; it only has to keep
     * these keys apart from the other two-int advisory locks taken in the shared database.
     */
    static final int SCHEME_LOCK_NAMESPACE = "analytics_schema.dim_scheme_table".hashCode();

    private static final String STATE_ID_SQL = """
            SELECT MIN(lgd_id) AS lgd_id
            FROM analytics_schema.dim_lgd_location_table
            WHERE tenant_id = ? AND lgd_level = 1
            """;

    /**
     * Gives a scheme with no rows a placeholder row under its state, so it has somewhere to be counted
     * before its villages are known. The sub-division is left NULL. {@code ON CONFLICT} covers another
     * writer inserting the same placeholder in between.
     */
    private static final String INSERT_PLACEHOLDER_SQL = """
            WITH state AS (%s)
            INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, level_1_lgd_id, created_at)
            SELECT ?, ?, ?, ?, COALESCE(state.lgd_id, 0), state.lgd_id, NOW()
            FROM state
            WHERE NOT EXISTS (
                SELECT 1 FROM analytics_schema.dim_scheme_table
                WHERE tenant_id = ? AND scheme_id = ?)
            ON CONFLICT ON CONSTRAINT uq_dim_scheme_tenant_scheme_parent_lgd_dept DO NOTHING
            """.formatted(STATE_ID_SQL);

    /** A null FHTC count means the message did not carry it, so the stored value stays. */
    private static final String UPDATE_DETAILS_SQL = """
            UPDATE analytics_schema.dim_scheme_table
            SET scheme_name = ?,
                state_scheme_id = ?,
                centre_scheme_id = ?,
                longitude = ?,
                latitude = ?,
                operating_status = ?,
                work_status = ?,
                fhtc_count = COALESCE(?, fhtc_count),
                planned_fhtc = COALESCE(?, planned_fhtc),
                house_hold_count = COALESCE(?, house_hold_count),
                updated_at = NOW()
            WHERE tenant_id = ? AND scheme_id = ?
            """;

    private static final String SELECT_ROWS_SQL = """
            SELECT id, parent_lgd_location_id, parent_department_location_id
            FROM analytics_schema.dim_scheme_table
            WHERE tenant_id = ? AND scheme_id = ?
            """;

    private static final String DELETE_ROW_SQL = "DELETE FROM analytics_schema.dim_scheme_table WHERE id = ?";

    /** A pair the scheme already has keeps its row; only its level ids are brought up to date. */
    private static final String UPSERT_PAIR_SQL = """
            INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id,
                 level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id,
                 level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT ON CONSTRAINT uq_dim_scheme_tenant_scheme_parent_lgd_dept DO UPDATE
            SET level_1_lgd_id = EXCLUDED.level_1_lgd_id,
                level_2_lgd_id = EXCLUDED.level_2_lgd_id,
                level_3_lgd_id = EXCLUDED.level_3_lgd_id,
                level_4_lgd_id = EXCLUDED.level_4_lgd_id,
                level_5_lgd_id = EXCLUDED.level_5_lgd_id,
                level_6_lgd_id = EXCLUDED.level_6_lgd_id,
                level_1_dept_id = EXCLUDED.level_1_dept_id,
                level_2_dept_id = EXCLUDED.level_2_dept_id,
                level_3_dept_id = EXCLUDED.level_3_dept_id,
                level_4_dept_id = EXCLUDED.level_4_dept_id,
                level_5_dept_id = EXCLUDED.level_5_dept_id,
                level_6_dept_id = EXCLUDED.level_6_dept_id
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Writes the scheme's details to every row it has, first inserting a placeholder row under its state
     * when it has none. The village, sub-division and level ids of existing rows are never changed.
     *
     * @return the number of rows written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int upsertDetails(SchemeEvent details) {
        lockScheme(details.getTenantId(), details.getSchemeId());
        jdbcTemplate.update(INSERT_PLACEHOLDER_SQL,
                details.getTenantId(),
                details.getTenantId(), details.getSchemeId(),
                details.getStateSchemeId(), details.getCentreSchemeId(),
                details.getTenantId(), details.getSchemeId());

        return writeDetails(details);
    }

    /**
     * Makes the scheme's rows exactly one per village and sub-division pair in {@code mappings}, then
     * writes its details to all of them. Rows of pairs that are gone, a placeholder among them, are
     * deleted; rows of pairs that stay are kept. A scheme with no villages gets its state in their place,
     * and one with no sub-divisions gets rows with none.
     *
     * @return the number of rows the scheme now has
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int replaceMappings(SchemeMappingsReplacedEvent mappings) {
        Integer tenantId = mappings.getTenantId();
        Integer schemeId = mappings.getSchemeId();
        lockScheme(tenantId, schemeId);
        List<Location> villages = mappings.getVillages().isEmpty()
                ? List.of(state(tenantId)) : mappings.getVillages();
        // A location with every id null stands in for the sub-division of a scheme that has none.
        List<Location> subDivisions = mappings.getSubDivisions().isEmpty()
                ? List.of(new Location()) : mappings.getSubDivisions();

        Set<Pair> pairs = new HashSet<>();
        List<Object[]> upserts = new ArrayList<>();
        for (Location village : villages) {
            for (Location subDivision : subDivisions) {
                pairs.add(new Pair(village.getId(), subDivision.getId()));
                upserts.add(new Object[] {
                        tenantId, schemeId, mappings.getStateSchemeId(), mappings.getCentreSchemeId(),
                        village.getId(), village.getLevel1Id(), village.getLevel2Id(), village.getLevel3Id(),
                        village.getLevel4Id(), village.getLevel5Id(), village.getLevel6Id(),
                        subDivision.getId(), subDivision.getLevel1Id(), subDivision.getLevel2Id(),
                        subDivision.getLevel3Id(), subDivision.getLevel4Id(), subDivision.getLevel5Id(),
                        subDivision.getLevel6Id()});
            }
        }

        List<Object[]> goneRowIds = jdbcTemplate.query(SELECT_ROWS_SQL,
                        (rs, rowNum) -> new StoredRow(rs.getInt("id"), new Pair(
                                rs.getObject("parent_lgd_location_id", Integer.class),
                                rs.getObject("parent_department_location_id", Integer.class))),
                        tenantId, schemeId)
                .stream()
                .filter(row -> !pairs.contains(row.pair()))
                .map(row -> new Object[] {row.id()})
                .toList();
        jdbcTemplate.batchUpdate(DELETE_ROW_SQL, goneRowIds);
        jdbcTemplate.batchUpdate(UPSERT_PAIR_SQL, upserts);

        return writeDetails(mappings);
    }

    /**
     * Makes another write to the same scheme wait until this transaction ends, so the two cannot interleave
     * their reads and writes of its rows. Messages about one scheme share a partition, but a rebalance, or
     * unkeyed messages sent before keys were added, can still hand two of them to different consumers.
     * Two schemes whose keys collide only make one of them wait.
     */
    private void lockScheme(Integer tenantId, Integer schemeId) {
        // Two int keys rather than one bigint, so the namespace keeps these apart from other locks.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, ?)",
                ps -> {
                    ps.setInt(1, SCHEME_LOCK_NAMESPACE);
                    ps.setInt(2, Objects.hash(tenantId, schemeId));
                },
                rs -> null);
    }

    private Location state(Integer tenantId) {
        Integer stateId = jdbcTemplate.queryForObject(STATE_ID_SQL, Integer.class, tenantId);
        return new Location(stateId != null ? stateId : 0, stateId, null, null, null, null, null);
    }

    private int writeDetails(SchemeEvent details) {
        return jdbcTemplate.update(UPDATE_DETAILS_SQL,
                details.getSchemeName(),
                details.getStateSchemeId(),
                details.getCentreSchemeId(),
                details.getLongitude(),
                details.getLatitude(),
                details.getStatus(),
                details.getWorkStatus(),
                details.getFhtcCount(),
                details.getPlannedFhtc(),
                details.getHouseHoldCount(),
                details.getTenantId(), details.getSchemeId());
    }

    /** A village and sub-division; the sub-division is null for a scheme that has none. */
    private record Pair(Integer villageId, Integer subDivisionId) {
    }

    private record StoredRow(int id, Pair pair) {
    }
}
