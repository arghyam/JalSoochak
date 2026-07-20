package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimTenantWorkStatusFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DimTenantWorkStatusFilterRepository extends JpaRepository<DimTenantWorkStatusFilter, Long> {

    /** The currently-in-effect filter row for a tenant (the open interval). */
    Optional<DimTenantWorkStatusFilter> findByTenantIdAndEffectiveToIsNull(Integer tenantId);

    /**
     * The filter row in effect for {@code tenantId} on {@code date} using half-open
     * intervals: effective_from &lt;= date AND (effective_to IS NULL OR date &lt; effective_to).
     */
    @Query("""
            SELECT f FROM DimTenantWorkStatusFilter f
            WHERE f.tenantId = :tenantId
              AND f.effectiveFrom <= :date
              AND (f.effectiveTo IS NULL OR f.effectiveTo > :date)
            """)
    Optional<DimTenantWorkStatusFilter> findEffectiveForDate(@Param("tenantId") Integer tenantId,
                                                             @Param("date") LocalDate date);
}
