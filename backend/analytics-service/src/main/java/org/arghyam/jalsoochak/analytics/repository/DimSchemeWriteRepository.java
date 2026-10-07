package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes to {@code dim_scheme_table} that Spring Data cannot express. A scheme has one row per
 * village and sub-division pair, and its details (name, ids, coordinates, statuses, FHTC counts)
 * belong on every one of them, so they are written to all of a scheme's rows at once. It joins the
 * caller's transaction.
 */
@Repository
@RequiredArgsConstructor
public class DimSchemeWriteRepository {

    /**
     * Gives a scheme with no rows a placeholder row under its state, so it has somewhere to be counted
     * before its villages are known. The state is the tenant's level-1 location (the lowest id if there
     * are several, as the national dashboard picks it). When the tenant's locations are not loaded yet,
     * {@code parent_lgd_location_id}, which is NOT NULL, falls back to 0. The sub-division and the lower
     * level ids are left NULL. {@code ON CONFLICT} covers another writer inserting the same placeholder
     * in between.
     */
    private static final String INSERT_PLACEHOLDER_SQL = """
            WITH state AS (
                SELECT MIN(lgd_id) AS lgd_id
                FROM analytics_schema.dim_lgd_location_table
                WHERE tenant_id = ? AND lgd_level = 1)
            INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, state_scheme_id, centre_scheme_id,
                 parent_lgd_location_id, level_1_lgd_id, created_at)
            SELECT ?, ?, ?, ?, COALESCE(state.lgd_id, 0), state.lgd_id, NOW()
            FROM state
            WHERE NOT EXISTS (
                SELECT 1 FROM analytics_schema.dim_scheme_table
                WHERE tenant_id = ? AND scheme_id = ?)
            ON CONFLICT ON CONSTRAINT uq_dim_scheme_tenant_scheme_parent_lgd_dept DO NOTHING
            """;

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

    private final JdbcTemplate jdbcTemplate;

    /**
     * Writes the scheme's details to every row it has, first inserting a placeholder row under its state
     * when it has none. The village, sub-division and level ids of existing rows are never changed.
     *
     * @return the number of rows written
     */
    public int upsertDetails(SchemeEvent details) {
        jdbcTemplate.update(INSERT_PLACEHOLDER_SQL,
                details.getTenantId(),
                details.getTenantId(), details.getSchemeId(),
                details.getStateSchemeId(), details.getCentreSchemeId(),
                details.getTenantId(), details.getSchemeId());

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
}
