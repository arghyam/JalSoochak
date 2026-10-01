package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeDimensionReplacedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes a scheme's complete set of {@code dim_scheme_table} rows.
 *
 * <p>The table holds one row per scheme × village × sub-division, keyed by
 * {@code uq_dim_scheme_tenant_scheme_parent_lgd_dept} (UNIQUE NULLS NOT DISTINCT). Every scheme-level
 * column is repeated on each row, which is why a single-row upsert ({@code DimensionServiceImpl
 * .upsertScheme}) leaves the others drifting. This repository works on the whole set instead.
 */
@Repository
@RequiredArgsConstructor
public class SchemeDimensionReplaceRepository {

    private static final String UPSERT = """
            INSERT INTO analytics_schema.dim_scheme_table (
                scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                parent_lgd_location_id,
                level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                parent_department_location_id,
                level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                operating_status, work_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT ON CONSTRAINT uq_dim_scheme_tenant_scheme_parent_lgd_dept DO UPDATE SET
                scheme_name = EXCLUDED.scheme_name, state_scheme_id = EXCLUDED.state_scheme_id,
                centre_scheme_id = EXCLUDED.centre_scheme_id, longitude = EXCLUDED.longitude,
                latitude = EXCLUDED.latitude,
                level_1_lgd_id = EXCLUDED.level_1_lgd_id, level_2_lgd_id = EXCLUDED.level_2_lgd_id,
                level_3_lgd_id = EXCLUDED.level_3_lgd_id, level_4_lgd_id = EXCLUDED.level_4_lgd_id,
                level_5_lgd_id = EXCLUDED.level_5_lgd_id, level_6_lgd_id = EXCLUDED.level_6_lgd_id,
                level_1_dept_id = EXCLUDED.level_1_dept_id, level_2_dept_id = EXCLUDED.level_2_dept_id,
                level_3_dept_id = EXCLUDED.level_3_dept_id, level_4_dept_id = EXCLUDED.level_4_dept_id,
                level_5_dept_id = EXCLUDED.level_5_dept_id, level_6_dept_id = EXCLUDED.level_6_dept_id,
                operating_status = EXCLUDED.operating_status, work_status = EXCLUDED.work_status,
                fhtc_count = EXCLUDED.fhtc_count, planned_fhtc = EXCLUDED.planned_fhtc,
                house_hold_count = EXCLUDED.house_hold_count, updated_at = NOW()
            """;

    private record Existing(int id, Integer lgd, Integer dept) {
    }

    private final JdbcTemplate jdbcTemplate;

    /**
     * Makes the scheme's rows exactly {@code event.rows}: rows for locations it no longer has are
     * deleted, the rest are upserted with the event's attributes. With no rows in the event the
     * location set is left alone and only the attributes are written onto every existing row — a
     * scheme is never left with no row at all.
     *
     * @return rows deleted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int replace(SchemeDimensionReplacedEvent e) {
        List<SchemeDimensionReplacedEvent.Row> rows = e.getRows() == null ? List.of() : e.getRows();
        if (rows.isEmpty()) {
            syncAttributes(e);
            return 0;
        }
        List<Existing> existing = jdbcTemplate.query("""
                        SELECT id, parent_lgd_location_id, parent_department_location_id
                        FROM analytics_schema.dim_scheme_table WHERE tenant_id = ? AND scheme_id = ?
                        """,
                (rs, n) -> new Existing(rs.getInt(1), (Integer) rs.getObject(2), (Integer) rs.getObject(3)),
                e.getTenantId(), e.getSchemeId());
        List<Integer> stale = new ArrayList<>();
        for (Existing row : existing) {
            boolean wanted = rows.stream().anyMatch(r -> Objects.equals(r.getParentLgdLocationId(), row.lgd())
                    && Objects.equals(r.getParentDepartmentLocationId(), row.dept()));
            if (!wanted) {
                stale.add(row.id());
            }
        }
        for (Integer id : stale) {
            jdbcTemplate.update("DELETE FROM analytics_schema.dim_scheme_table WHERE id = ?", id);
        }
        for (SchemeDimensionReplacedEvent.Row row : rows) {
            List<Object> args = new ArrayList<>();
            args.add(e.getSchemeId());
            args.add(e.getTenantId());
            args.add(e.getSchemeName());
            args.add(nz(e.getStateSchemeId()));
            args.add(nz(e.getCentreSchemeId()));
            args.add(e.getLongitude());
            args.add(e.getLatitude());
            args.add(row.getParentLgdLocationId());
            args.addAll(levels(row.getLgdLevels()));
            args.add(row.getParentDepartmentLocationId());
            args.addAll(levels(row.getDeptLevels()));
            args.add(nz(e.getOperatingStatus()));
            args.add(e.getWorkStatus());
            args.add(e.getFhtcCount());
            args.add(e.getPlannedFhtc());
            args.add(e.getHouseHoldCount());
            jdbcTemplate.update(UPSERT, args.toArray());
        }
        return stale.size();
    }

    /** Pushes the scheme-level columns onto every row the scheme has, leaving locations alone. */
    private void syncAttributes(SchemeDimensionReplacedEvent e) {
        jdbcTemplate.update("""
                        UPDATE analytics_schema.dim_scheme_table
                        SET scheme_name = ?, state_scheme_id = ?, centre_scheme_id = ?, longitude = ?, latitude = ?,
                            operating_status = ?, work_status = ?, fhtc_count = ?, planned_fhtc = ?,
                            house_hold_count = ?, updated_at = NOW()
                        WHERE tenant_id = ? AND scheme_id = ?
                        """,
                e.getSchemeName(), nz(e.getStateSchemeId()), nz(e.getCentreSchemeId()), e.getLongitude(),
                e.getLatitude(), nz(e.getOperatingStatus()), e.getWorkStatus(), e.getFhtcCount(), e.getPlannedFhtc(),
                e.getHouseHoldCount(), e.getTenantId(), e.getSchemeId());
    }

    /** Exactly six values for levels 1..6, padding with {@code null}. */
    private static List<Integer> levels(List<Integer> given) {
        List<Integer> out = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            out.add(given != null && i < given.size() ? given.get(i) : null);
        }
        return out;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
