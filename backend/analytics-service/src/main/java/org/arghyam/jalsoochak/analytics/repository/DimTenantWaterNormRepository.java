package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimTenantWaterNorm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DimTenantWaterNormRepository extends JpaRepository<DimTenantWaterNorm, Long> {

    /** The currently-in-effect norm row for a tenant (the open interval). */
    Optional<DimTenantWaterNorm> findByTenantIdAndEffectiveToIsNull(Integer tenantId);

    /**
     * The norm row in effect for {@code tenantId} on {@code date} using half-open
     * intervals: effective_from &lt;= date AND (effective_to IS NULL OR date &lt; effective_to).
     */
    @Query("""
            SELECT n FROM DimTenantWaterNorm n
            WHERE n.tenantId = :tenantId
              AND n.effectiveFrom <= :date
              AND (n.effectiveTo IS NULL OR n.effectiveTo > :date)
            """)
    Optional<DimTenantWaterNorm> findEffectiveForDate(@Param("tenantId") Integer tenantId,
                                                      @Param("date") LocalDate date);
}
