package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the household size a tenant's population figures are built on.
 *
 * <p>Population is modelled as {@code Σ fhtc_count × person_count_per_household}, so this value sits
 * underneath every LPCD the platform reports. It is per tenant because household size genuinely
 * differs between states.</p>
 *
 * <p>Read through SQL rather than the {@code DimTenant} entity, which does not map this column; the
 * dashboard aggregates read it the same way, as an inline sub-select. The fallback of 5 matches the
 * {@code COALESCE(..., 5)} in those queries, so a tenant with no configured value reports one LPCD
 * rather than a different number on a report than on a dashboard.</p>
 */
@Repository
@RequiredArgsConstructor
public class TenantPopulationRepository {

    /** Matches the COALESCE default in the dashboard population SQL. */
    public static final int DEFAULT_PERSONS_PER_HOUSEHOLD = 5;

    private final JdbcTemplate jdbcTemplate;

    public int personsPerHousehold(Integer tenantId) {
        if (tenantId == null) {
            return DEFAULT_PERSONS_PER_HOUSEHOLD;
        }
        // A scalar sub-select yields NULL when the tenant has no row, which COALESCE then resolves —
        // so this always returns exactly one row. A bare SELECT ... WHERE tenant_id = ? would instead
        // throw EmptyResultDataAccessException for an unknown tenant.
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(
                         (SELECT NULLIF(person_count_per_household, 0)
                            FROM analytics_schema.dim_tenant_table
                           WHERE tenant_id = ?),
                         ?)
                """, Integer.class, tenantId, DEFAULT_PERSONS_PER_HOUSEHOLD);
        return value != null && value > 0 ? value : DEFAULT_PERSONS_PER_HOUSEHOLD;
    }
}
